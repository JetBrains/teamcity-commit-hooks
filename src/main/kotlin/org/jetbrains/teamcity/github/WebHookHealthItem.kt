package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.healthStatus.HealthStatusItem
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.vcs.VcsRootInstance

fun WebHookHealthItem(info: VcsRootGitHubInfo, root: SVcsRoot): HealthStatusItem {
    return HealthStatusItem("GH.WH.${root.id}.${info.getIdentifier()}", GitHubWebHookAvailableHealthReport.CATEGORY, mapOf(
            "GitHubInfo" to info,
            "VcsRoot" to root
    ))
}

fun WebHookHealthItem(info: VcsRootGitHubInfo, root: VcsRootInstance): HealthStatusItem {
    return WebHookHealthItem(info, root.parent)
}