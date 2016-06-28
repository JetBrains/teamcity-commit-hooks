package org.jetbrains.teamcity.github.action

import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import org.eclipse.egit.github.core.RepositoryId
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.GitHubAccessException
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.TokensHelper
import org.jetbrains.teamcity.github.WebHooksStorage

object DeleteWebHookAction : Action<HookDeleteOperationResult, ActionContext> {
    @Throws(GitHubAccessException::class)
    override fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext): HookDeleteOperationResult {
        val repo = info.getRepositoryId()
        val service = RepositoryService(client)

        var hook = context.getHook(info)

        if (hook != null) {
            delete(client, hook, info, repo, service, context)
            return HookDeleteOperationResult.Removed
        }

        // TODO: Consider handling GitHubAccessException
        GetAllWebHooksAction.doRun(info, client, context)

        hook = context.getHook(info)

        if (hook != null) {
            delete(client, hook, info, repo, service, context)
            return HookDeleteOperationResult.Removed
        }

        return HookDeleteOperationResult.NeverExisted
    }

    private fun delete(client: GitHubClientEx, hook: WebHooksStorage.HookInfo, info: GitHubRepositoryInfo, repo: RepositoryId, service: RepositoryService, context: ActionContext) {
        try {
            service.deleteHook(repo, hook.id.toInt())
        } catch(e: RequestException) {
            when (e.status) {
                403, 404 -> {
                    // ? No access
                    // "X-Accepted-OAuth-Scopes" -> "admin:repo_hook, public_repo, repo"
                    val pair = TokensHelper.getHooksAccessType(client) ?: throw GitHubAccessException(GitHubAccessException.Type.NoAccess)// Weird. No header?
                    if (pair.first < TokensHelper.HookAccessType.ADMIN) throw GitHubAccessException(GitHubAccessException.Type.TokenScopeMismatch, "Required scope 'admin:repo_hook', 'public_repo' or 'repo'")
                    throw GitHubAccessException(GitHubAccessException.Type.UserHaveNoAccess)
                }
            }
            throw e
        }
        context.storage.delete(info.server, repo)
    }
}