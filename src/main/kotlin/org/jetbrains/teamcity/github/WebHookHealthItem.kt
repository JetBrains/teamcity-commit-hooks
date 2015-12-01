package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.healthStatus.HealthStatusItem
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.vcs.VcsRootInstance

// TODO: Either use fields or data map
class WebHookHealthItem private constructor(val type: String, val id: Long, val info: VcsRootGitHubInfo, val root: SVcsRoot, val instance: VcsRootInstance?) : HealthStatusItem("GH.WH.$type.$id", GitHubWebHookAvailableHealthReport.CATEGORY, mapOf(
        "Type" to type,
        "Id" to id,
        "GitHubInfo" to info,
        "VcsRoot" to root,
        "VcsRootInstance" to instance
)) {

    public constructor(info: VcsRootGitHubInfo, instance: VcsRootInstance) : this("Instance", instance.id, info, instance.parent, instance) {
    }

    public constructor(info: VcsRootGitHubInfo, root: SVcsRoot) : this("Root", root.id, info, root, null) {
    }
}