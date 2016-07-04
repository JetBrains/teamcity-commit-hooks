package org.jetbrains.teamcity.github.action

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.WebLinks
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryId
import org.eclipse.egit.github.core.client.RequestException
import org.jetbrains.teamcity.github.*
import org.jetbrains.teamcity.github.controllers.GitHubWebHookListener
import org.jetbrains.teamcity.github.controllers.Status
import java.net.HttpURLConnection
import java.util.*

open class ActionContext(val storage: WebHooksStorage,
                         val authDataStorage: AuthDataStorage,
                         protected val links: WebLinks) {

    companion object {
        private val LOG = Logger.getInstance(ActionContext::class.java.name)
    }

    fun getCallbackUrl(authData: AuthDataStorage.AuthData? = null): String {
        // It's not possible to add some url parameters, since GitHub ignores that part of url
        val base = links.rootUrl.removeSuffix("/") + GitHubWebHookListener.PATH
        if (authData == null) {
            return base
        }
        return base + '/' + authData.public
    }

    fun updateHooks(server: String, repo: RepositoryId, filtered: List<RepositoryHook>): Map<RepositoryHook, WebHooksStorage.HookInfo> {
        // TODO: Report more than one active webhook in storage as misconfiguration
        if (filtered.isEmpty()) {
            // Mark old hooks as removed
            storage.update(server, repo) {
                it.status = Status.MISSING
            }
            return emptyMap()
        }
        val result = HashMap<RepositoryHook, WebHooksStorage.HookInfo>()
        val hooks = storage.getHooks(server, repo).toMutableList()

        val missing = hooks.any { hi ->
            !filtered.any { rh -> hi.isSame(rh) }
        }
        if (missing) {
            storage.update(server, repo) { hi ->
                val rh = filtered.firstOrNull { rh -> hi.isSame(rh) }
                if (rh == null) {
                    hi.status = Status.MISSING
                } else if (!rh.isActive) {
                    // TODO: Should check that status is OK?
                    hi.status = Status.DISABLED
                } else {
                    // TODO: Should update status?
                }
            }
        }

        for (hook in filtered) {
            if (hooks.isEmpty()) {
                result.put(hook, addHook(hook, server, repo)!!)
            } else {
                val same = hooks.firstOrNull { it.isSame(hook) }
                if (same != null) {
                    result.put(hook, same)
                    continue
                } else {
                    result.put(hook, addHook(hook, server, repo)!!)
                }
            }
        }
        return result
    }

    fun addHook(created: RepositoryHook, server: String, repo: RepositoryId): WebHooksStorage.HookInfo? {
        val callbackUrl = created.callbackUrl
        if (callbackUrl == null) {
            LOG.warn("Received RepositoryHook without callback url, ignoring it")
            return null
        }
        val status: Status
        if (created.lastResponse != null) {
            if (created.lastResponse.code in 200..299) {
                status = Status.OK
            } else {
                status = Status.PAYLOAD_DELIVERY_FAILED
            }
        } else {
            status = Status.WAITING_FOR_SERVER_RESPONSE
        }
        return storage.add(server, repo, { WebHooksStorage.HookInfo(created.id, created.url, status, callbackUrl = callbackUrl) })
    }

    fun getHook(info: GitHubRepositoryInfo): WebHooksStorage.HookInfo? {
        return storage.getHooks(info).firstOrNull()
    }

    @Throws(GitHubAccessException::class)
    fun handleCommonErrors(e: RequestException) {
        when (e.status) {
            HttpURLConnection.HTTP_INTERNAL_ERROR -> {
                throw GitHubAccessException(GitHubAccessException.Type.InternalServerError)
            }
            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                throw GitHubAccessException(GitHubAccessException.Type.InvalidCredentials)
            }
        }
    }
}