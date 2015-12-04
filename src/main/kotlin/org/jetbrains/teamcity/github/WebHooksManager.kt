package org.jetbrains.teamcity.github

import jetbrains.buildServer.controllers.vcs.GitHubWebHookListener
import jetbrains.buildServer.serverSide.WebLinks
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.util.cache.CacheProvider
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryId
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import java.io.IOException
import java.util.*
import javax.annotation.concurrent.NotThreadSafe

@NotThreadSafe
public class WebHooksManager(private val links: WebLinks, CacheProvider: CacheProvider) {
    data class HookInfo(val id: Long, val url: String) {
        var lastUsed: Date? = null
    }

    class PerServerMap : HashMap<RepositoryId, HookInfo>();

    private val myCache = CacheProvider.getOrCreateCache("WebHooksCache", PerServerMap::class.java)


    public enum class HookAddOperationResult {
        INVALID_CREDENTIALS,
        NOT_ENOUGH_TOKEN_SCOPE, // If token is valid but does not have required scope
        NO_ACCESS,
        USER_HAVE_NO_ACCESS,
        REPO_NOT_EXISTS,
        ALREADY_EXISTS,
        CREATED,
    }

    @Throws(IOException::class, RequestException::class)
    public fun doRegisterWebHook(info: VcsRootGitHubInfo, client: GitHubClientEx, tokenOwner: String): HookAddOperationResult {
        val repo = info.getRepositoryId()
        val service = RepositoryService(client)
        val hook = RepositoryHook().setActive(true).setName("web").setConfig(mapOf(
                "url" to getCallbackUrl(),
                "content_type" to "json"
                // TODO: Investigate ssl option
        ))

        if (getHook(info) != null) {
            return HookAddOperationResult.ALREADY_EXISTS
        }

        // First, check for already existing hooks, otherwise Github will answer with code 422
        // If we cannot get hooks, we cannot add new one
        try {
            val hooks = service.getHooks(repo)
            val filtered = hooks.filter { it.name == "web" && it.config["url"] == getCallbackUrl() && it.config["content_type"] == "json" }
            if (filtered.isNotEmpty()) {
                populateHooks(info.server, repo, filtered);
            }
        } catch(e: RequestException) {
            when (e.status) {
                401 -> {
                    return HookAddOperationResult.INVALID_CREDENTIALS
                }
                403, 404 -> {
                    // No access
                    // Probably token does not have 'repo_hook' permission, lets check that
                    val scopes = client.tokenOAuthScopes?.map { it.toLowerCase() } ?: return HookAddOperationResult.NO_ACCESS // Weird. No header?
                    val pair = TokensHelper.getHooksAccessType(scopes)
                    val accessType = pair.first
                    when (accessType) {
                        TokensHelper.HookAccessType.NO_ACCESS -> return HookAddOperationResult.NOT_ENOUGH_TOKEN_SCOPE
                        TokensHelper.HookAccessType.READ -> return HookAddOperationResult.NOT_ENOUGH_TOKEN_SCOPE
                        TokensHelper.HookAccessType.WRITE, TokensHelper.HookAccessType.ADMIN -> {
                            return HookAddOperationResult.USER_HAVE_NO_ACCESS
                        }
                    }
                }
            }
            throw e
        }

        if (getHook(info) != null) {
            return HookAddOperationResult.ALREADY_EXISTS
        }

        val created: RepositoryHook
        try {
            created = service.createHook(repo, hook)
        } catch(e: RequestException) {
            when (e.status) {
                401 -> return HookAddOperationResult.INVALID_CREDENTIALS
                403, 404 -> {
                    // ? No access
                    val pair = TokensHelper.getHooksAccessType(client) ?: return HookAddOperationResult.NO_ACCESS // Weird. No header?
                    if (pair.first <= TokensHelper.HookAccessType.READ) return HookAddOperationResult.NOT_ENOUGH_TOKEN_SCOPE
                    return HookAddOperationResult.USER_HAVE_NO_ACCESS
                }
                422 -> {
                    if (e.error.errors.any { it.resource.equals("hook", true) && it.message.contains("already exists") }) {
                        // Already exists
                        return HookAddOperationResult.ALREADY_EXISTS
                    }
                }
            }
            throw e
        }

        save(created, info.server, repo)
        return HookAddOperationResult.CREATED
    }


    private fun populateHooks(server: String, repo: RepositoryId, filtered: List<RepositoryHook>) {
        for (hook in filtered) {
            save(hook, server, repo)
        }
    }

    private fun getCallbackUrl(): String {
        // It's not possible to add some url parameters, since GitHub ignores that part of url
        return links.rootUrl.removeSuffix("/") + GitHubWebHookListener.PATH;
    }

    private fun save(created: RepositoryHook, server: String, repo: RepositoryId) {
        val map = myCache.fetch(server, { PerServerMap() });
        map.put(repo, HookInfo(created.id, created.url))
        myCache.write(server, map)
    }

    fun getHook(info: VcsRootGitHubInfo): HookInfo? {
        // TODO: Populate map in background
        val map = myCache.read(info.server) ?: return null
        val hook = map[info.getRepositoryId()] ?: return null
        return hook
    }

    fun updateLastUsed(info: VcsRootGitHubInfo, date: Date) {
        // We should not show vcs root instances in health report if hook was used in last 7 (? or any other number) days. Even if we have not created that hook.
        val hook = getHook(info) ?: return
        val used = hook.lastUsed
        if (used == null || used.before(date)) {
            hook.lastUsed = date
        }
    }
}