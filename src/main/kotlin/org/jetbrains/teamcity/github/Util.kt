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
import jetbrains.buildServer.vcs.VcsRoot
import jetbrains.buildServer.vcs.VcsRootInstance

public class Util {
    companion object {
        public fun getGitHubInfo(root: VcsRoot): VcsRootGitHubInfo? {
            if (root.vcsName != Constants.VCS_NAME_GIT) return null
            val url = root.properties[Constants.VCS_PROPERTY_GIT_URL] ?: return null

            // Consider checking push_url also
            return getGitHubInfo(url)
        }

        public fun getGitHubInfo(url: String): VcsRootGitHubInfo? {
            return parseGitRepoUrl(url)
        }

        public val GITHUB_REPO_URL_PATTERN = "([^/:@]+)[/:]([a-zA-Z0-9\\.\\-_]+)/([a-zA-Z0-9\\.\\-_]+)$".toPattern()

        public fun parseGitRepoUrl(url: String): VcsRootGitHubInfo? {
            val matcher = GITHUB_REPO_URL_PATTERN.matcher(url)
            if (!matcher.find()) return null
            val host = matcher.group(1) ?: return null
            val owner = matcher.group(2) ?: return null
            val name = matcher.group(3)?.removeSuffix(".git") ?: return null
            return VcsRootGitHubInfo(host, owner, name)
        }

        public fun findConnections(manager: OAuthConnectionsManager, project: SProject, server: String): List<OAuthConnectionDescriptor> {
            return manager.getAvailableConnections(project)
                    .filter {
                        it != null && isConnectionToServer(it, server)
                    }
        }

        public fun isConnectionToServer(connection: OAuthConnectionDescriptor, server: String): Boolean {
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

        private fun isSameUrl(host: String, url: String): Boolean {
            // TODO: Improve somehow
            return url.contains(host, true)
        }

        fun isSuitableVcsRoot(root: VcsRoot): Boolean {
            if (root.vcsName != Constants.VCS_NAME_GIT) return false
            val url = root.properties[Constants.VCS_PROPERTY_GIT_URL] ?: return false
            if (StringUtil.hasParameterReferences(url)) return false
            return getGitHubInfo(url) != null
        }

        fun findSuitableRoots(scope: HealthStatusScope, collector: (VcsRootInstance) -> Boolean) {
            findSuitableRoots(scope.buildTypes, false, collector);
        }

        fun findSuitableRoots(project: SProject, recursive: Boolean = false, archived: Boolean = false, collector: (VcsRootInstance) -> Boolean) {
            val list = if (recursive) project.buildTypes else project.ownBuildTypes
            findSuitableRoots(list, archived, collector)
        }

        fun findSuitableRoots(buildTypes: Collection<SBuildType>, archived: Boolean = false, collector: (VcsRootInstance) -> Boolean) {
            for (bt in buildTypes) {
                if (!archived && bt.project.isArchived) continue
                for (it in bt.vcsRootInstances) {
                    if (it.vcsName == Constants.VCS_NAME_GIT && it.properties[Constants.VCS_PROPERTY_GIT_URL] != null) {
                        if (!collector(it)) return;
                    }
                }
            }
        }
    }
}