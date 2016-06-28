package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.healthStatus.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.vcs.VcsRootInstance
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
        val gitRootInstances = HashSet<VcsRootInstance>()
        Util.findSuitableRoots(scope, { gitRootInstances.add(it); true })

        val split = GitHubWebHookAvailableHealthReport.split(gitRootInstances)
                .filter { entry ->
                    // Filter by known servers
                    entry.key.server == "github.com" || GitHubWebHookAvailableHealthReport.getProjects(entry.value).any { project -> Util.findConnections(OAuthConnectionsManager, project, entry.key.server).isNotEmpty() }
                }
        val infos = HashSet<GitHubRepositoryInfo>(split.keys)

        val hooks = WebHooksManager.getIncorrectHooks().filter { infos.contains(it.first) }

        for (hook in hooks) {
            val info = hook.first
            val map = split[info] ?: continue

            val id = info.server + "#" + hook.second.id
            val item = HealthStatusItem("GH.WH.O.$id", CATEGORY, mapOf(
                    "GitHubInfo" to info,
                    "HookInfo" to hook.second,
                    "Projects" to GitHubWebHookAvailableHealthReport.getProjects(map),
                    "UsageMap" to map
            ))

            val roots = HashSet<SVcsRoot>(map.keySet())
            map.get(null)?.let { roots.addAll(it.map { it.parent }) }

            roots.forEach { resultConsumer.consumeForVcsRoot(it, item) }
            roots.flatMap { it.usages.keys }.toSet().forEach { resultConsumer.consumeForBuildType(it, item) }
            roots.map { it.project }.toSet().forEach { resultConsumer.consumeForProject(it, item) }
        }
    }
}