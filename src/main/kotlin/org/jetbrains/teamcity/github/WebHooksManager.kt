package org.jetbrains.teamcity.github

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.WebLinks
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
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

class WebHooksManager(private val links: WebLinks,
                      private val repoStateEventDispatcher: EventDispatcher<RepositoryStateListener>,
                      private val myAuthDataStorage: AuthDataStorage,
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

    enum class HooksGetOperationResult {
        Ok
    }

    enum class HookTestOperationResult {
        NotFound,
        Ok
    }

    enum class HookAddOperationResult {
        AlreadyExists,
        Created,
    }

    enum class HookDeleteOperationResult {
        Removed,
        NeverExisted,
    }

    interface Operation<ORT : Enum<ORT>> {
        @Throws(GitHubAccessException::class) fun doRun(info: VcsRootGitHubInfo, client: GitHubClientEx, user: SUser): ORT
    }

    val GetAllWebHooks = object : Operation<HooksGetOperationResult> {
        @Throws(GitHubAccessException::class)
        override fun doRun(info: VcsRootGitHubInfo, client: GitHubClientEx, user: SUser): HooksGetOperationResult {
            val service = RepositoryService(client)
            val repo = info.getRepositoryId()
            try {
                val hooks = service.getHooks(repo)
                // TODO: Check AuthData.user == user
                val filtered = hooks.filter { it.name == "web" && it.config["url"].orEmpty().startsWith(getCallbackUrl()) && it.config["content_type"] == "json" }
                updateHooks(info.server, repo, filtered);
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
        override fun doRun(info: VcsRootGitHubInfo, client: GitHubClientEx, user: SUser): HookTestOperationResult {
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
        override fun doRun(info: VcsRootGitHubInfo, client: GitHubClientEx, user: SUser): HookAddOperationResult {
            val repo = info.getRepositoryId()
            val service = RepositoryService(client)

            if (getHook(info) != null) {
                return HookAddOperationResult.AlreadyExists
            }

            val authData = myAuthDataStorage.create(user);

            val hook = RepositoryHook().setActive(true).setName("web").setConfig(mapOf(
                    "url" to getCallbackUrl(authData),
                    "content_type" to "json",
                    "secret" to authData.secret
                    // TODO: Investigate ssl option
            ))

            // First, check for already existing hooks, otherwise Github will answer with code 422
            // If we cannot get hooks, we cannot add new one
            // TODO: Consider handling GitHubAccessException
            GetAllWebHooks.doRun(info, client, user)

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
        override fun doRun(info: VcsRootGitHubInfo, client: GitHubClientEx, user: SUser): HookDeleteOperationResult {
            val repo = info.getRepositoryId()
            val service = RepositoryService(client)

            var hook = getHook(info)

            if (hook != null) {
                delete(client, hook, info, repo, service)
                return HookDeleteOperationResult.Removed
            }

            // TODO: Consider handling GitHubAccessException
            GetAllWebHooks.doRun(info, client, user)

            hook = getHook(info)

            if (hook != null) {
                delete(client, hook, info, repo, service)
                return HookDeleteOperationResult.Removed
            }

            return HookDeleteOperationResult.NeverExisted
        }

        private fun delete(client: GitHubClientEx, hook: WebHooksStorage.HookInfo, info: VcsRootGitHubInfo, repo: RepositoryId, service: RepositoryService) {
            try {
                service.deleteHook(repo, hook.id.toInt())
            } catch(e: RequestException) {
                when (e.status) {
                    403, 404 -> {
                        // ? No access
                        // "X-Accepted-OAuth-Scopes" -> "admin:repo_hook, public_repo, repo"
                        val pair = TokensHelper.getHooksAccessType(client) ?: throw GitHubAccessException(GitHubAccessException.Type.NoAccess)// Weird. No header?
                        if (pair.first < TokensHelper.HookAccessType.ADMIN) throw GitHubAccessException(GitHubAccessException.Type.TokenScopeMismatch, "Required scope 'admin:repo_hook', 'public_repo' or 'repo'")
                        throw GitHubAccessException(GitHubAccessException.Type.UserHaveNoAccess)
                    }
                }
                throw e
            }
            myStorage.delete(info.server, repo)
        }
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class) fun doRegisterWebHook(info: VcsRootGitHubInfo, client: GitHubClientEx, user: SUser): HookAddOperationResult {
        return CreateWebHook.doRun(info, client, user)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class) fun doGetAllWebHooks(info: VcsRootGitHubInfo, client: GitHubClientEx, user: SUser): HooksGetOperationResult {
        return GetAllWebHooks.doRun(info, client, user)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class) fun doUnRegisterWebHook(info: VcsRootGitHubInfo, client: GitHubClientEx, user: SUser): HookDeleteOperationResult {
        return DeleteWebHook.doRun(info, client, user)
    }


    private fun updateHooks(server: String, repo: RepositoryId, filtered: List<RepositoryHook>) {
        // TODO: Support more than one hook in storage, report that as misconfiguration
        for (hook in filtered) {
            val info = myStorage.getHook(server, repo)
            if (info == null) {
                addHook(hook, server, repo)
            } else if (info.id != hook.id || info.url != hook.url) {
                myStorage.delete(server, repo)
                addHook(hook, server, repo)
            }
        }
        if (filtered.isEmpty()) {
            // Remove old hooks
            myStorage.delete(server, repo)
        }
    }

    private fun getCallbackUrl(authData: AuthDataStorage.AuthData? = null): String {
        // It's not possible to add some url parameters, since GitHub ignores that part of url
        val base = links.rootUrl.removeSuffix("/") + GitHubWebHookListener.PATH
        if (authData == null) {
            return base
        }
        return base + '/' + authData.public;
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
