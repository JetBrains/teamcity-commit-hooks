package org.jetbrains.teamcity.github

import jetbrains.buildServer.controllers.vcs.GitHubWebHookListener
import jetbrains.buildServer.serverSide.WebLinks
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryId
import org.eclipse.egit.github.core.client.GitHubClient
import org.eclipse.egit.github.core.service.RepositoryService
import java.io.IOException
import java.util.*

public class WebHooksManager(private val links: WebLinks) {
    data class HookInfo(val id: Long, val url: String)
    class PerServerMap : HashMap<RepositoryId, HookInfo>();

    // TODO: Persist known hooks map between server restarts
    private val myHooks: MutableMap<String, PerServerMap> = HashMap<String, PerServerMap>().withDefault { PerServerMap() }

    @Throws(IOException::class)
    public fun doRegisterWebHook(info: VcsRootGitHubInfo, client: GitHubClient) {
        val repo = info.getRepositoryId()
        val service = RepositoryService(client)
        val hook = RepositoryHook().setActive(true).setName("web").setConfig(mapOf(
                "url" to getCallbackUrl(),
                "content_type" to "json"
                // TODO: Investigate ssl option
        ))
        val created: RepositoryHook
        try {
            created = service.createHook(repo, hook)
        } catch(e: IOException) {
            // Failed to create hook :(
            throw e
        }
        myHooks.getOrImplicitDefault(info.server).put(repo, HookInfo(created.id, created.url))
    }

    private fun getCallbackUrl(): String {
        // It's not possible to add some url parameters, since GitHub ignores that part of url
        return links.rootUrl.removeSuffix("/") + GitHubWebHookListener.PATH;
    }

    fun findHook(info: VcsRootGitHubInfo): Any? {
        // TODO: Populate map in background
        val map = myHooks[info.server] ?: return null
        val hookId = map[info.getRepositoryId()] ?: return null
        return hookId
    }

    fun updateLastUsed(info: VcsRootGitHubInfo, date: Date) {
        // We should not show vcs root instances in health report if hook was used in last 7 (? or any other number) days. Even if we have not created that hook.
        // TODO: Implement
    }
}