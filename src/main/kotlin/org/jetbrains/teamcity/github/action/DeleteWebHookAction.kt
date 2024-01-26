

package org.jetbrains.teamcity.github.action

import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.eclipse.egit.github.core.service.RepositoryServiceEx
import org.jetbrains.teamcity.github.*
import org.jetbrains.teamcity.github.controllers.GitHubWebHookListener
import org.jetbrains.teamcity.github.controllers.Status

object DeleteWebHookAction {
    private val LOG = Util.getLogger(DeleteWebHookAction::class.java)

    @Throws(GitHubAccessException::class)
    fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, context: ActionContext): HookDeleteOperationResult {
        // 0. Check whether there any hooks for repo in local cache. Return of none in cache.
        var hooks = context.storage.getHooks(info)
        if (hooks.isEmpty()) return HookDeleteOperationResult.NeverExisted

        // 1. Reload all hooks from GitHub
        // It's ok throw GitHubAccessException upwards
        GetAllWebHooksAction.doRun(info, client, context)

        // 2. Remove missing hooks from storage as they don't exists remotely
        context.storage.delete(info) { it.status == Status.MISSING }

        hooks = context.storage.getHooks(info)
        if (hooks.isEmpty()) return HookDeleteOperationResult.NeverExisted

        val service = RepositoryServiceEx(client)

        // 3. Run 'delete' action remotely
        // 4. If case of insufficient permissions - run 'disable' action remotely
        for (hook in hooks) {
            doDeleteOrDisable(client, context, hook, info, service)
        }
        return HookDeleteOperationResult.Removed
    }

    private fun doDeleteOrDisable(client: GitHubClientEx, context: ActionContext, hook: WebHookInfo, info: GitHubRepositoryInfo, service: RepositoryServiceEx): HookDeleteOperationResult {
        try {
            delete(client, hook, info, service, context)
        } catch(e: GitHubAccessException) {
            if (e.type == GitHubAccessException.Type.TokenScopeMismatch) {
                if (hook.status != Status.DISABLED) {
                    disable(client, hook, info, service, context)
                }
            } else throw e
        }
        return HookDeleteOperationResult.Removed
    }

    private fun delete(client: GitHubClientEx, hook: WebHookInfo, info: GitHubRepositoryInfo, service: RepositoryService, context: ActionContext) {
        try {
            service.deleteHook(info.getRepositoryId(), hook.id.toInt())
        } catch(e: RequestException) {
            LOG.warnAndDebugDetails("Failed to delete webhook for repository ${info.id}: ${e.status}", e)
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
        context.storage.delete(hook)
        GitHubWebHookListener.getPubKeyFromRequestPath(hook.callbackUrl)?.let { context.authDataStorage.delete(it) }
    }

    private fun disable(client: GitHubClientEx, hook: WebHookInfo, info: GitHubRepositoryInfo, service: RepositoryServiceEx, context: ActionContext) {
        try {
            val rh = service.disableHook(info.getRepositoryId(), hook.id)
            context.updateOneHook(info.server, info.getRepositoryId(), rh)
        } catch(e: RequestException) {
            LOG.warnAndDebugDetails("Failed to delete webhook for repository ${info.id}: ${e.status}", e)
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
    }
}