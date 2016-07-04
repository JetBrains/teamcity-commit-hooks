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
        // TODO: Support more than one hook in storage, report that as misconfiguration
        if (filtered.isEmpty()) {
            // Mark old hooks as removed
            storage.update(server, repo) {
                it.status = Status.MISSING
            }
            return emptyMap()
        }
        if (!filtered.any { it.isActive }) {
            // No active webhooks, mark infos as disabled
            // TODO: Investigate whether hook disabled if GitHub failed to deliver payload (TC returned error)
            storage.update(server, repo) {
//                it.correct = false
                it.status = Status.DISABLED
            }
            return emptyMap()
        }
        val result = HashMap<RepositoryHook, WebHooksStorage.HookInfo>()
        for (hook in filtered) {
            if (!hook.isActive) continue
            val info = storage.getHook(server, repo)
            if (info == null) {
                result.put(hook, addHook(hook, server, repo)!!)
            } else if (info.id != hook.id || info.url != hook.url || info.callbackUrl != hook.callbackUrl) {
                storage.update(server, repo) {
                    it.status = Status.MISSING
                }
                result.put(hook, addHook(hook, server, repo)!!)
            } else {
                result.put(hook, info)
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
        return storage.add(server, repo, { WebHooksStorage.HookInfo(created.id, created.url, status, true, callbackUrl = callbackUrl) })
    }

    fun getHook(info: GitHubRepositoryInfo): WebHooksStorage.HookInfo? {
        return storage.getHook(info)
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