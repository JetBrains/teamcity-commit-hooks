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

object CreateWebHookAction {

    private val LOG = Logger.getInstance(CreateWebHookAction::class.java.name)

    @Throws(GitHubAccessException::class)
    fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext, connection: OAuthConnectionDescriptor): HookAddOperationResult {
        val repo = info.getRepositoryId()
        val service = RepositoryService(client)

        var hookInfo = context.getHook(info)
        if (hookInfo != null) {
            // TODO: Check AuthData
            if (checkExisting(client, context, hookInfo, info, user)) return HookAddOperationResult.AlreadyExists
        }

        // First, check for already existing hooks, otherwise Github will answer with code 422
        // If we cannot get hooks, we cannot add new one
        // TODO: Consider handling GitHubAccessException
        GetAllWebHooksAction.doRun(info, client, context)

        hookInfo = context.getHook(info)
        if (hookInfo != null) {
            // TODO: Check AuthData
            if (checkExisting(client, context, hookInfo, info, user)) return HookAddOperationResult.AlreadyExists
        }

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
                        // TODO: Handle AuthData
                        // TODO: Remove existing hook if there no auth data know here.
                        return HookAddOperationResult.AlreadyExists
                    }
                }
            }
            throw e
        }


        if (callbackUrl != created.callbackUrl
            || authData.public != getPubKey(created.callbackUrl)) {
            // Weird
            // Either callback url is null or does not contains public key part of callback
            throw IllegalStateException("GitHub returned incorrect hook")
        }

        hookInfo = context.addHook(created, info.server, repo)
        if (hookInfo == null) {
            throw IllegalStateException("GitHub returned incorrect hook")
        }

        context.authDataStorage.store(authData)
        return HookAddOperationResult.Created

    }

    private fun checkExisting(client: GitHubClientEx, context: ActionContext, hookInfo: WebHooksStorage.HookInfo, info: GitHubRepositoryInfo, user: SUser): Boolean {
        val pubKey = getPubKey(hookInfo.callbackUrl)
        if (pubKey == null) {
            // Seems old hook. Forget about it
            context.storage.delete(info)
        } else {
            val authData = context.authDataStorage.find(pubKey)
            if (authData == null) {
                // Unknown webhook, remove from GitHub and local cache
                try {
                    DeleteWebHookAction.doRun(info, client, user, context)
                } catch(ignored: GitHubAccessException) {
                }
                context.storage.update(info) {
                    it.correct = false
                    it.status = Status.INCORRECT
                }
            } else {
                return true
            }
        }
        return false
    }

    private fun getPubKey(url: String?): String? {
        return url?.let { GitHubWebHookListener.getPubKeyFromRequestPath(url) }
    }
}