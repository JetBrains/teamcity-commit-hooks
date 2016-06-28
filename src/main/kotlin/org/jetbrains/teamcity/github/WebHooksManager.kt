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
import org.jetbrains.teamcity.github.action.*
import java.io.IOException
import java.util.*

class WebHooksManager(links: WebLinks,
                      private val repoStateEventDispatcher: EventDispatcher<RepositoryStateListener>,
                      private val myAuthDataStorage: AuthDataStorage,
                      storage: WebHooksStorage) : ActionContext(storage, links) {

    private val myRepoStateListener: RepositoryStateListenerAdapter = object : RepositoryStateListenerAdapter() {
        override fun repositoryStateChanged(root: VcsRoot, oldState: RepositoryState, newState: RepositoryState) {
            if (!Util.isSuitableVcsRoot(root)) return
            val info = Util.getGitHubInfo(root) ?: return
            val hook = getHook(info) ?: return
            if (!isBranchesInfoUpToDate(hook, newState.branchRevisions, info)) {
                // Mark hook as outdated, probably incorrectly configured
                storage.update(info) {
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

    val GetAllWebHooks = object : Action<HooksGetOperationResult, ActionContext> {
        @Throws(GitHubAccessException::class)
        override fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext): HooksGetOperationResult {
            val service = RepositoryService(client)
            val repo = info.getRepositoryId()
            try {
                val hooks = service.getHooks(repo)
                // TODO: Check AuthData.user == user
                val filtered = hooks.filter { it.name == "web" && it.config["url"].orEmpty().startsWith(context.getCallbackUrl()) && it.config["content_type"] == "json" }
                context.updateHooks(info.server, repo, filtered)
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

    val CreateWebHook = object : Action<HookAddOperationResult, ActionContext> {
        @Throws(GitHubAccessException::class)
        override fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext): HookAddOperationResult {
            val repo = info.getRepositoryId()
            val service = RepositoryService(client)

            if (context.getHook(info) != null) {
                return HookAddOperationResult.AlreadyExists
            }

            // First, check for already existing hooks, otherwise Github will answer with code 422
            // If we cannot get hooks, we cannot add new one
            // TODO: Consider handling GitHubAccessException
            GetAllWebHooks.doRun(info, client, user, context)

            if (context.getHook(info) != null) {
                return HookAddOperationResult.AlreadyExists
            }

            val authData = myAuthDataStorage.create(user, info, false)

            val hook = RepositoryHook().setActive(true).setName("web").setConfig(mapOf(
                    "url" to context.getCallbackUrl(authData),
                    "content_type" to "json",
                    "secret" to authData.secret
                    // TODO: Investigate ssl option
            ))

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
                            // TODO: Handle AuthData
                            // TODO: Remove existing hook if there no auth data know here.
                            return HookAddOperationResult.AlreadyExists
                        }
                    }
                }
                throw e
            }

            context.addHook(created, info.server, repo)

            myAuthDataStorage.store(authData)
            return HookAddOperationResult.Created

        }
    }

    val DeleteWebHook = object : Action<HookDeleteOperationResult, ActionContext> {
        @Throws(GitHubAccessException::class)
        override fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext): HookDeleteOperationResult {
            val repo = info.getRepositoryId()
            val service = RepositoryService(client)

            var hook = context.getHook(info)

            if (hook != null) {
                delete(client, hook, info, repo, service, context)
                return HookDeleteOperationResult.Removed
            }

            // TODO: Consider handling GitHubAccessException
            GetAllWebHooks.doRun(info, client, user, context)

            hook = context.getHook(info)

            if (hook != null) {
                delete(client, hook, info, repo, service, context)
                return HookDeleteOperationResult.Removed
            }

            return HookDeleteOperationResult.NeverExisted
        }

        private fun delete(client: GitHubClientEx, hook: WebHooksStorage.HookInfo, info: GitHubRepositoryInfo, repo: RepositoryId, service: RepositoryService, context: ActionContext) {
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
            context.storage.delete(info.server, repo)
        }
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doRegisterWebHook(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser): HookAddOperationResult {
        return CreateWebHook.doRun(info, client, user, this)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doGetAllWebHooks(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser): HooksGetOperationResult {
        return GetAllWebHooks.doRun(info, client, user, this)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doUnRegisterWebHook(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser): HookDeleteOperationResult {
        return DeleteWebHook.doRun(info, client, user, this)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doTestWebHook(info: GitHubRepositoryInfo, ghc: GitHubClientEx, user: SUser): HookTestOperationResult {
        return TestWebHookAction.doRun(info, ghc, user, this)
    }
    
    fun updateLastUsed(info: GitHubRepositoryInfo, date: Date) {
        // We should not show vcs root instances in health report if hook was used in last 7 (? or any other number) days. Even if we have not created that hook.
        val hook = getHook(info) ?: return
        val used = hook.lastUsed
        if (used == null || used.before(date)) {
            storage.update(info) {
                @Suppress("NAME_SHADOWING")
                val used = it.lastUsed
                if (used == null || used.before(date)) {
                    it.correct = true
                    it.lastUsed = date
                }
            }
        }
    }

    fun updateBranchRevisions(info: GitHubRepositoryInfo, map: Map<String, String>) {
        val hook = getHook(info) ?: return
        storage.update(info) {
            it.correct = true
            val lbr = hook.lastBranchRevisions ?: HashMap()
            lbr.putAll(map)
            it.lastBranchRevisions = lbr
        }
    }

    private fun isBranchesInfoUpToDate(hook: WebHooksStorage.HookInfo, newBranches: Map<String, String>, info: GitHubRepositoryInfo): Boolean {
        val hookBranches = hook.lastBranchRevisions

        // Maybe we have forgot about revisions (cache cleanup after server restart)
        if (hookBranches == null) {
            storage.update(info) {
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

    fun isHasIncorrectHooks() = storage.isHasIncorrectHooks()
    fun getIncorrectHooks() = storage.getIncorrectHooks()

}
