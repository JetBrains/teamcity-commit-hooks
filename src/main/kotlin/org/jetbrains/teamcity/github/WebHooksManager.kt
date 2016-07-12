package org.jetbrains.teamcity.github

import com.intellij.openapi.diagnostic.Logger
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
            if (storage.getHooks(info).any { !isBranchesInfoUpToDate(it, newState.branchRevisions, info) }) {
                storage.update(info) {
                    if (!isBranchesInfoUpToDate(it, newState.branchRevisions, info)) {
                        // Mark hook as outdated, probably incorrectly configured
                        it.status = Status.OUTDATED
                    }
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

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doRegisterWebHook(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, connection: OAuthConnectionDescriptor): Pair<HookAddOperationResult, WebHooksStorage.HookInfo> {
        return CreateWebHookAction.doRun(info, client, user, this, connection)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doGetAllWebHooks(info: GitHubRepositoryInfo, client: GitHubClientEx): Map<RepositoryHook, WebHooksStorage.HookInfo> {
        return GetAllWebHooksAction.doRun(info, client, this)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doUnRegisterWebHook(info: GitHubRepositoryInfo, client: GitHubClientEx): HookDeleteOperationResult {
        return DeleteWebHookAction.doRun(info, client, this)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doTestWebHook(info: GitHubRepositoryInfo, ghc: GitHubClientEx, hook: WebHooksStorage.HookInfo): HookTestOperationResult {
        return TestWebHookAction.doRun(info, ghc, this, hook)
    }

    fun updateLastUsed(info: GitHubRepositoryInfo, date: Date, hookInfo: WebHooksStorage.HookInfo) {
        // TODO: We should not show vcs root instances in health report if hook was used in last 7 (? or any other number) days. Even if we have not created that hook.
        storage.update(info) {
            if (it.isSame(hookInfo)) {
                val used = it.lastUsed
                it.status = Status.OK
                if (used == null || used.before(date)) {
                    it.lastUsed = date
                }
            }
        }
    }

    fun updateBranchRevisions(info: GitHubRepositoryInfo, map: Map<String, String>, hookInfo: WebHooksStorage.HookInfo) {
        storage.update(info) {
            if (it.isSame(hookInfo)) {
                it.status = Status.OK
                val lbr = it.lastBranchRevisions ?: HashMap()
                lbr.putAll(map)
                it.lastBranchRevisions = lbr
            }
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

    fun getHookForPubKey(authData: AuthDataStorage.AuthData): WebHooksStorage.HookInfo? {
        return storage.getHooks(authData.repository).firstOrNull { it.callbackUrl.endsWith(authData.public) }
    }

}
