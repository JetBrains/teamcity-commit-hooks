package org.jetbrains.teamcity.github.action

import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.GitHubAccessException
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.TokensHelper

object TestWebHookAction : Action<HookTestOperationResult, ActionContext> {
    @Throws(GitHubAccessException::class)
    override fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext): HookTestOperationResult {
        val service = RepositoryService(client)
        val hook = context.storage.getHook(info) ?: return HookTestOperationResult.NotFound
        try {
            service.testHook(info.getRepositoryId(), hook.id.toInt())
        } catch(e: RequestException) {
            when (e.status) {
                401 -> throw GitHubAccessException(GitHubAccessException.Type.InvalidCredentials)
                403, 404 -> {
                    // ? No access
                    val pair = TokensHelper.getHooksAccessType(client) ?: throw GitHubAccessException(GitHubAccessException.Type.NoAccess)// Weird. No header?
                    if (pair.first <= TokensHelper.HookAccessType.READ) throw GitHubAccessException(GitHubAccessException.Type.TokenScopeMismatch)
                    throw GitHubAccessException(GitHubAccessException.Type.UserHaveNoAccess)
                }
            }
            throw e
        }
        return HookTestOperationResult.Ok
    }
}