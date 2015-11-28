package org.jetbrains.teamcity.github

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
    }
}