package org.jetbrains.teamcity.github

import jetbrains.buildServer.dataStructures.MultiMapToSet
import jetbrains.buildServer.serverSide.healthStatus.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.vcs.VcsRoot
import jetbrains.buildServer.vcs.VcsRootInstance

open class GitHubWebHookSuggestion(private val WebHooksManager: WebHooksManager,
                                   private val OAuthConnectionsManager: OAuthConnectionsManager) : HealthStatusReport() {
    companion object {
        val TYPE = "GitHubWebHooksSuggestion"
        val CATEGORY: ItemCategory = SuggestionCategory(ItemSeverity.INFO, "Reduce GitHub repository overhead and speedup changes detection by switching to GitHub webhook", null)

        fun <T : VcsRoot> splitRoots(vcsRoots: Collection<T>): MultiMapToSet<GitHubRepositoryInfo, T> {
            val map = MultiMapToSet<GitHubRepositoryInfo, T>()

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
        val vcsRoots = Util.getVcsRootsWhereHookCanBeInstalled(scope.buildTypes, OAuthConnectionsManager)

        val split: MultiMapToSet<GitHubRepositoryInfo, VcsRootInstance> = splitRoots<VcsRootInstance>(vcsRoots)

        val processed = mutableSetOf<String>();

        for ((info, instances) in split.entrySet()) {
            if (hasHooksInStorage(info)) continue

            for (instance in instances) {
                if (!processed.add(info.id)) continue; // we already created health item for this repository

                val item = WebHookAddHookHealthItem(info, instance.parent)
                resultConsumer.consumeForVcsRoot(instance.parent, item)
                instance.usages.keys.forEach { resultConsumer.consumeForBuildType(it, item) }
            }
        }
    }

    protected open fun hasHooksInStorage(info: GitHubRepositoryInfo) = WebHooksManager.storage.getHooks(info).isNotEmpty()
}
