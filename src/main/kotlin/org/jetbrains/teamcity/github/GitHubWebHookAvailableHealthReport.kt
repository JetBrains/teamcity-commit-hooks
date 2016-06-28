package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.healthStatus.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.util.MultiMap
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.vcs.VcsRootInstance
import org.jetbrains.teamcity.github.controllers.Status
import org.jetbrains.teamcity.github.controllers.getHookStatus
import java.util.*

class GitHubWebHookAvailableHealthReport(private val WebHooksManager: WebHooksManager,
                                         private val OAuthConnectionsManager: OAuthConnectionsManager) : HealthStatusReport() {
    companion object {
        val TYPE = "GitHub.WebHookAvailable"
        val CATEGORY: ItemCategory = ItemCategory("GH.WebHook.Available", "GitHub repo polling could be replaced with webhook", ItemSeverity.INFO)

        fun split(vcsRootInstances: Set<VcsRootInstance>): HashMap<GitHubRepositoryInfo, MultiMap<SVcsRoot?, VcsRootInstance>> {
            val map = HashMap<GitHubRepositoryInfo, MultiMap<SVcsRoot?, VcsRootInstance>>()

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
                    val hook = WebHooksManager.getHook(it)
                    hook == null || getHookStatus(hook).status == Status.OK
                }
                .filter { entry ->
                    // Filter by known servers
                    entry.key.server == "github.com" || getProjects(entry.value).any { project -> Util.findConnections(OAuthConnectionsManager, project, entry.key.server).isNotEmpty() }
                }

        for ((info, map) in filtered) {
            val hook = WebHooksManager.getHook(info)
            val status = getHookStatus(hook).status
            if (hook != null && status != Status.OK) {
                // Something changes since filtering on '.filterKeys' above
                continue
            }
            for ((root, instances) in map.entrySet()) {
                if (root == null) {
                    if (hook == null) {
                        // 'Add WebHook' part
                        for (rootInstance in instances) {
                            val item = WebHookAddHookHealthItem(info, rootInstance)
                            resultConsumer.consumeForVcsRoot(rootInstance.parent, item)
                            resultConsumer.consumeForProject(rootInstance.parent.project, item)
                            rootInstance.usages.keys.forEach { resultConsumer.consumeForBuildType(it, item) }
                        }
                    }
                    continue
                }
                if (hook == null) {
                    // 'Add WebHook' part
                    val item = WebHookAddHookHealthItem(info, root)
                    resultConsumer.consumeForVcsRoot(root, item)
                    resultConsumer.consumeForProject(root.project, item)
                    root.usages.keys.forEach { resultConsumer.consumeForBuildType(it, item) }
                }
            }
        }
    }
}
