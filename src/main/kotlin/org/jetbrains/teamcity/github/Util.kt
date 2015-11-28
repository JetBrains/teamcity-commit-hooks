package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.github.GHEOAuthProvider
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.serverSide.oauth.github.GitHubOAuthProvider
import jetbrains.buildServer.vcs.VcsRoot

public class Util {
    companion object {
        public fun getGitHubInfo(root: VcsRoot): VcsRootGitHubInfo? {
            if (root.vcsName != "jetbrains.git") return null
            val url = root.properties["url"] ?: return null

            // Consider checking push_url also
            return getGitHubInfo(url)
        }

        public fun getGitHubInfo(url: String): VcsRootGitHubInfo? {
            return parseGitRepoUrl(url)
        }

        public val GITHUB_REPO_URL_PATTERN = "([^/:@]+)[/:]([a-zA-Z0-9\\.\\-_]+)/([a-zA-Z0-9\\.\\-_]+)(\\.git)?$".toPattern()

        public fun parseGitRepoUrl(url: String): VcsRootGitHubInfo? {
            val matcher = GITHUB_REPO_URL_PATTERN.matcher(url)
            if (!matcher.find()) return null
            val host = matcher.group(1) ?: return null
            val owner = matcher.group(2) ?: return null
            val name = matcher.group(3)?.removeSuffix(".git") ?: return null
            return VcsRootGitHubInfo(host, owner, name)
        }

        public fun findConnections(manager: OAuthConnectionsManager, info: VcsRootGitHubInfo, project: SProject): List<OAuthConnectionDescriptor> {
            return manager.getAvailableConnections(project)
                    .filter {
                        when (it.oauthProvider) {
                            is GHEOAuthProvider -> {
                                // Check server url
                                val url = it.parameters[GitHubConstants.GITHUB_URL_PARAM] ?: return@filter false
                                if (!isSameUrl(info.server, url)) {
                                    return@filter false
                                }
                            }
                            is GitHubOAuthProvider -> {
                                if (!isSameUrl(info.server, "github.com")) {
                                    return@filter false
                                }
                            }
                            else -> return@filter false
                        }
                        return@filter it.parameters[GitHubConstants.CLIENT_ID_PARAM] != null && it.parameters[GitHubConstants.CLIENT_SECRET_PARAM] != null
                    }
        }

        private fun isSameUrl(host: String, url: String): Boolean {
            // TODO: Improve somehow
            return url.contains(host, true)
        }
    }
}