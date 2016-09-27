package org.jetbrains.teamcity.github

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.log.Loggers
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.healthStatus.HealthStatusScope
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
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
import java.util.*
import java.util.regex.Pattern

class Util {
    companion object {
        val LOG_CATEGORY = Loggers.SERVER_CATEGORY + ".CommitHooks"
        private val LOG = Logger.getInstance(LOG_CATEGORY + ".Util")

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

        fun getProjects(roots: Collection<SVcsRoot>): Set<SProject> = roots.map { it.project }.toCollection(HashSet<SProject>())

        val GITHUB_REPO_URL_PATTERN = "([^/:@]+)[/:]([a-zA-Z0-9\\.\\-_]+)/([a-zA-Z0-9\\.\\-_]+)$".toPattern()

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
            when (candidate) {
                "https://" -> return true
                "http://" -> return true
                "ssh://" -> return true
                "git://" -> return true
                "git@" -> return true
                "" -> return true
                else -> return false
            }
        }

        fun findConnections(manager: OAuthConnectionsManager, project: SProject, server: String): List<OAuthConnectionDescriptor> {
            return manager.getAvailableConnections(project)
                    .filter {
                        it != null && isConnectionToServer(it, server)
                    }
        }

        fun isConnectionToServer(connection: OAuthConnectionDescriptor, server: String): Boolean {
            when (connection.oauthProvider.type) {
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
            try {
                u2 = URL(host)
                return urlHost == u2.host
            } catch(e: Exception) {
                return false
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
        fun getVcsRootsWhereHookCanBeInstalled(project: SProject, connectionsManager: OAuthConnectionsManager): List<VcsRootInstance> {
            return doGetVcsRootsWhereHookCanBeInstalled(project, connectionsManager, false)
        }

        /**
         * Returns project suitable Git SVcsRoots or VcsRootInstances if there are OAuth connections corresponding to these VCS roots
         */
        fun isVcsRootsWhereHookCanBeInstalled(project: SProject, connectionsManager: OAuthConnectionsManager): Boolean {
            return doGetVcsRootsWhereHookCanBeInstalled(project, connectionsManager, true).isNotEmpty()
        }

        private fun doGetVcsRootsWhereHookCanBeInstalled(project: SProject, connectionsManager: OAuthConnectionsManager, fast: Boolean): List<VcsRootInstance> {
            val start = System.currentTimeMillis()

            val oauthServers = getOAuthServers(project, connectionsManager)
            if (oauthServers.isEmpty()) return emptyList()

            val oauthServersPattern = Pattern.compile(oauthServers.joinToString(separator = "|") { Pattern.quote(it) })

            val result: MutableCollection<VcsRootInstance> = if (fast) ArrayList(0) else LinkedHashSet()

            for (bt in project.buildTypes) {
                if (bt.project.isArchived) continue
                for (root in bt.vcsRoots) {
                    if (isSuitableVcsRoot(root, false)) {
                        val vri = bt.getVcsRootInstanceForParent(root) ?: continue
                        val url = vri.properties[Constants.VCS_PROPERTY_GIT_URL] ?: continue
                        if (!oauthServersPattern.matcher(url).find()) {
                            LOG.debug("Found Git VCS root instance '$vri' but it's url ($url) not mentions any of oauth connected servers: $oauthServers")
                            continue
                        }
                        val info = getGitHubInfo(vri)
                        if (info == null) {
                            LOG.debug("Suitable GitHub-like VCS root instance '$vri' ignored: GitHubInfo is null, url is: $url")
                        } else if (!oauthServers.contains(info.server)) {
                            LOG.debug("Suitable GitHub-like VCS root instance '$vri' ignored: there's no oauth connection to '${info.server}'")
                        } else {
                            LOG.debug("Found Suitable GitHub-like VCS root instance '$vri' with oauth connection to '${info.server}'")
                            if (fast) {
                                LOG.info("In project '$project' found at least one VCS root instance with OAuth connection in fast mode in ~${System.currentTimeMillis() - start} ms")
                                return listOf(vri)
                            }
                            result.add(vri)
                        }
                    }
                }
            }
            LOG.info("In project '$project' found ${result.size} VCS root${result.size.s} with OAuth connection in ~${System.currentTimeMillis() - start} ms")
            LOG.debug("In project '$project' found ${result.size} VCS root${result.size.s} with OAuth connection: $result")
            return result.toList()
        }

        fun getOAuthServers(project: SProject, connectionsManager: OAuthConnectionsManager): Set<String> {
            return connectionsManager
                    .getAvailableConnections(project)
                    .filterNotNull()
                    .filter {
                        it.parameters[GitHubConstants.CLIENT_ID_PARAM] != null && it.parameters[GitHubConstants.CLIENT_SECRET_PARAM] != null
                    }
                    .mapNotNull {
                        when (it.oauthProvider.type) {
                            GHEOAuthProvider.TYPE -> {
                                it.parameters[GitHubConstants.GITHUB_URL_PARAM]?.let { getHost(it) }
                            }
                            GitHubOAuthProvider.TYPE -> {
                                "github.com"
                            }
                            else -> null
                        }
                    }
                    .toHashSet()
        }
    }
}