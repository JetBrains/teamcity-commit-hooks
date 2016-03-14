package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.SProject
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
        public val CATEGORY: ItemCategory = ItemCategory("GH.WebHook.Available", "GitHub repo polling could be replaced with webhook", ItemSeverity.INFO)

        public fun split(vcsRootInstances: Set<VcsRootInstance>): HashMap<VcsRootGitHubInfo, MultiMap<SVcsRoot?, VcsRootInstance>> {
            val map = HashMap<VcsRootGitHubInfo, MultiMap<SVcsRoot?, VcsRootInstance>>()

            for (rootInstance in vcsRootInstances) {
                val info = Util.Companion.getGitHubInfo(rootInstance) ?: continue

                // Ignore roots with unresolved references in url
                if (info.isHasParameterReferences()) continue

                val value = map.getOrPut(info, { MultiMap() })
                if (rootInstance.parent.properties[Constants.VCS_PROPERTY_GIT_URL] == rootInstance.properties[Constants.VCS_PROPERTY_GIT_URL]) {
                    // Not parametrized url
                    value.putValue(rootInstance.parent, rootInstance);
                } else {
                    value.putValue(null, rootInstance)
                }
            }
            return map;
        }

        fun getProjects(map: MultiMap<SVcsRoot?, VcsRootInstance>): Set<SProject> {
            val result = HashSet<SProject>()
            map[null]?.forEach { result.add(it.parent.project) }
            map.keySet().filterNotNull().forEach { result.add(it.project) }
            return result
        }
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
        Util.findSuitableRoots(scope) { found = true; false }
        return found
    }


    override fun report(scope: HealthStatusScope, resultConsumer: HealthStatusItemConsumer) {
        val gitRootInstances = HashSet<VcsRootInstance>()
        Util.findSuitableRoots(scope, { gitRootInstances.add(it); true })

        val split = split(gitRootInstances)

        val filtered = split
                .filterKeys {
                    WebHooksManager.getHook(it) == null
                }
                .filter { entry ->
                    // Filter by known servers
                    entry.key.server == "github.com" || getProjects(entry.value).any { project -> Util.findConnections(OAuthConnectionsManager, project, entry.key.server).isNotEmpty() }
                }

        for ((info, map) in filtered) {
            for ((root, instances) in map.entrySet()) {
                if (root == null) {
                    for (rootInstance in instances) {
                        val item = WebHookHealthItem(info, rootInstance)
                        resultConsumer.consumeForVcsRoot(rootInstance.parent, item)
                        resultConsumer.consumeForProject(rootInstance.parent.project, item)
                        rootInstance.usages.keys.forEach { resultConsumer.consumeForBuildType(it, item) }
                    }
                    continue
                }
                val item = WebHookHealthItem(info, root)
                resultConsumer.consumeForVcsRoot(root, item)
                resultConsumer.consumeForProject(root.project, item)
                root.usages.keys.forEach { resultConsumer.consumeForBuildType(it, item) }
            }
        }
    }
}
