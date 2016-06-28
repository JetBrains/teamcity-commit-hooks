package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.healthStatus.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.vcs.SVcsRoot
import java.util.*

class GitHubWebHookOutdatedHealthReport(private val WebHooksManager: WebHooksManager,
                                        private val OAuthConnectionsManager: OAuthConnectionsManager) : HealthStatusReport() {
    companion object {
        val TYPE = "GitHub.WebHookOutdated"
        val CATEGORY: ItemCategory = ItemCategory("GH.WebHook.Outdated", "GitHub repo webhook is misconfigured or outdated", ItemSeverity.INFO)
    }

    override fun getType(): String = TYPE

    override fun getDisplayName(): String {
        return "GitHub misconfigured/outdated webhooks"
    }

    override fun getCategories(): MutableCollection<ItemCategory> {
        return arrayListOf(CATEGORY)
    }

    override fun canReportItemsFor(scope: HealthStatusScope): Boolean {
        if (!scope.isItemWithSeverityAccepted(CATEGORY.severity)) return false
        var found = false
        Util.findSuitableRoots(scope) { found = true; false }
        return found && WebHooksManager.isHasIncorrectHooks()
    }

    override fun report(scope: HealthStatusScope, resultConsumer: HealthStatusItemConsumer) {
        val gitRoots = HashSet<SVcsRoot>()
        Util.findSuitableRoots(scope, { gitRoots.add(it); true })

        val split = GitHubWebHookAvailableHealthReport.splitRoots(gitRoots)

        val filtered = split.entrySet()
                .filter { entry ->
                    // Filter by known servers
                    entry.key.server == "github.com" || GitHubWebHookAvailableHealthReport.getProjects(entry.value).any { project -> Util.findConnections(OAuthConnectionsManager, project, entry.key.server).isNotEmpty() }
                }.map { it.key to it.value }.toMap()

        val infos = HashSet<GitHubRepositoryInfo>(filtered.keys)

        val hooks = WebHooksManager.getIncorrectHooks().filter { infos.contains(it.first) }

        for (hook in hooks) {
            val info = hook.first
            val roots = filtered[info] ?: continue

            val id = info.server + "#" + hook.second.id
            val item = HealthStatusItem("GH.WH.O.$id", CATEGORY, mapOf(
                    "GitHubInfo" to info,
                    "HookInfo" to hook.second,
                    "Projects" to GitHubWebHookAvailableHealthReport.getProjects(roots),
                    "Usages" to roots
            ))

            for (it in roots) {
                resultConsumer.consumeForVcsRoot(it, item)
                it.usagesInConfigurations.forEach { resultConsumer.consumeForBuildType(it, item) }
                it.usagesInProjects.plus(it.project).toSet().forEach { resultConsumer.consumeForProject(it, item) }
            }
        }
    }
}