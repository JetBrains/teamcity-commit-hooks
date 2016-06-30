package org.jetbrains.teamcity.github

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

class Util {
    companion object {
        fun getGitHubInfo(root: VcsRoot): GitHubRepositoryInfo? {
            if (root.vcsName != Constants.VCS_NAME_GIT) return null
            val url = root.properties[Constants.VCS_PROPERTY_GIT_URL] ?: return null

            // Consider checking push_url also
            return getGitHubInfo(url)
        }

        fun getGitHubInfo(url: String): GitHubRepositoryInfo? {
            return parseGitRepoUrl(url)
        }

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
            when (connection.oauthProvider) {
                is GHEOAuthProvider -> {
                    // Check server url
                    val url = connection.parameters[GitHubConstants.GITHUB_URL_PARAM] ?: return false
                    if (!isSameUrl(server, url)) {
                        return false
                    }
                }
                is GitHubOAuthProvider -> {
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
        
        @Deprecated("#findSuitableRoots should be used to reduce load on server due to VcsInstances calculation")
        fun findSuitableInstances(project: SProject, recursive: Boolean = false, archived: Boolean = false, collector: (VcsRootInstance) -> Boolean) {
            val list = if (recursive) project.buildTypes else project.ownBuildTypes
            findSuitableInstances(list, archived, collector)
        }

        @Deprecated("#findSuitableRoots should be used to reduce load on server due to VcsInstances calculation")
        private fun findSuitableInstances(buildTypes: Collection<SBuildType>, archived: Boolean = false, collector: (VcsRootInstance) -> Boolean) {
            for (bt in buildTypes) {
                if (!archived && bt.project.isArchived) continue
                for (it in bt.vcsRootInstances) {
                    if (isSuitableVcsRoot(it, false)) {
                        if (!collector(it)) return
                    }
                }
            }
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
    }
}