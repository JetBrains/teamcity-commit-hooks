package org.jetbrains.teamcity.github.action

import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import org.eclipse.egit.github.core.Repository
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryHookEx
import org.eclipse.egit.github.core.client.GitHubRequest
import org.eclipse.egit.github.core.client.IGitHubConstants
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.*
import org.jetbrains.teamcity.github.controllers.GitHubWebHookListener
import org.jetbrains.teamcity.github.controllers.Status
import org.jetbrains.teamcity.github.controllers.bad
import org.jetbrains.teamcity.github.controllers.good
import java.net.HttpRetryException
import java.net.URL

object CreateWebHookAction {

    private val LOG = Util.getLogger(CreateWebHookAction::class.java)

    @Throws(GitHubAccessException::class)
    fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext, connection: OAuthConnectionDescriptor): Pair<HookAddOperationResult, WebHooksStorage.HookInfo> {
        val repo = info.getRepositoryId()
        val service = RepositoryService(client)

        // Reload all hooks from GitHub
        // It's ok throw GitHubAccessException upwards: if we cannot get hooks, we cannot add new one
        GetAllWebHooksAction.doRun(info, client, context)

        for (hook in context.storage.getHooks(info)) {
            if (checkExisting(client, context, hook, info)) {
                return HookAddOperationResult.AlreadyExists to hook;
            }
        }

        val authData = context.authDataStorage.create(user, info, connection, false)

        val callbackUrl = context.getCallbackUrl(authData)

        val hook = RepositoryHookEx()
                .setEvents(arrayOf("push", "pull_request"))
                .setActive(true)
                .setName("web")
                .setConfig(mapOf(
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
            LOG.warnAndDebugDetails("Failed to create webhook for repository ${info.id}: ${e.status}", e)
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
        } catch (e: HttpRetryException) {
            val location = e.location ?: throw GitHubAccessException(GitHubAccessException.Type.InternalServerError, e.message)
            LOG.warn("Received redirect (${e.responseCode()} from GitHub to location '$location'")
            val repository: Repository
            val id = URL(location).path.split('/').dropLast(1).last()
            try {
                val request = GitHubRequest()
                request.uri = IGitHubConstants.SEGMENT_REPOSITORIES + "/" + id
                request.type = Repository::class.java
                repository = client.get(request).body as Repository
            } catch(e1: Throwable) {
                LOG.warn("Cannot obtain information about repository with id '$id' from server ${info.server}")
                throw GitHubAccessException(GitHubAccessException.Type.InternalServerError, e.message)
            }
            throw GitHubAccessException(GitHubAccessException.Type.Moved, "${info.server}/${repository.generateId()}")
        }


        if (callbackUrl != created.callbackUrl
            || authData.public != getPubKey(created.callbackUrl)) {
            // Weird
            // Either callback url is null or does not contains public key part of callback
            context.authDataStorage.remove(authData)
            throw IllegalStateException("GitHub returned incorrect hook")
        }

        var hookInfo = context.addHook(created)
        if (hookInfo == null) {
            context.authDataStorage.remove(authData)
            throw IllegalStateException("GitHub returned incorrect hook")
        }

        if (callbackUrl != hookInfo.callbackUrl) {
            // context.addHook returned another hook.
            context.storage.delete(info) { !it.status.good }
            hookInfo = context.addHook(created)
            if (hookInfo == null) {
                context.authDataStorage.remove(authData)
                throw IllegalStateException("GitHub returned incorrect hook")
            }
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
                context.storage.delete(hookInfo)
            } catch(ignored: GitHubAccessException) {
                hookInfo.status = Status.INCORRECT
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