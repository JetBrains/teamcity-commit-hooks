package org.jetbrains.teamcity.github

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.controllers.vcs.GitHubWebHookListener
import jetbrains.buildServer.serverSide.WebLinks
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.util.cache.CacheProvider
import jetbrains.buildServer.vcs.RepositoryState
import jetbrains.buildServer.vcs.RepositoryStateListener
import jetbrains.buildServer.vcs.RepositoryStateListenerAdapter
import jetbrains.buildServer.vcs.VcsRoot
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryId
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import java.io.IOException
import java.util.*

public class WebHooksManager(private val links: WebLinks, CacheProvider: CacheProvider, private val dispatcher: EventDispatcher<RepositoryStateListener>) {

    val listener: RepositoryStateListenerAdapter = object : RepositoryStateListenerAdapter() {
        override fun repositoryStateChanged(root: VcsRoot, oldState: RepositoryState, newState: RepositoryState) {
            if (!Util.isSuitableVcsRoot(root)) return
            val info = Util.getGitHubInfo(root) ?: return
            val hook = getHook(info) ?: return
            if (!isBranchesInfoUpToDate(hook, newState.branchRevisions, info)) {
                // Mark hook as outdated, probably incorrectly configured
                hook.correct = false
                save(info, hook)
            }
        }
    }

    fun init(): Unit {
        dispatcher.addListener(listener)
    }

    fun destroy(): Unit {
        dispatcher.removeListener(listener)
    }

    companion object {
        private val LOG = Logger.getInstance(WebHooksManager::class.java.name)
    }

    data class HookInfo(val id: Long, val url: String) {
        var correct: Boolean = true
        var lastUsed: Date? = null
        var lastBranchRevisions: MutableMap<String, String>? = null
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
    public fun doRegisterWebHook(info: VcsRootGitHubInfo, client: GitHubClientEx): HookAddOperationResult {
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
                    // Probably token does not have permissions
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

        addHook(created, info.server, repo)
        return HookAddOperationResult.CREATED
    }


    private fun populateHooks(server: String, repo: RepositoryId, filtered: List<RepositoryHook>) {
        for (hook in filtered) {
            addHook(hook, server, repo)
        }
    }

    private fun getCallbackUrl(): String {
        // It's not possible to add some url parameters, since GitHub ignores that part of url
        return links.rootUrl.removeSuffix("/") + GitHubWebHookListener.PATH;
    }

    private fun addHook(created: RepositoryHook, server: String, repo: RepositoryId) {
        save(server, repo, HookInfo(created.id, created.url))
    }

    private fun save(info: VcsRootGitHubInfo, hook: HookInfo) {
        save(info.server, info.getRepositoryId(), hook)
    }

    private fun save(server: String, repo: RepositoryId, hook: HookInfo) {
        synchronized(myCache) {
            val map = myCache.fetch(server, { PerServerMap() });
            map.put(repo, hook)
            myCache.write(server, map)
        }
    }

    fun getHook(info: VcsRootGitHubInfo): HookInfo? {
        // TODO: Populate map in background
        synchronized(myCache) {
            val map = myCache.read(info.server) ?: return null
            val hook = map[info.getRepositoryId()] ?: return null
            return hook
        }
    }

    fun updateLastUsed(info: VcsRootGitHubInfo, date: Date) {
        // We should not show vcs root instances in health report if hook was used in last 7 (? or any other number) days. Even if we have not created that hook.
        val hook = getHook(info) ?: return
        val used = hook.lastUsed
        if (used == null || used.before(date)) {
            hook.lastUsed = date
            save(info, hook)
        }
    }

    fun updateBranchRevisions(info: VcsRootGitHubInfo, map: Map<String, String>) {
        val hook = getHook(info) ?: return
        val lbr = hook.lastBranchRevisions ?: HashMap()
        lbr.putAll(map)
        hook.lastBranchRevisions = lbr
        save(info, hook)
    }

    private fun isBranchesInfoUpToDate(hook: HookInfo, newBranches: Map<String, String>, info: VcsRootGitHubInfo): Boolean {
        val hookBranches = hook.lastBranchRevisions

        // Maybe we have forgot about revisions (cache cleanup after server restart)
        if (hookBranches == null) {
            hook.lastBranchRevisions = HashMap(newBranches)
            save(info, hook)
            return true
        }
        for ((name, hash) in newBranches.entries) {
            val old = hookBranches[name]
            if (old == null) {
                LOG.warn("Hook $hook have no revision saved for branch $name, but it should be $hash")
                return false
            }
            if (old != hash) {
                LOG.warn("Hook $hook have incorrect revision saved for branch $name, expected $hash but found $old")
                return false
            }
        }
        return true
    }
}