package org.jetbrains.teamcity.github

import jetbrains.buildServer.dataStructures.MultiMapToSet
import jetbrains.buildServer.serverSide.healthStatus.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.vcs.SVcsRoot

class GitHubWebHookSuggestion(private val WebHooksManager: WebHooksManager,
                              private val OAuthConnectionsManager: OAuthConnectionsManager) : HealthStatusReport() {
    companion object {
        val TYPE = "GitHubWebHooksSuggestion"
        val CATEGORY: ItemCategory = SuggestionCategory(ItemSeverity.INFO, "Reduce GitHub repository overhead and speedup changes detection by switching to GitHub webhook", null)

        fun splitRoots(vcsRoots: Set<SVcsRoot>): MultiMapToSet<GitHubRepositoryInfo, SVcsRoot> {
            val map = MultiMapToSet<GitHubRepositoryInfo, SVcsRoot>()

            for (root in vcsRoots) {
                val info = Util.Companion.getGitHubInfo(root) ?: continue

                // Ignore roots with unresolved references in url
                if (info.isHasParameterReferences()) continue

                map.add(info, root)
            }
            return map
        }
    }

    override fun getType(): String = TYPE

    override fun getDisplayName(): String {
        return "Suggests installing of a GitHub webhook for GitHub repositories configured in TeamCity"
    }

    override fun getCategories(): MutableCollection<ItemCategory> {
        return arrayListOf(CATEGORY)
    }

    override fun canReportItemsFor(scope: HealthStatusScope): Boolean {
        if (!scope.isItemWithSeverityAccepted(CATEGORY.severity)) return false
        var found = false
        Util.findSuitableRoots(scope) { found = true; false }
        return found
    }


    override fun report(scope: HealthStatusScope, resultConsumer: HealthStatusItemConsumer) {
        val gitRoots = mutableSetOf<SVcsRoot>()
        Util.findSuitableRoots(scope, { gitRoots.add(it); true })

        val split = splitRoots(gitRoots)

        val filtered = split.entrySet()
                .filter {
                    WebHooksManager.storage.getHooks(it.key).isEmpty()
                }
                .filterKnownServers(OAuthConnectionsManager)

        val processed = mutableSetOf<String>();

        for ((info, roots) in filtered) {
            if (WebHooksManager.storage.getHooks(info).isNotEmpty()) {
                // Something changes since filtering on '.filter' above
                continue
            }
            for (root in roots) {
                if (!processed.add(info.id)) continue; // we already created health item for this repository

                val item = WebHookAddHookHealthItem(info, root)
                resultConsumer.consumeForVcsRoot(root, item)
                root.usagesInConfigurations.forEach { resultConsumer.consumeForBuildType(it, item) }
            }
        }
    }
}
