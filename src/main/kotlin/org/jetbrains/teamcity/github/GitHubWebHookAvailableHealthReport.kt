package org.jetbrains.teamcity.github

import jetbrains.buildServer.dataStructures.MultiMapToSet
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.healthStatus.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.vcs.SVcsRoot
import org.jetbrains.teamcity.github.controllers.Status
import org.jetbrains.teamcity.github.controllers.getHookStatus
import java.util.*

class GitHubWebHookAvailableHealthReport(private val WebHooksManager: WebHooksManager,
                                         private val OAuthConnectionsManager: OAuthConnectionsManager) : HealthStatusReport() {
    companion object {
        val TYPE = "GitHub.WebHookAvailable"
        val CATEGORY: ItemCategory = ItemCategory("GH.WebHook.Available", "GitHub repo polling could be replaced with webhook", ItemSeverity.INFO)

        fun splitRoots(vcsRoots: Set<SVcsRoot>): MultiMapToSet<GitHubRepositoryInfo, SVcsRoot> {
            val map = MultiMapToSet<GitHubRepositoryInfo, SVcsRoot>();

            for (root in vcsRoots) {
                val info = Util.Companion.getGitHubInfo(root) ?: continue

                // Ignore roots with unresolved references in url
                if (info.isHasParameterReferences()) continue

                map.add(info, root);
            }
            return map
        }

        fun getProjects(roots: Collection<SVcsRoot>): Set<SProject> = roots.map { it.project }.toCollection(HashSet<SProject>())
    }

    override fun getType(): String = TYPE

    override fun getDisplayName(): String {
        return "Find VCS roots which can use GitHub push hook instead of polling"
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
        val gitRoots = HashSet<SVcsRoot>()
        Util.findSuitableRoots(scope, { gitRoots.add(it); true })

        val split = splitRoots(gitRoots)

        val filtered = split.entrySet()
                .filter {
                    val hook = WebHooksManager.getHook(it.key)
                    hook == null || getHookStatus(hook).status == Status.OK
                }
                .filter {
                    // Filter by known servers
                    it.key.server == "github.com" || getProjects(it.value).any { project -> Util.findConnections(OAuthConnectionsManager, project, it.key.server).isNotEmpty() }
                }

        for ((info, roots) in filtered) {
            val hook = WebHooksManager.getHook(info)
            val status = getHookStatus(hook).status
            if (hook != null && status != Status.OK) {
                // Something changes since filtering on '.filterKeys' above
                continue
            }
            for (root in roots) {
                if (hook == null) {
                    // 'Add WebHook' part
                    val item = WebHookAddHookHealthItem(info, root)
                    resultConsumer.consumeForVcsRoot(root, item)
                    root.usagesInConfigurations.forEach { resultConsumer.consumeForBuildType(it, item) }
                    root.usagesInProjects.plus(root.project).toSet().forEach { resultConsumer.consumeForProject(it, item) }
                }
            }
        }
    }
}
