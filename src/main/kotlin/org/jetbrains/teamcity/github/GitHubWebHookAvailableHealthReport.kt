package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.healthStatus.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.vcs.VcsRootInstance
import java.util.*

public class GitHubWebHookAvailableHealthReport(private val WebHooksManager: WebHooksManager,
                                                private val OAuthConnectionsManager: OAuthConnectionsManager) : HealthStatusReport() {
    companion object {
        public val TYPE = "GitHub.WebHookAvailable"
        private val CATEGORY: ItemCategory = ItemCategory("GH.WebHook", "GitHub repo polling could be replaced with webhook", ItemSeverity.INFO)
    }

    override fun getType(): String = TYPE

    override fun getDisplayName(): String {
        return "Find VCS roots which can use GitHub push hook instead of polling"
    }

    override fun getCategories(): MutableCollection<ItemCategory> {
        return arrayListOf(CATEGORY);
    }

    override fun canReportItemsFor(scope: HealthStatusScope): Boolean {
        if (!scope.isItemWithSeverityAccepted(CATEGORY.severity)) return false
        var found = false
        findSuitableRoots(scope) { found = true; false }
        return found
    }


    override fun report(scope: HealthStatusScope, resultConsumer: HealthStatusItemConsumer) {
        val gitRootInstances = HashSet<VcsRootInstance>()
        findSuitableRoots(scope, { gitRootInstances.add(it); true })


        val map = jetbrains.buildServer.util.MultiMap<SVcsRoot, VcsRootInstance>()
        for (rootInstance in gitRootInstances) {
            val info = Util.getGitHubInfo(rootInstance) ?: continue

            // Filter by known servers
            if (info.server != "github.com") {
                val connections = Util.findConnections(OAuthConnectionsManager, info, rootInstance.parent.project)
                if (connections.isEmpty()) continue
            }

            val hook = WebHooksManager.findHook(info)
            if (hook != null) continue

            if (rootInstance.parent.properties["url"] == rootInstance.properties["url"]) {
                // Not parametrized url
                map.putValue(rootInstance.parent, rootInstance);
                continue
            }

            val item = HealthStatusItem("GH.WH.I.${rootInstance.id}", CATEGORY, mapOf(
                    "Type" to "Instance",
                    "Id" to rootInstance.id,
                    "GitHubInfo" to info,

                    "VcsRoot" to rootInstance.parent,
                    "VcsRootInstance" to rootInstance
            ))
            resultConsumer.consumeForVcsRoot(rootInstance.parent, item)
            resultConsumer.consumeForProject(rootInstance.parent.project, item)
        }

        for (entry in map.entrySet()) {
            val root = entry.key
            val info = Util.Companion.getGitHubInfo(root.properties["url"]!!) ?: continue
            val item = HealthStatusItem("GH.WH.R.${root.id}", CATEGORY, mapOf(
                    "Type" to "Root",
                    "Id" to root.id,
                    "GitHubInfo" to info,

                    "VcsRoot" to root
            ))
            resultConsumer.consumeForVcsRoot(root, item)
            resultConsumer.consumeForProject(root.project, item)
        }
    }

    private fun findSuitableRoots(scope: HealthStatusScope, collector: (VcsRootInstance) -> Boolean): Unit {
        for (bt in scope.buildTypes) {
            if (bt.project.isArchived) continue
            for (it in bt.vcsRootInstances) {
                if (it.vcsName == "jetbrains.git" && it.properties["url"] != null) {
                    if (!collector(it)) return;
                }
            }
        }
    }
}