package org.jetbrains.teamcity.github.action

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import org.eclipse.egit.github.core.RepositoryId
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.GitHubAccessException
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.TokensHelper
import org.jetbrains.teamcity.github.WebHooksStorage
import org.jetbrains.teamcity.github.controllers.GitHubWebHookListener

object DeleteWebHookAction : Action<HookDeleteOperationResult, ActionContext> {
    private val LOG = Logger.getInstance(DeleteWebHookAction::class.java.name)

    @Throws(GitHubAccessException::class)
    override fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext): HookDeleteOperationResult {
        val repo = info.getRepositoryId()
        val service = RepositoryService(client)

        var hook = context.getHook(info)

        if (hook != null) {
            delete(client, hook, info, service, context)
            return HookDeleteOperationResult.Removed
        }

        // TODO: Consider handling GitHubAccessException
        GetAllWebHooksAction.doRun(info, client, context)

        hook = context.getHook(info)

        if (hook != null) {
            delete(client, hook, info, service, context)
            return HookDeleteOperationResult.Removed
        }

        return HookDeleteOperationResult.NeverExisted
    }

    private fun delete(client: GitHubClientEx, hook: WebHooksStorage.HookInfo, info: GitHubRepositoryInfo, service: RepositoryService, context: ActionContext) {
        try {
            service.deleteHook(info.getRepositoryId(), hook.id.toInt())
        } catch(e: RequestException) {
            LOG.warnAndDebugDetails("Failed to delete webhook for repository $info: ${e.status}", e)
            // TODO: There was not handel for 401. Investigate
            context.handleCommonErrors(e)
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
        context.storage.delete(info)
        GitHubWebHookListener.getPubKeyFromRequestPath(hook.callbackUrl)?.let { context.authDataStorage.delete(it) }
    }
}