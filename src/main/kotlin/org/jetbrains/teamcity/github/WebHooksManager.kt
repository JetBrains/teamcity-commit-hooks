package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.WebLinks
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.vcs.RepositoryState
import jetbrains.buildServer.vcs.RepositoryStateListener
import jetbrains.buildServer.vcs.RepositoryStateListenerAdapter
import jetbrains.buildServer.vcs.VcsRoot
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.client.RequestException
import org.jetbrains.teamcity.github.action.*
import org.jetbrains.teamcity.github.controllers.Status
import org.jetbrains.teamcity.github.controllers.good
import java.io.IOException
import java.util.*

class WebHooksManager(links: WebLinks,
                      private val repoStateEventDispatcher: EventDispatcher<RepositoryStateListener>,
                      authDataStorage: AuthDataStorage,
                      storage: WebHooksStorage) : ActionContext(storage, authDataStorage, links) {

    private val myRepoStateListener: RepositoryStateListenerAdapter = object : RepositoryStateListenerAdapter() {
        override fun repositoryStateChanged(root: VcsRoot, oldState: RepositoryState, newState: RepositoryState) {
            if (!Util.isSuitableVcsRoot(root)) return
            val info = Util.getGitHubInfo(root) ?: return
            for (hook in storage.getHooks(info)) {
                if (hook.status.good && !isBranchesInfoUpToDate(hook, newState.branchRevisions)) {
                    hook.status = Status.OUTDATED
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
        private val LOG = Util.getLogger(WebHooksManager::class.java)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doInstallWebHook(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, connection: OAuthConnectionDescriptor): Pair<HookAddOperationResult, WebHooksStorage.HookInfo> {
        return CreateWebHookAction.doRun(info, client, user, this, connection)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doGetAllWebHooks(info: GitHubRepositoryInfo, client: GitHubClientEx): Map<RepositoryHook, WebHooksStorage.HookInfo> {
        return GetAllWebHooksAction.doRun(info, client, this)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doDeleteWebHook(info: GitHubRepositoryInfo, client: GitHubClientEx): HookDeleteOperationResult {
        return DeleteWebHookAction.doRun(info, client, this)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doTestWebHook(info: GitHubRepositoryInfo, ghc: GitHubClientEx, hook: WebHooksStorage.HookInfo) {
        return TestWebHookAction.doRun(info, ghc, this, hook)
    }

    fun updateLastUsed(hookInfo: WebHooksStorage.HookInfo, date: Date) {
        // TODO: We should not show vcs root instances in health report if hook was used in last 7 (? or any other number) days. Even if we have not created that hook.
        val used = hookInfo.lastUsed
        hookInfo.status = Status.OK
        if (used == null || used.before(date)) {
            hookInfo.lastUsed = date
        }
    }

    fun updateBranchRevisions(hookInfo: WebHooksStorage.HookInfo, map: Map<String, String>) {
        hookInfo.status = Status.OK
        hookInfo.updateBranchMapping(map)
    }

    private fun isBranchesInfoUpToDate(hook: WebHooksStorage.HookInfo, newBranches: Map<String, String>): Boolean {
        val hookBranches = hook.lastBranchRevisions

        // Maybe we have forgot about revisions (cache cleanup after server restart)
        if (hookBranches == null) {
            hook.updateBranchMapping(newBranches)
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

    fun getHookForPubKey(authData: AuthDataStorage.AuthData): WebHooksStorage.HookInfo? {
        return authData.repository?.let { storage.getHooks(it).firstOrNull { it.callbackUrl.endsWith(authData.public) } }
    }

}
