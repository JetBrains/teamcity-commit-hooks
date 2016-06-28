package org.jetbrains.teamcity.github.action

import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import org.jetbrains.teamcity.github.GitHubAccessException
import org.jetbrains.teamcity.github.GitHubRepositoryInfo

interface Action<ORT : Enum<ORT>, Context: ActionContext> {
    @Throws(GitHubAccessException::class) fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: Context): ORT
}