package org.jetbrains.teamcity.github.action

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.*
import org.jetbrains.teamcity.github.controllers.GitHubWebHookListener
import org.jetbrains.teamcity.github.controllers.Status
import org.jetbrains.teamcity.github.controllers.bad

object CreateWebHookAction {

    private val LOG = Logger.getInstance(CreateWebHookAction::class.java.name)

    @Throws(GitHubAccessException::class)
    fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext, connection: OAuthConnectionDescriptor): Pair<HookAddOperationResult, WebHooksStorage.HookInfo> {
        val repo = info.getRepositoryId()
        val service = RepositoryService(client)

        var result: Pair<HookAddOperationResult, WebHooksStorage.HookInfo>? = null

        for (hook in context.storage.getHooks(info)) {
            if (checkExisting(client, context, hook, info)) result = HookAddOperationResult.AlreadyExists to hook
        }
        if (result != null) return result

        // Reload all hooks from GitHub
        // It's ok throw GitHubAccessException upwards: if we cannot get hooks, we cannot add new one
        GetAllWebHooksAction.doRun(info, client, context)

        // Check for already existing hooks, otherwise Github we may create another hook for repository
        for (hook in context.storage.getHooks(info)) {
            if (checkExisting(client, context, hook, info)) result = HookAddOperationResult.AlreadyExists to hook
        }
        if (result != null) return result

        val authData = context.authDataStorage.create(user, info, connection, false)

        val callbackUrl = context.getCallbackUrl(authData)

        val hook = RepositoryHook().setActive(true).setName("web").setConfig(mapOf(
                "url" to callbackUrl,
                "content_type" to "json",
                "secret" to authData.secret
                // TODO: Investigate ssl option
        ))

        val created: RepositoryHook
        try {
            created = service.createHook(repo, hook)
            context.authDataStorage.store(authData)
        } catch(e: RequestException) {
            LOG.warnAndDebugDetails("Failed to create webhook for repository $info: ${e.status}", e)
            context.handleCommonErrors(e)
            when (e.status) {
                403, 404 -> {
                    // ? No access
                    val pair = TokensHelper.getHooksAccessType(client) ?: throw GitHubAccessException(GitHubAccessException.Type.NoAccess)// Weird. No header?
                    if (pair.first <= TokensHelper.HookAccessType.READ) throw GitHubAccessException(GitHubAccessException.Type.TokenScopeMismatch)
                    throw GitHubAccessException(GitHubAccessException.Type.UserHaveNoAccess)
                }
                422 -> {
                    if (e.error.errors.any { it.resource.equals("hook", true) && it.message.contains("already exists") }) {
                        // Already exists
                        // Very low chance to happen since auth data and callback url is randomly generated
                        // TODO: Remove
                        return HookAddOperationResult.AlreadyExists to context.storage.getHooks(info).first { !it.status.bad }
                    }
                }
            }
            throw e
        }


        if (callbackUrl != created.callbackUrl
            || authData.public != getPubKey(created.callbackUrl)) {
            // Weird
            // Either callback url is null or does not contains public key part of callback
            context.authDataStorage.remove(authData)
            throw IllegalStateException("GitHub returned incorrect hook")
        }

        val hookInfo = context.addHook(created, info.server, repo)
        if (hookInfo == null) {
            context.authDataStorage.remove(authData)
            throw IllegalStateException("GitHub returned incorrect hook")
        }

        // Remove missing hooks from storage as they don't exists remotely and we just created good one
        context.storage.delete(info) { it.status == Status.MISSING }

        return HookAddOperationResult.Created to hookInfo

    }

    private fun checkExisting(client: GitHubClientEx, context: ActionContext, hookInfo: WebHooksStorage.HookInfo, info: GitHubRepositoryInfo): Boolean {
        val pubKey = getPubKey(hookInfo.callbackUrl)
        val authData = pubKey?.let { context.authDataStorage.find(it) }
        if (authData == null) {
            // Unknown webhook or old callback url format, remove from GitHub and local cache
            // Possible cause: local cache was cleared, old callback url format.
            try {
                DeleteWebHookAction.doRun(info, client, context)
                context.storage.delete(info)
            } catch(ignored: GitHubAccessException) {
                context.storage.update(info) {
                    it.status = Status.INCORRECT
                }
            }
            return false
        } else {
            return !hookInfo.status.bad
        }
    }

    private fun getPubKey(url: String?): String? {
        return url?.let { GitHubWebHookListener.getPubKeyFromRequestPath(url) }
    }
}