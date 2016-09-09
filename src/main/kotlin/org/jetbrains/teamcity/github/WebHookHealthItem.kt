package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.healthStatus.HealthStatusItem
import jetbrains.buildServer.vcs.SVcsRoot

fun WebHookAddHookHealthItem(info: GitHubRepositoryInfo, root: SVcsRoot): HealthStatusItem {
    return HealthStatusItem("GitHubWebHook.${info.id}", GitHubWebHookSuggestion.CATEGORY, mapOf(
            "GitHubInfo" to info,
            "VcsRoot" to root
    ))
}