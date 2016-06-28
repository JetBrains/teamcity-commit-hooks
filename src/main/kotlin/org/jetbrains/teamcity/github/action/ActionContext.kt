package org.jetbrains.teamcity.github.action

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.WebLinks
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryId
import org.jetbrains.teamcity.github.AuthDataStorage
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.WebHooksStorage
import org.jetbrains.teamcity.github.callbackUrl
import org.jetbrains.teamcity.github.controllers.GitHubWebHookListener

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

    fun updateHooks(server: String, repo: RepositoryId, filtered: List<RepositoryHook>) {
        // TODO: Support more than one hook in storage, report that as misconfiguration
        if (filtered.isEmpty()) {
            // Remove old hooks
            storage.delete(server, repo)
            return
        }
        if (!filtered.any { it.isActive }) {
            // No active webhooks, remove info
            // TODO: Investigate whether hook disabled if GitHub failed to deliver payload (TC returned error)
            storage.delete(server, repo)
            return
        }
        for (hook in filtered) {
            if (!hook.isActive) continue
            val info = storage.getHook(server, repo)
            if (info == null) {
                addHook(hook, server, repo)
            } else if (info.id != hook.id || info.url != hook.url) {
                storage.delete(server, repo)
                addHook(hook, server, repo)
            }
        }
    }

    fun addHook(created: RepositoryHook, server: String, repo: RepositoryId) {
        val callbackUrl = created.callbackUrl
        if (callbackUrl == null) {
            LOG.warn("Received RepositoryHook without callback url, ignoring it")
            return
        }
        storage.add(server, repo, { WebHooksStorage.HookInfo(created.id, created.url, callbackUrl = callbackUrl) })
    }

    fun getHook(info: GitHubRepositoryInfo): WebHooksStorage.HookInfo? {
        return storage.getHook(info)
    }
}