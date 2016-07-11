package org.jetbrains.teamcity.github.action

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.GitHubAccessException
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.TokensHelper
import org.jetbrains.teamcity.github.WebHooksStorage
import org.jetbrains.teamcity.github.controllers.GitHubWebHookListener
import org.jetbrains.teamcity.github.controllers.Status

object DeleteWebHookAction : Action<HookDeleteOperationResult, ActionContext> {
    private val LOG = Logger.getInstance(DeleteWebHookAction::class.java.name)

    @Throws(GitHubAccessException::class)
    override fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext): HookDeleteOperationResult {
        // 0. Check whether there any hooks for repo in local cache. Return of none in cache.
        var hooks = context.storage.getHooks(info)
        if (hooks.isEmpty()) return HookDeleteOperationResult.NeverExisted

        // 1. Reload all hooks from GitHub Read
        // It's ok throw GitHubAccessException upwards
        GetAllWebHooksAction.doRun(info, client, context)

        // 2. Remove missing hooks from storage as they don't exists remotely
        context.storage.delete(info) { it.status == Status.MISSING }

        hooks = context.storage.getHooks(info)
        if (hooks.isEmpty()) return HookDeleteOperationResult.NeverExisted

        val service = RepositoryService(client)

        // 3. Run 'delete' action remotely
        // 4. If case of insufficient permissions - run 'disable' action remotely
        for (hook in hooks) {
            doDeleteOrDisable(client, context, hook, info, service)
        }
        return HookDeleteOperationResult.Removed
    }

    private fun doDeleteOrDisable(client: GitHubClientEx, context: ActionContext, hook: WebHooksStorage.HookInfo, info: GitHubRepositoryInfo, service: RepositoryService): HookDeleteOperationResult {
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

    private fun disable(client: GitHubClientEx, hook: WebHooksStorage.HookInfo, info: GitHubRepositoryInfo, service: RepositoryService, context: ActionContext) {
        try {
            val id = hook.id.toInt()
            var rh = service.getHook(info.getRepositoryId(), id)
            context.updateHooks(info.server, info.getRepositoryId(), listOf(rh))
            if (!rh.isActive) return
            rh.isActive = false
            rh = service.editHook(info.getRepositoryId(), rh)
            context.updateHooks(info.server, info.getRepositoryId(), listOf(rh))
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