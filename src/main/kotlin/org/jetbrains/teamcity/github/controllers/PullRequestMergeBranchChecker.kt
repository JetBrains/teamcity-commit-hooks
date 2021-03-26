/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.teamcity.github.controllers

import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.executors.ExecutorServices
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientFactory
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.users.impl.UserEx
import org.jetbrains.teamcity.github.*
import org.jetbrains.teamcity.github.action.GetPullRequestDetailsAction
import org.jetbrains.teamcity.impl.RestApiFacade
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PullRequestMergeBranchChecker(
        private val myProjectManager: ProjectManager,
        private val myOAuthConnectionsManager: OAuthConnectionsManager,
        private val myAuthDataStorage: AuthDataStorage,
        private val myWebHooksManager: WebHooksManager,
        ExecutorServices: ExecutorServices,
        private val RestApiFacade: RestApiFacade,
        private val myTokensHelper: TokensHelper
) {
    companion object {
        private val DELAYS = arrayOf<Long>(10, 10, 10, 30, 60, 60, 60, 60) // Total 300 seconds
        private val LOG = Util.getLogger(PullRequestMergeBranchChecker::class.java)
    }

    private val myNormalExecutor = ExecutorServices.normalExecutorService
    private val myLowPrioExecutor = ExecutorServices.lowPriorityExecutorService
    private val myScheduledActions = ConcurrentHashMap<GitHubRepositoryInfo, Action>()

    fun schedule(info: GitHubRepositoryInfo, hookInfo: WebHookInfo, user: UserEx, prNumber: Int) {
        LOG.info("Scheduling check for repo ${info.id} PR #$prNumber")
        val action = Action(info, hookInfo, user, prNumber)
        myScheduledActions.remove(info)?.cancel() // Cancel previous action
        action.schedule(myNormalExecutor)
    }

    inner class Action(val info: GitHubRepositoryInfo, val hook: WebHookInfo, val user: UserEx, private val prNumber: Int) : Runnable {
        private var attempt: Int = 0
        @Volatile
        private var active = true
        private var future: ScheduledFuture<*>? = null
        private val runningInLowPriorityPool = AtomicBoolean(false)

        fun cancel() {
            active = false
            future?.cancel(false)
        }

        fun schedule(executor: ScheduledExecutorService): Boolean {
            if (attempt in DELAYS.indices) {
                if (myScheduledActions.putIfAbsent(info, this) == null) {
                    future = executor.schedule(this, DELAYS[attempt++], TimeUnit.SECONDS)
                    return true
                }
            } else {
                LOG.info("Gave up trying to fetch pull request merge branch details for repo ${info.id}")
                active = false
            }
            return false
        }

        override fun run() {
            if (!active) return
            if (runningInLowPriorityPool.get()) {
                // Still running or scheduled
                schedule(myNormalExecutor)
                return
            }
            runningInLowPriorityPool.set(true)
            myLowPrioExecutor.execute {
                try {
                    val retry = doCheck()
                    if (!retry) {
                        // Cleanup
                        myScheduledActions.remove(info, this)
                    } else {
                        // Retry
                        schedule(myNormalExecutor)
                    }
                } finally {
                    runningInLowPriorityPool.set(false)
                }
            }
        }

        /**
         * @return whether to retry later
         */
        private fun doCheck(): Boolean {
            val pubKey = GitHubWebHookListener.getPubKeyFromRequestPath(hook.callbackUrl)
            if (pubKey == null || pubKey.isBlank()) {
                // Old hook format
                LOG.warn("Callback url (${hook.callbackUrl}) of hook '${hook.url}' does not contains security check public key")
                return false
            }
            val authData = myAuthDataStorage.find(pubKey)
            if (authData == null) {
                LOG.warn("Cannot find auth data for hook '${hook.url}'")
                return false
            }

            val connection = getConnection(authData)
            if (connection == null) {
                LOG.warn("OAuth Connection for repository '${info.id}' not found")
                return false
            }

            val tokens = myTokensHelper.getExistingTokens(listOf(connection), user).entries.firstOrNull()?.value.orEmpty()
            if (tokens.isEmpty()) {
                LOG.warn("No OAuth tokens to access repository '${info.id}'")
                return false
            }

            val ghc = GitHubClientFactory.createGitHubClient(connection.parameters[GitHubConstants.GITHUB_URL_PARAM]!!)

            tokens@ for (token in tokens) {
                ghc.setOAuth2Token(token.accessToken)
                try {
                    val pr = GetPullRequestDetailsAction.doRun(info, ghc, myWebHooksManager, prNumber)
                    val sha = pr.mergeCommitSha
                    if (sha.isNullOrBlank()) return true
                    // Since there's merge commit sha there should be a branch ref also
                    LOG.info("For repo ${info.id} Pull Request #$prNumber merge commit sha is $sha")
                    onSucceed()
                    return false
                } catch (e: GitHubAccessException) {
                    LOG.info("Cannot check PR merge branch status for repository ${info.id}, cause ${e.message}")
                    @Suppress("NON_EXHAUSTIVE_WHEN")
                    when (e.type) {
                        GitHubAccessException.Type.InvalidCredentials -> continue@tokens
                        GitHubAccessException.Type.TokenScopeMismatch -> continue@tokens
                        GitHubAccessException.Type.InternalServerError -> return true
                    }
                }
            }
            return false
        }

        private fun onSucceed() {
            val httpId = info.id
            val sshId = info.server + ":" + info.owner + "/" + info.name
            try {
                val response = RestApiFacade.request("POST", user, "text/plain", "/app/rest/vcs-root-instances/commitHookNotification", "locator=type:jetbrains.git,or:(property:(name:url,value:$httpId,matchType:contains),property:(name:url,value:$sshId,matchType:contains))", emptyMap())
                if (response == null) {
                    LOG.warn("REST is unavailable, failed to start checking for changes")
                } else {
                    LOG.debug("For repo ${info.id} PR# $prNumber scheduling checking for changes via REST: $response")
                }
            } catch (e: RestApiFacade.InternalRestApiCallException) {
                LOG.warnAndDebugDetails("For repo ${info.id} PR# $prNumber scheduling checking for changes failed", e)
            }
        }
    }


    private fun getConnection(authData: AuthDataStorage.AuthData): OAuthConnectionDescriptor? {
        val info = authData.connection
        val project = myProjectManager.findProjectByExternalId(info.projectExternalId)
        if (project == null) {
            LOG.warn("OAuth Connection project '${info.projectExternalId}' not found")
            return null
        }
        val connection = myOAuthConnectionsManager.findConnectionById(project, info.id)
        if (connection == null) {
            LOG.warn("OAuth Connection with id '${info.id}' not found in project ${project.describe(true)} and it parents")
            return null
        }
        return connection
    }
}