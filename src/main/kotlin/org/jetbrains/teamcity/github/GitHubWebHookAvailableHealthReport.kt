package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.healthStatus.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.util.MultiMap
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.vcs.VcsRootInstance
import java.util.*

public class GitHubWebHookAvailableHealthReport(private val WebHooksManager: WebHooksManager,
                                                private val OAuthConnectionsManager: OAuthConnectionsManager) : HealthStatusReport() {
    companion object {
        public val TYPE = "GitHub.WebHookAvailable"
        public val CATEGORY: ItemCategory = ItemCategory("GH.WebHook", "GitHub repo polling could be replaced with webhook", ItemSeverity.INFO)
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


        val map = MultiMap<SVcsRoot, VcsRootInstance>()
        for (rootInstance in gitRootInstances) {
            val info = Util.getGitHubInfo(rootInstance) ?: continue

            // Ignore roots with unresolved references in url
            if (info.isHasParameterReferences()) continue

            // Filter by known servers
            if (info.server != "github.com") {
                val connections = Util.findConnections(OAuthConnectionsManager, rootInstance.parent.project, info.server)
                if (connections.isEmpty()) continue
            }

            val hook = WebHooksManager.getHook(info)
            if (hook != null) continue

            if (rootInstance.parent.properties[Constants.VCS_PROPERTY_GIT_URL] == rootInstance.properties[Constants.VCS_PROPERTY_GIT_URL]) {
                // Not parametrized url
                map.putValue(rootInstance.parent, rootInstance);
                continue
            }

            val item = WebHookHealthItem(info, rootInstance)
            resultConsumer.consumeForVcsRoot(rootInstance.parent, item)
            resultConsumer.consumeForProject(rootInstance.parent.project, item)
            rootInstance.usages.keys.forEach { resultConsumer.consumeForBuildType(it, item) }
        }

        for (entry in map.entrySet()) {
            val root = entry.key
            val info = Util.Companion.getGitHubInfo(root.properties["url"]!!) ?: continue
            val item = WebHookHealthItem(info, root)
            resultConsumer.consumeForVcsRoot(root, item)
            resultConsumer.consumeForProject(root.project, item)
            root.usages.keys.forEach { resultConsumer.consumeForBuildType(it, item) }
        }
    }

    private fun findSuitableRoots(scope: HealthStatusScope, collector: (VcsRootInstance) -> Boolean): Unit {
        for (bt in scope.buildTypes) {
            if (bt.project.isArchived) continue
            for (it in bt.vcsRootInstances) {
                if (it.vcsName == Constants.VCS_NAME_GIT && it.properties[Constants.VCS_PROPERTY_GIT_URL] != null) {
                    if (!collector(it)) return;
                }
            }
        }
    }
}