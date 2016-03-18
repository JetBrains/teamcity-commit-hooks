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
                myStorage.update(info) {
                    it.correct = false
                }
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

    public enum class HooksGetOperationResult {
        Ok
    }

    public enum class HookTestOperationResult {
        NotFound,
        Ok
    }

    public enum class HookAddOperationResult {
        AlreadyExists,
        Created,
    }

    public enum class HookDeleteOperationResult {
        Removed,
        NeverExisted,
    }

    interface Operation<ORT : Enum<ORT>> {
        @Throws(GitHubAccessException::class)
        public fun doRun(info: VcsRootGitHubInfo, client: GitHubClientEx): ORT
    }

    val GetAllWebHooks = object : Operation<HooksGetOperationResult> {
        @Throws(GitHubAccessException::class)
        override fun doRun(info: VcsRootGitHubInfo, client: GitHubClientEx): HooksGetOperationResult {
            val service = RepositoryService(client)
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
                        throw GitHubAccessException(GitHubAccessException.Type.InvalidCredentials)
                    }
                    403, 404 -> {
                        // No access
                        // Probably token does not have permissions
                        val scopes = client.tokenOAuthScopes?.map { it.toLowerCase() } ?: throw GitHubAccessException(GitHubAccessException.Type.NoAccess) // Weird. No header?
                        val pair = TokensHelper.getHooksAccessType(scopes)
                        val accessType = pair.first
                        when (accessType) {
                            TokensHelper.HookAccessType.NO_ACCESS -> throw GitHubAccessException(GitHubAccessException.Type.TokenScopeMismatch)
                            TokensHelper.HookAccessType.READ -> throw GitHubAccessException(GitHubAccessException.Type.TokenScopeMismatch)
                            TokensHelper.HookAccessType.WRITE, TokensHelper.HookAccessType.ADMIN -> throw GitHubAccessException(GitHubAccessException.Type.UserHaveNoAccess)
                        }
                    }
                }
                throw e
            }
            return HooksGetOperationResult.Ok
        }
    }

    val TestWebHook = object : Operation<HookTestOperationResult> {
        @Throws(GitHubAccessException::class)
        override fun doRun(info: VcsRootGitHubInfo, client: GitHubClientEx): HookTestOperationResult {
            val service = RepositoryService(client)
            val hook = getHook(info) ?: return HookTestOperationResult.NotFound
            try {
                service.testHook(info.getRepositoryId(), hook.id.toInt())
            } catch(e: RequestException) {
                when (e.status) {
                    401 -> throw GitHubAccessException(GitHubAccessException.Type.InvalidCredentials)
                    403, 404 -> {
                        // ? No access
                        val pair = TokensHelper.getHooksAccessType(client) ?: throw GitHubAccessException(GitHubAccessException.Type.NoAccess)// Weird. No header?
                        if (pair.first <= TokensHelper.HookAccessType.READ) throw GitHubAccessException(GitHubAccessException.Type.TokenScopeMismatch)
                        throw GitHubAccessException(GitHubAccessException.Type.UserHaveNoAccess)
                    }
                }
                throw e
            }
            return HookTestOperationResult.Ok
        }
    }

    val CreateWebHook = object : Operation<HookAddOperationResult> {
        @Throws(GitHubAccessException::class)
        override fun doRun(info: VcsRootGitHubInfo, client: GitHubClientEx): HookAddOperationResult {
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
            // TODO: Consider handling GitHubAccessException
            GetAllWebHooks.doRun(info, client)

            if (getHook(info) != null) {
                return HookAddOperationResult.AlreadyExists
            }

            val created: RepositoryHook
            try {
                created = service.createHook(repo, hook)
            } catch(e: RequestException) {
                when (e.status) {
                    401 -> throw GitHubAccessException(GitHubAccessException.Type.InvalidCredentials)
                    403, 404 -> {
                        // ? No access
                        val pair = TokensHelper.getHooksAccessType(client) ?: throw GitHubAccessException(GitHubAccessException.Type.NoAccess)// Weird. No header?
                        if (pair.first <= TokensHelper.HookAccessType.READ) throw GitHubAccessException(GitHubAccessException.Type.TokenScopeMismatch)
                        throw GitHubAccessException(GitHubAccessException.Type.UserHaveNoAccess)
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
    }

    val DeleteWebHook = object : Operation<HookDeleteOperationResult> {
        @Throws(GitHubAccessException::class)
        override fun doRun(info: VcsRootGitHubInfo, client: GitHubClientEx): HookDeleteOperationResult {
            val repo = info.getRepositoryId()
            val service = RepositoryService(client)

            var hook = getHook(info)

            if (hook != null) {
                try {
                    service.deleteHook(repo, hook.id.toInt())
                } catch(e: RequestException) {
                    // TODO: Check result code
                }
                myStorage.delete(info.server, repo)
                return HookDeleteOperationResult.Removed
            }

            // TODO: Consider handling GitHubAccessException
            GetAllWebHooks.doRun(info, client)

            hook = getHook(info)

            if (hook != null) {
                try {
                    service.deleteHook(repo, hook.id.toInt())
                } catch(e: RequestException) {
                    // TODO: Check result code
                }
                myStorage.delete(info.server, repo)
                return HookDeleteOperationResult.Removed
            }

            return HookDeleteOperationResult.NeverExisted
        }
    }


    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    public fun doRegisterWebHook(info: VcsRootGitHubInfo, client: GitHubClientEx): HookAddOperationResult {
        return CreateWebHook.doRun(info, client)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    public fun doGetAllWebHooks(info: VcsRootGitHubInfo, client: GitHubClientEx): HooksGetOperationResult {
        return GetAllWebHooks.doRun(info, client)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    public fun doUnRegisterWebHook(info: VcsRootGitHubInfo, client: GitHubClientEx): HookDeleteOperationResult {
        return DeleteWebHook.doRun(info, client)
    }


    private fun populateHooks(server: String, repo: RepositoryId, filtered: List<RepositoryHook>) {
        for (hook in filtered) {
            val info = myStorage.getHook(server, repo)
            if (info != null) {
                assert(info.id == hook.id)
                assert(info.url == hook.url)
            } else {
                addHook(hook, server, repo)
            }
        }
    }

    private fun getCallbackUrl(): String {
        // It's not possible to add some url parameters, since GitHub ignores that part of url
        return links.rootUrl.removeSuffix("/") + GitHubWebHookListener.PATH;
    }

    private fun addHook(created: RepositoryHook, server: String, repo: RepositoryId) {
        myStorage.add(server, repo, { WebHooksStorage.HookInfo(created.id, created.url) })
    }

    fun getHook(info: VcsRootGitHubInfo): WebHooksStorage.HookInfo? {
        return myStorage.getHook(info)
    }

    fun updateLastUsed(info: VcsRootGitHubInfo, date: Date) {
        // We should not show vcs root instances in health report if hook was used in last 7 (? or any other number) days. Even if we have not created that hook.
        val hook = getHook(info) ?: return
        val used = hook.lastUsed
        if (used == null || used.before(date)) {
            myStorage.update(info) {
                @Suppress("NAME_SHADOWING")
                val used = it.lastUsed
                if (used == null || used.before(date)) {
                    it.correct = true
                    it.lastUsed = date
                }
            }
        }
    }

    fun updateBranchRevisions(info: VcsRootGitHubInfo, map: Map<String, String>) {
        val hook = getHook(info) ?: return
        myStorage.update(info) {
            it.correct = true
            val lbr = hook.lastBranchRevisions ?: HashMap()
            lbr.putAll(map)
            it.lastBranchRevisions = lbr
        }
    }

    private fun isBranchesInfoUpToDate(hook: WebHooksStorage.HookInfo, newBranches: Map<String, String>, info: VcsRootGitHubInfo): Boolean {
        val hookBranches = hook.lastBranchRevisions

        // Maybe we have forgot about revisions (cache cleanup after server restart)
        if (hookBranches == null) {
            myStorage.update(info) {
                it.lastBranchRevisions = HashMap(newBranches)
            }
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
