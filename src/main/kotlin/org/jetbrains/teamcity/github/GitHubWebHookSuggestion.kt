package org.jetbrains.teamcity.github

import jetbrains.buildServer.dataStructures.MultiMapToSet
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.healthStatus.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.vcs.VcsRoot
import jetbrains.buildServer.vcs.VcsRootInstance
import java.util.*

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

        fun report(buildTypes: Collection<SBuildType>, resultConsumer: HealthStatusItemConsumer, oauthConnectionsManager: OAuthConnectionsManager, hasHooksInStorage: (GitHubRepositoryInfo) -> Boolean) {
            val pairs = Util.getVcsRootsWhereHookCanBeInstalled(buildTypes, oauthConnectionsManager)

            val groupByGitHubInfo: Map<GitHubRepositoryInfo?, List<Pair<SBuildType, VcsRootInstance>>> = pairs.groupBy { Util.getGitHubInfo(it.second) }

            val processed = HashSet<GitHubRepositoryInfo>()

            for ((info, pairs) in groupByGitHubInfo) {
                if (info == null) continue
                // Ignore roots with unresolved references in url
                if (info.isHasParameterReferences()) continue
                if (hasHooksInStorage(info)) continue

                val groupByProject: Map<SProject, List<Pair<SBuildType, VcsRootInstance>>> = pairs.groupBy { it.first.project }

                for ((project, pairs) in groupByProject) {
                    val item = WebHookAddHookHealthItem(info, project)

                    // we already created health item for this repository
                    if (!processed.add(info)) continue

                    val instances: List<VcsRootInstance> = pairs.map { it.second }

                    // Project
                    resultConsumer.consumeForProject(project, item)

                    // BuildTypes
                    pairs.map { it.first }.forEach { resultConsumer.consumeForBuildType(it, item) }

                    // VcsRoots
                    instances
                            .map { it.parent }
                            .filter { it.project.belongsTo(project) }
                            .forEach { resultConsumer.consumeForVcsRoot(it, item) }
                }
            }
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
        Companion.report(scope.buildTypes, resultConsumer, OAuthConnectionsManager) { WebHooksManager.storage.getHooks(it).isNotEmpty() }
    }
}
