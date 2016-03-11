package org.jetbrains.teamcity.github

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.WebLinks
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.vcs.RepositoryState
import jetbrains.buildServer.vcs.RepositoryStateListener
import jetbrains.buildServer.vcs.RepositoryStateListenerAdapter
import jetbrains.buildServer.vcs.VcsRoot
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryId
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.controllers.GitHubWebHookListener
import java.io.IOException
import java.util.*

public class WebHooksManager(private val links: WebLinks,
                             private val repoStateEventDispatcher: EventDispatcher<RepositoryStateListener>,
                             private val myStorage: WebHooksStorage) {

    private val myRepoStateListener: RepositoryStateListenerAdapter = object : RepositoryStateListenerAdapter() {
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
        repoStateEventDispatcher.addListener(myRepoStateListener)
    }

    fun destroy(): Unit {
        repoStateEventDispatcher.removeListener(myRepoStateListener)
    }

    companion object {
        private val LOG = Logger.getInstance(WebHooksManager::class.java.name)
    }

    public enum class HookAddOperationResult {
        InvalidCredentials,
        TokenScopeMismatch, // If token is valid but does not have required scope
        NoAccess,
        UserHaveNoAccess,
        AlreadyExists,
        Created,
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
            return HookAddOperationResult.AlreadyExists
        }

        // First, check for already existing hooks, otherwise Github will answer with code 422
        // If we cannot get hooks, we cannot add new one
        val result = doGetAllRepoHooks(info, client, service)
        when (result) {
            HooksGetOperationResult.InvalidCredentials -> return HookAddOperationResult.InvalidCredentials
            HooksGetOperationResult.TokenScopeMismatch -> return HookAddOperationResult.TokenScopeMismatch
            HooksGetOperationResult.NoAccess -> return HookAddOperationResult.NoAccess
            HooksGetOperationResult.UserHaveNoAccess -> return HookAddOperationResult.UserHaveNoAccess
            HooksGetOperationResult.Ok -> {
            }
        }

        if (getHook(info) != null) {
            return HookAddOperationResult.AlreadyExists
        }

        val created: RepositoryHook
        try {
            created = service.createHook(repo, hook)
        } catch(e: RequestException) {
            when (e.status) {
                401 -> return HookAddOperationResult.InvalidCredentials
                403, 404 -> {
                    // ? No access
                    val pair = TokensHelper.getHooksAccessType(client) ?: return HookAddOperationResult.NoAccess // Weird. No header?
                    if (pair.first <= TokensHelper.HookAccessType.READ) return HookAddOperationResult.TokenScopeMismatch
                    return HookAddOperationResult.UserHaveNoAccess
                }
                422 -> {
                    if (e.error.errors.any { it.resource.equals("hook", true) && it.message.contains("already exists") }) {
                        // Already exists
                        return HookAddOperationResult.AlreadyExists
                    }
                }
            }
            throw e
        }

        addHook(created, info.server, repo)
        return HookAddOperationResult.Created
    }

    public enum class HooksGetOperationResult {
        InvalidCredentials,
        TokenScopeMismatch, // If token is valid but does not have required scope
        NoAccess,
        UserHaveNoAccess,
        Ok
    }

    public fun doGetAllWebHooks(info: VcsRootGitHubInfo, client: GitHubClientEx): HooksGetOperationResult {
        val service = RepositoryService(client)
        return doGetAllRepoHooks(info, client, service)
    }

    private fun doGetAllRepoHooks(info: VcsRootGitHubInfo, client: GitHubClientEx, service: RepositoryService): HooksGetOperationResult {
        val repo = info.getRepositoryId()
        try {
            val hooks = service.getHooks(repo)
            val filtered = hooks.filter { it.name == "web" && it.config["url"] == getCallbackUrl() && it.config["content_type"] == "json" }
            if (filtered.isNotEmpty()) {
                populateHooks(info.server, repo, filtered);
            }
        } catch(e: RequestException) {
            when (e.status) {
                401 -> {
                    return HooksGetOperationResult.InvalidCredentials
                }
                403, 404 -> {
                    // No access
                    // Probably token does not have permissions
                    val scopes = client.tokenOAuthScopes?.map { it.toLowerCase() } ?: return HooksGetOperationResult.NoAccess // Weird. No header?
                    val pair = TokensHelper.getHooksAccessType(scopes)
                    val accessType = pair.first
                    when (accessType) {
                        TokensHelper.HookAccessType.NO_ACCESS -> return HooksGetOperationResult.TokenScopeMismatch
                        TokensHelper.HookAccessType.READ -> return HooksGetOperationResult.TokenScopeMismatch
                        TokensHelper.HookAccessType.WRITE, TokensHelper.HookAccessType.ADMIN -> {
                            return HooksGetOperationResult.UserHaveNoAccess
                        }
                    }
                }
            }
            throw e
        }
        return HooksGetOperationResult.Ok
    }

    public enum class HookDeleteOperationResult {
        InvalidCredentials,
        TokenScopeMismatch, // If token is valid but does not have required scope
        NoAccess,
        UserHaveNoAccess,
        Removed,
        NeverExisted,
    }

    @Throws(IOException::class, RequestException::class)
    public fun doUnRegisterWebHook(info: VcsRootGitHubInfo, client: GitHubClientEx): HookDeleteOperationResult {
        val repo = info.getRepositoryId()
        val service = RepositoryService(client)

        var hook = getHook(info)

        if (hook != null) {
            try {
                service.deleteHook(repo, hook.id.toInt())
            } catch(e: RequestException) {
                // TODO: Check result code
            }
            return HookDeleteOperationResult.Removed
        }

        val result = doGetAllRepoHooks(info, client, service)
        when (result) {
            HooksGetOperationResult.InvalidCredentials -> return HookDeleteOperationResult.InvalidCredentials
            HooksGetOperationResult.TokenScopeMismatch -> return HookDeleteOperationResult.TokenScopeMismatch
            HooksGetOperationResult.NoAccess -> return HookDeleteOperationResult.NoAccess
            HooksGetOperationResult.UserHaveNoAccess -> return HookDeleteOperationResult.UserHaveNoAccess
            HooksGetOperationResult.Ok -> {
            }
        }

        hook = getHook(info)

        if (hook != null) {
            try {
                service.deleteHook(repo, hook.id.toInt())
            } catch(e: RequestException) {
                // TODO: Check result code
            }
            return HookDeleteOperationResult.Removed
        }

        return HookDeleteOperationResult.NeverExisted
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
        save(server, repo, WebHooksStorage.HookInfo(created.id, created.url))
    }

    private fun save(info: VcsRootGitHubInfo, hook: WebHooksStorage.HookInfo) {
        myStorage.storeHook(info, hook)
    }

    private fun save(server: String, repo: RepositoryId, hook: WebHooksStorage.HookInfo) {
        myStorage.storeHook(server, repo, hook)
    }

    fun getHook(info: VcsRootGitHubInfo): WebHooksStorage.HookInfo? {
        return myStorage.getHook(info)
    }

    fun updateLastUsed(info: VcsRootGitHubInfo, date: Date) {
        // We should not show vcs root instances in health report if hook was used in last 7 (? or any other number) days. Even if we have not created that hook.
        val hook = getHook(info) ?: return
        val used = hook.lastUsed
        if (used == null || used.before(date)) {
            hook.correct = true
            hook.lastUsed = date
            save(info, hook)
        }
    }

    fun updateBranchRevisions(info: VcsRootGitHubInfo, map: Map<String, String>) {
        val hook = getHook(info) ?: return
        val lbr = hook.lastBranchRevisions ?: HashMap()
        lbr.putAll(map)
        hook.correct = true
        hook.lastBranchRevisions = lbr
        save(info, hook)
    }

    private fun isBranchesInfoUpToDate(hook: WebHooksStorage.HookInfo, newBranches: Map<String, String>, info: VcsRootGitHubInfo): Boolean {
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

    fun isHasIncorrectHooks() = myStorage.isHasIncorrectHooks()
    fun getIncorrectHooks() = myStorage.getIncorrectHooks()

}