package org.jetbrains.teamcity.github.action

import jetbrains.buildServer.serverSide.WebLinks
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryId
import org.jetbrains.teamcity.github.AuthDataStorage
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.WebHooksStorage
import org.jetbrains.teamcity.github.controllers.GitHubWebHookListener

open class ActionContext(val storage: WebHooksStorage,
                         protected val links: WebLinks) {

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
        for (hook in filtered) {
            val info = storage.getHook(server, repo)
            if (info == null) {
                addHook(hook, server, repo)
            } else if (info.id != hook.id || info.url != hook.url) {
                storage.delete(server, repo)
                addHook(hook, server, repo)
            }
        }
        if (filtered.isEmpty()) {
            // Remove old hooks
            storage.delete(server, repo)
        }
    }

    fun addHook(created: RepositoryHook, server: String, repo: RepositoryId) {
        storage.add(server, repo, { WebHooksStorage.HookInfo(created.id, created.url) })
    }

    fun getHook(info: GitHubRepositoryInfo): WebHooksStorage.HookInfo? {
        return storage.getHook(info)
    }
}