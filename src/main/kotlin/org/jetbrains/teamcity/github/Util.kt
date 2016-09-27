package org.jetbrains.teamcity.github

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
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
import java.net.URL
import java.util.*

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
            val uri = URI(url)
            val u: URL
            try {
                if (uri.scheme == null) {
                    u = URL("http://$url")
                } else u = uri.toURL()
            } catch(e: MalformedURLException) {
                return host == url
            }
            if (u.host == host) return true
            val u2: URL
            try {
                u2 = URL(host)
                return u.host == u2.host
            } catch(e: Exception) {
                return false
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
        fun getVcsRootsWhereHookCanBeInstalled(project: SProject, connectionsManager: OAuthConnectionsManager): List<VcsRoot> {
            return doGetVcsRootsWhereHookCanBeInstalled(project, connectionsManager, false)
        }

        /**
         * Returns project suitable Git SVcsRoots or VcsRootInstances if there are OAuth connections corresponding to these VCS roots
         */
        fun isVcsRootsWhereHookCanBeInstalled(project: SProject, connectionsManager: OAuthConnectionsManager): Boolean {
            return doGetVcsRootsWhereHookCanBeInstalled(project, connectionsManager, true).isNotEmpty()
        }

        private fun doGetVcsRootsWhereHookCanBeInstalled(project: SProject, connectionsManager: OAuthConnectionsManager, fast: Boolean = true): List<VcsRoot> {
            val roots = LinkedHashSet<SVcsRoot>()
            val parametrizedVcsRoots = LinkedHashSet<SVcsRoot>()

            val serverHasConnectionsMap = CacheBuilder.newBuilder().build<String, Boolean>(
                    CacheLoader.from({ server -> server != null && Util.findConnections(connectionsManager, project, server).isNotEmpty() }))

            Util.findSuitableRoots(project, recursive = true) { root ->
                val info = getGitHubInfo(root)
                if (info != null) {
                    if (serverHasConnectionsMap[info.server]) {
                        LOG.debug("Found Suitable GitHub-like VCS root '$root' with oauth connection to '${info.server}'")
                        roots.add(root)
                        if (fast) return@findSuitableRoots false
                    } else {
                        LOG.debug("Found Suitable GitHub-like VCS root '$root' but there's no oauth connection to '${info.server}'")
                    }
                } else if (StringUtil.hasParameterReferences(root.properties[Constants.VCS_PROPERTY_GIT_URL])) {
                    LOG.debug("Parametrized Suitable GitHub-like VCS root '$root'")
                    parametrizedVcsRoots.add(root)
                } else {
                    LOG.debug("Suitable GitHub-like VCS root '$root' ignored: GitHubInfo is null, url has no parameter references")
                }
                true
            }

            if (roots.isNotEmpty() && fast) {
                val result = roots.toList()
                LOG.info("In project '$project' found ${result.size} VCS root${result.size.s} with OAuth connection in fast mode")
                LOG.debug("In project '$project' found ${result.size} VCS root${result.size.s} with OAuth connection in fast mode: $result")
                return result
            }

            val instances = LinkedHashSet<VcsRootInstance>()
            for (root in parametrizedVcsRoots) {
                val rootInstances = root.usagesInConfigurations
                        .filter { bt -> bt.belongsTo(project) }
                        .mapNotNull { bt -> bt.getVcsRootInstanceForParent(root) }
                LOG.debug("Found ${rootInstances.size} VCS root instance${rootInstances.size.s} for VCS root '$root' in project '$project': $rootInstances")
                for (vri in rootInstances) {
                    val info = getGitHubInfo(vri)
                    if (info != null) {
                        if (serverHasConnectionsMap[info.server]) {
                            LOG.debug("Found Suitable GitHub-like VCS root instance '$vri' with oauth connection to '${info.server}'")
                            instances.add(vri)
                        } else {
                            LOG.debug("Found Suitable GitHub-like VCS root instance '$vri' but there's no oauth connection to '${info.server}'")
                        }
                    } else if (StringUtil.hasParameterReferences(vri.properties[Constants.VCS_PROPERTY_GIT_URL])) {
                        LOG.debug("Suitable GitHub-like VCS root instance still has unresolved parameters '$vri'")
                    } else {
                        LOG.debug("Suitable GitHub-like VCS root instance '$vri' ignored: GitHubInfo is null, url has no parameter references")
                    }
                }
            }

            val result = roots.toList().plus(instances)
            LOG.info("In project '$project' found ${result.size} VCS root${result.size.s} with OAuth connection")
            LOG.debug("In project '$project' found ${result.size} VCS root${result.size.s} with OAuth connection: $result")
            return result
        }
    }
}