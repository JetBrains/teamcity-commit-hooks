package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.healthStatus.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.vcs.VcsRootInstance
import org.jetbrains.teamcity.github.GitHubWebHookAvailableHealthReport.Companion.getProjects
import org.jetbrains.teamcity.github.GitHubWebHookAvailableHealthReport.Companion.split
import org.jetbrains.teamcity.github.controllers.Status
import org.jetbrains.teamcity.github.controllers.getHookStatus
import java.util.*

public class VCSRootCheckIntervalHR(private val WebHooksManager: WebHooksManager,
                                    private val OAuthConnectionsManager: OAuthConnectionsManager) : HealthStatusReport() {
    companion object {
        public val TYPE = "GitHub.VCSCheckingIntervalReplace"
        public val CATEGORY: ItemCategory = ItemCategory("GitHub.VCSCheckingIntervalReplace", "VCS Repo checking for changes interval could be reduced (webhook is configured)", ItemSeverity.INFO)
    }

    override fun getType(): String = TYPE

    override fun getDisplayName(): String {
        return "Find VCS roots which have configured webhooks"
    }

    override fun getCategories(): MutableCollection<ItemCategory> {
        return arrayListOf(CATEGORY);
    }

    override fun canReportItemsFor(scope: HealthStatusScope): Boolean {
        if (!scope.isItemWithSeverityAccepted(CATEGORY.severity)) return false
        var found = false
        Util.findSuitableRoots(scope) { found = true; false }
        return found
    }


    override fun report(scope: HealthStatusScope, resultConsumer: HealthStatusItemConsumer) {
        val gitRootInstances = HashSet<VcsRootInstance>()
        Util.findSuitableRoots(scope, { gitRootInstances.add(it); true })

        val split = split(gitRootInstances)

        val filtered = split
                .filterKeys {
                    getHookStatus(WebHooksManager.getHook(it)).status == Status.OK
                }
                .filter { entry ->
                    // Filter by known servers
                    entry.key.server == "github.com" || getProjects(entry.value).any { project -> Util.findConnections(OAuthConnectionsManager, project, entry.key.server).isNotEmpty() }
                }

        for ((info, map) in filtered) {
            for ((root, instances) in map.entrySet()) {
                if (root == null) {
                    // For not Checking For Changes interval could be set only for SVcsRoot
                    // So we will set it for vcs roots which is parametrized but all VcsRootInstances has correct hooks
                    // TODO: Implement
                    continue
                }
                val hook = WebHooksManager.getHook(info) ?: continue
                if (!root.isUseDefaultModificationCheckInterval && root.modificationCheckInterval >= 3600) continue;
                val item = VCSRootCheckIntervalHealthItem(info, root, hook)
                resultConsumer.consumeForVcsRoot(root, item)
                resultConsumer.consumeForProject(root.project, item)
                root.usages.keys.forEach { resultConsumer.consumeForBuildType(it, item) }
            }
        }
    }
}
