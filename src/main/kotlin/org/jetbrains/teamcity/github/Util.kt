

package org.jetbrains.teamcity.github

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.log.Loggers
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.connections.ConnectionDescriptor
import jetbrains.buildServer.serverSide.connections.ProjectConnectionsManager
import jetbrains.buildServer.serverSide.healthStatus.HealthStatusScope
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.github.GHEOAuthProvider
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.serverSide.oauth.github.GitHubOAuthProvider
import jetbrains.buildServer.util.StringUtil
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.vcs.VcsRoot
import jetbrains.buildServer.vcs.VcsRootInstance
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.regex.Pattern

class Util {
    companion object {
        private const val LOG_CATEGORY = Loggers.VCS_CATEGORY + ".CommitHooks"
        private val LOG = getLogger("Util")

        fun getLogger(name: String): Logger {
            return Logger.getInstance("$LOG_CATEGORY.$name")
        }

        fun getLogger(clazz: Class<out Any>): Logger {
            return getLogger(clazz.simpleName)
        }

        fun getGitHubInfo(root: VcsRoot): GitHubRepositoryInfo? {
            if (root.vcsName != Constants.VCS_NAME_GIT) return null
            val url = root.properties[Constants.VCS_PROPERTY_GIT_URL] ?: return null

            if (StringUtil.hasParameterReferences(url)) return null

            // Consider checking push_url also
            return getGitHubInfo(url)
        }

        fun getGitHubInfo(url: String): GitHubRepositoryInfo? {
            return parseGitRepoUrl(url)
        }

        fun getProjects(roots: Collection<SVcsRoot>): Set<SProject> = roots.map { it.project }.toCollection(HashSet())

        private val GITHUB_REPO_URL_PATTERN = "([^/:@]+)[/:]([a-zA-Z0-9\\.\\-_]+)/([a-zA-Z0-9\\.\\-_]+)$".toPattern()

        fun parseGitRepoUrl(url: String): GitHubRepositoryInfo? {
            val matcher = GITHUB_REPO_URL_PATTERN.matcher(url)
            if (!matcher.find()) return null
            val protocol = url.substring(0, matcher.start())
            if (!isSupportedProtocol(protocol)) return null
            val host = matcher.group(1) ?: return null
            val owner = matcher.group(2) ?: return null
            val name = matcher.group(3)?.removeSuffix(".git") ?: return null
            return GitHubRepositoryInfo(host, owner, name)
        }

        fun isSupportedProtocol(candidate: String): Boolean {
            if (candidate in setOf("git@", ""))
                return true;
            val username = candidate.substringAfter("://")
            if (username !in setOf("git@", ""))
                return false;
            val protocol = candidate.substringBefore("://")
            when (protocol.lowercase()) {
                "https" -> return true
                "http" -> return true
                "ssh" -> return true
                "git" -> return true
                else -> return false
            }
        }

        fun findConnections(manager: ProjectConnectionsManager, project: SProject, server: String): List<OAuthConnectionDescriptor> {
            return manager.getAvailableConnections(project)
                    .filter {
                        it is OAuthConnectionDescriptor && isConnectionToServer(it, server)
                    }.map {
                        it as OAuthConnectionDescriptor
                    }
        }

        fun isConnectionToServer(connection: OAuthConnectionDescriptor, server: String): Boolean {
            when (connection.connectionProvider.type) {
                GHEOAuthProvider.TYPE -> {
                    // Check server url
                    val url = connection.parameters[GitHubConstants.GITHUB_URL_PARAM] ?: return false
                    if (!isSameUrl(server, url)) {
                        return false
                    }
                }
                GitHubOAuthProvider.TYPE -> {
                    if (!isSameUrl(server, "github.com")) {
                        return false
                    }
                }
                else -> return false
            }
            return connection.parameters[GitHubConstants.CLIENT_ID_PARAM] != null && connection.parameters[GitHubConstants.CLIENT_SECRET_PARAM] != null
        }

        fun isSameUrl(host: String, url: String): Boolean {
            val urlHost = getHost(url) ?: return url == host
            if (urlHost == host) return true
            val u2: URL
            return try {
                u2 = URL(host)
                urlHost == u2.host
            } catch(e: Exception) {
                false
            }
        }

        private fun getHost(url: String): String? {
            try {
                val uri = URI(url)
                if (uri.scheme == null) {
                    return URL("http://$url").host
                }
                return uri.toURL().host
            } catch(e: MalformedURLException) {
                return null
            } catch(e: URISyntaxException) {
                return null
            }
        }


        fun isSuitableVcsRoot(root: VcsRoot, checkUrl: Boolean = true): Boolean {
            if (root.vcsName != Constants.VCS_NAME_GIT) return false
            val url = root.properties[Constants.VCS_PROPERTY_GIT_URL] ?: return false
            if (!checkUrl) return true
            if (StringUtil.hasParameterReferences(url)) return false
            return getGitHubInfo(url) != null
        }

        fun findSuitableRoots(scope: HealthStatusScope, collector: (SVcsRoot) -> Boolean) {
            findSuitableRoots(scope.buildTypes, false, collector)
        }

        fun findSuitableRoots(project: SProject, recursive: Boolean = false, archived: Boolean = false, collector: (SVcsRoot) -> Boolean) {
            val list = if (recursive) project.buildTypes else project.ownBuildTypes
            findSuitableRoots(list, archived, collector)
        }

        fun findSuitableRoots(buildTypes: Collection<SBuildType>, archived: Boolean = false, collector: (SVcsRoot) -> Boolean) {
            for (bt in buildTypes) {
                if (!archived && bt.project.isArchived) continue
                for (it in bt.vcsRoots) {
                    if (isSuitableVcsRoot(it, false)) {
                        if (!collector(it)) return
                    }
                }
            }
        }

        /**
         * Returns project suitable Git SVcsRoots or VcsRootInstances if there are OAuth connections corresponding to these VCS roots
         */
        fun getVcsRootsWhereHookCanBeInstalled(project: SProject, connectionsManager: ProjectConnectionsManager): List<VcsRootInstance> {
            val result: MutableCollection<Pair<SBuildType, VcsRootInstance>> = LinkedHashSet()
            doGetVcsRootsWhereHookCanBeInstalled(connectionsManager, false, project, recursive = true, result = result)
            return result.map { it.second }.toSet().toList()
        }

        /**
         * Returns project suitable Git SVcsRoots or VcsRootInstances if there are OAuth connections corresponding to these VCS roots and GitHub App Connections are not configured
         */
        fun getVcsRootsWhereHookCanBeInstalledForSuggestion(buildTypes: Collection<SBuildType>, connectionsManager: ProjectConnectionsManager): List<Pair<SBuildType, VcsRootInstance>> {
            val result: MutableCollection<Pair<SBuildType, VcsRootInstance>> = LinkedHashSet()
            val mapProjectToBuildTypes = buildTypes.groupBy { it.project }
            for ((project, types) in mapProjectToBuildTypes) {
                if (isGitHubAppConfigured(project, connectionsManager)) continue
                doGetVcsRootsWhereHookCanBeInstalled(connectionsManager, false, project, buildTypes = types, recursive = false, result = result)
            }
            return result.toList()
        }

        /**
         * Returns project suitable Git SVcsRoots or VcsRootInstances if there are OAuth connections corresponding to these VCS roots
         */
        fun isVcsRootsWhereHookCanBeInstalled(project: SProject, connectionsManager: ProjectConnectionsManager): Boolean {
            val result: MutableCollection<Pair<SBuildType, VcsRootInstance>> = ArrayList(1)
            doGetVcsRootsWhereHookCanBeInstalled(connectionsManager, true, project, recursive = true, result = result)
            return result.isNotEmpty()
        }

        private fun doGetVcsRootsWhereHookCanBeInstalled(connectionsManager: ProjectConnectionsManager,
                                                         fast: Boolean,
                                                         project: SProject,
                                                         gitHubServers: Set<String> = getAvailableGitHubServers(project, connectionsManager),
                                                         buildTypes: List<SBuildType> = project.ownBuildTypes,
                                                         inner: Boolean = false,
                                                         recursive: Boolean = true,
                                                         checkedRoots: MutableMap<VcsRootInstance, Boolean> = mutableMapOf(),
                                                         result: MutableCollection<Pair<SBuildType, VcsRootInstance>>) {
            val start = if (!inner) System.currentTimeMillis() else 0

            if (project.isArchived) return

            if (gitHubServers.isEmpty()) return
            val oauthServersPattern = lazy { Pattern.compile(gitHubServers.joinToString(separator = "|") { Pattern.quote(it) }) }

            if (gitHubServers.isNotEmpty()) for (bt in buildTypes) {
                for (vri in bt.vcsRootInstances) {
                    val alreadyChecked = checkedRoots[vri]
                    if (alreadyChecked != null && alreadyChecked) {
                        result.add(bt to vri)
                        continue
                    }

                    if (alreadyChecked != null) continue

                    checkedRoots[vri] = false

                    if (isSuitableVcsRoot(vri, false)) {
                        val url = vri.properties[Constants.VCS_PROPERTY_GIT_URL] ?: continue
                        if (!oauthServersPattern.value.matcher(url).find()) {
                            LOG.debug("Found Git VCS root instance '$vri' but it's url ($url) not mentions any of oauth connected servers: $gitHubServers")
                            continue
                        }
                        val info = getGitHubInfo(vri)
                        if (info == null) {
                            LOG.debug("Suitable GitHub-like VCS root instance '$vri' ignored: GitHubInfo is null, url is: $url")
                        } else if (!gitHubServers.contains(info.server)) {
                            LOG.debug("Suitable GitHub-like VCS root instance '$vri' ignored: there's no oauth connection to '${info.server}'")
                        } else {
                            LOG.debug("Found Suitable GitHub-like VCS root instance '$vri' with oauth connection to '${info.server}'")
                            result.add(bt to vri)
                            checkedRoots[vri] = true
                            if (fast) {
                                if (!inner) LOG.debug("In project '$project' found at least one VCS root instance with OAuth connection in fast mode in ~${System.currentTimeMillis() - start} ms")
                                return
                            }
                        }
                    }
                }
            }

            if (recursive) for (subProject in project.ownProjects) {
                if (subProject.isVirtual) continue
                val availableGitHubServers = mutableSetOf<String>()
                availableGitHubServers.addAll(gitHubServers)
                availableGitHubServers.addAll(getOwnGitHubServers(subProject, connectionsManager))
                doGetVcsRootsWhereHookCanBeInstalled(connectionsManager, fast, subProject,
                                                     recursive = true, result = result,
                                                     inner = true, gitHubServers = availableGitHubServers,
                                                     checkedRoots = checkedRoots)
                if (fast && result.isNotEmpty()) return
            }

            if (!inner) {
                LOG.info("In project '$project' found ${result.size} VCS root${result.size.s} with OAuth connection in ~${System.currentTimeMillis() - start} ms")
                LOG.debug("In project '$project' found ${result.size} VCS root${result.size.s} with OAuth connection: $result")
            }
        }

        fun getAvailableGitHubServers(project: SProject, connectionsManager: ProjectConnectionsManager): Set<String> {
            return getGitHubRelatedServers(connectionsManager.getAvailableConnections(project))
        }

        fun getOwnGitHubServers(project: SProject, connectionsManager: ProjectConnectionsManager): Set<String> {
            return getGitHubRelatedServers(connectionsManager.getOwnAvailableConnections(project))
        }

        private fun getGitHubRelatedServers(connections: List<ConnectionDescriptor>): Set<String> {
            return connections.filter {
                it.parameters[GitHubConstants.CLIENT_ID_PARAM] != null && it.parameters[GitHubConstants.CLIENT_SECRET_PARAM] != null
            }
                    .mapNotNull {
                        when (it.connectionProvider.type) {
                            GHEOAuthProvider.TYPE -> {
                                it.parameters[GitHubConstants.GITHUB_URL_PARAM]?.let { url -> getHost(url) }
                            }

                            GitHubOAuthProvider.TYPE -> {
                                "github.com"
                            }

                            else -> null
                        }
                    }
                    .toHashSet()
        }

        fun isGitHubAppConfigured(project: SProject, connectionsManager: ProjectConnectionsManager): Boolean {
            return connectionsManager.getAvailableConnections(project).find { it.connectionProvider.type == "GitHubApp" } != null
        }
    }
}