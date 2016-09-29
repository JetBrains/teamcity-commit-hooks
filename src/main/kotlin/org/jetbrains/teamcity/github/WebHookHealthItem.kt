package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.healthStatus.HealthStatusItem

fun WebHookAddHookHealthItem(info: GitHubRepositoryInfo, project: SProject): HealthStatusItem {
    return HealthStatusItem("GitHubWebHook.${info.id}", GitHubWebHookSuggestion.CATEGORY, mapOf(
            "GitHubInfo" to info,
            "Project" to project
    ))
}