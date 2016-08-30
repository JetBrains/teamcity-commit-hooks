package org.jetbrains.teamcity.github

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.TeamCityProperties
import jetbrains.buildServer.serverSide.executors.ExecutorServices
import jetbrains.buildServer.serverSide.healthStatus.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientFactory
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.users.UserModelEx
import jetbrains.buildServer.util.StringUtil
import jetbrains.buildServer.vcs.SVcsRoot
import org.jetbrains.teamcity.github.action.GetAllWebHooksAction
import org.jetbrains.teamcity.github.action.TestWebHookAction
import org.jetbrains.teamcity.github.controllers.GitHubWebHookListener
import org.jetbrains.teamcity.github.controllers.Status
import org.jetbrains.teamcity.github.controllers.good
import java.util.*
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class WebhookPeriodicalChecker(
        private val myProjectManager: ProjectManager,
        private val myOAuthConnectionsManager: OAuthConnectionsManager,
        private val myAuthDataStorage: AuthDataStorage,
        private val myWebHooksStorage: WebHooksStorage,
        private val myUserModel: UserModelEx,
        private val myWebHooksManager: WebHooksManager,
        private val myExecutorServices: ExecutorServices,
        private val myOAuthTokensStorage: OAuthTokensStorage,
        private val myTokensHelper: TokensHelper
) : HealthStatusReport() {


    private var myTask: ScheduledFuture<*>? = null

    companion object {
        private val LOG = Logger.getInstance(WebhookPeriodicalChecker::class.java.name)
        val TYPE = "GitHubWebHookProblem"
        val CATEGORY: ItemCategory = ItemCategory("GitHubWebHookProblem", "GitHub webhook problem", ItemSeverity.WARN)

        val CHECK_INTERVAL_PROPERTY = "teamcity.githubWebhooks.checkInterval.min"
    }

    override fun getType(): String = TYPE

    override fun getDisplayName(): String = "Reports problems detected with GitHub webhooks"

    override fun getCategories(): MutableCollection<ItemCategory> = arrayListOf(CATEGORY)

    fun init() {
        myTask = myExecutorServices.normalExecutorService.scheduleWithFixedDelay({ doCheck() }, 3, TeamCityProperties.getLong(CHECK_INTERVAL_PROPERTY, 15), TimeUnit.MINUTES)
    }

    fun destroy() {
        myTask?.cancel(false)
    }

    override fun canReportItemsFor(scope: HealthStatusScope): Boolean {
        if (!scope.isItemWithSeverityAccepted(CATEGORY.severity)) return false
        if (myIncorrectHooks.size() == 0L && !myWebHooksStorage.isHasIncorrectHooks()) return false
        var found = false
        Util.findSuitableRoots(scope) { found = true; false }
        return found
    }

    override fun report(scope: HealthStatusScope, resultConsumer: HealthStatusItemConsumer) {
        if (!canReportItemsFor(scope)) return
        val gitRoots = HashSet<SVcsRoot>()
        Util.findSuitableRoots(scope, { gitRoots.add(it); true })

        val incorrectHooks = myWebHooksStorage.getIncorrectHooks()
        val incorrectHooksInfos = incorrectHooks.map { it.first }.toHashSet()

        val split = GitHubWebHookSuggestion.splitRoots(gitRoots)

        val myIncorrectHooksKeys = myIncorrectHooks.asMap().keys.toHashSet()
        val filtered = split.entrySet()
                .filter { it.key in myIncorrectHooksKeys || it.key in incorrectHooksInfos }
                .map { it.key to it.value }.toMap()

        for ((info, roots) in filtered) {
            val hook = incorrectHooks.firstOrNull { it.first == info }?.second ?: myWebHooksStorage.getHooks(info).firstOrNull()
            if (hook == null) {
                // Completely removed, even from our storage. Let's forget about it
                myIncorrectHooks.invalidate(info)
                continue
            }
            if (myWebHooksStorage.getHooks(info).any { it.status.good }) {
                // Installed new hook or fixed previous one
                myIncorrectHooks.invalidate(info)
                continue
            }
            val id = info.server + "#" + hook.id

            val reason = myIncorrectHooks.getIfPresent(info) ?: "Unknown reason"

            val item = HealthStatusItem("GitHubWebHook.I.$id", CATEGORY, mapOf(
                    "GitHubInfo" to info,
                    "HookInfo" to hook,
                    "Projects" to Util.getProjects(roots),
                    "Reason" to reason
            ))

            for (it in roots) {
                resultConsumer.consumeForVcsRoot(it, item)
                it.usagesInConfigurations.forEach { resultConsumer.consumeForBuildType(it, item) }
            }
        }
    }

    fun doCheck() {
        LOG.info("Periodical GitHub Webhooks checker started")
        val ignoredServers = ArrayList<String>()

        val toCheck = ArrayDeque(myWebHooksStorage.getAll())
        val toPing = ArrayDeque<Triple<GitHubRepositoryInfo, Pair<GitHubClientEx, String>, WebHooksStorage.HookInfo>>()
        if (toCheck.isEmpty()) {
            LOG.debug("No configured webhooks found")
        } else {
            LOG.debug("Will check ${toCheck.size} ${StringUtil.pluralize("webhook", toCheck.size)}")
        }
        while (toCheck.isNotEmpty()) {
            val pair = toCheck.pop()
            val (info, hook) = pair
            val callbackUrl = hook.callbackUrl
            val pubKey = GitHubWebHookListener.getPubKeyFromRequestPath(callbackUrl)
            if (pubKey == null || pubKey.isBlank()) {
                // Old hook format
                LOG.warn("Callback url (${hook.callbackUrl}) of hook '${hook.url}' does not contains security check public key")
                myWebHooksStorage.delete(hook)
                continue
            }
            val authData = myAuthDataStorage.find(pubKey)
            if (authData == null) {
                LOG.warn("Cannot find auth data for hook '${hook.url}'")
                report(info, hook, "Webhook callback url is incorrect or internal storage was corrupted")
                continue
            }

            val connection = getConnection(authData)
            if (connection == null) {
                LOG.warn("OAuth Connection for repository '$info' not found")
                report(info, hook, "OAuth connection used to install webhook is unavailable", Status.NO_INFO)
                continue
            }

            val user = myUserModel.findUserById(authData.userId)
            if (user == null) {
                LOG.warn("TeamCity user '${authData.userId}' which created webhook for repository '$info' no longer exists")
                report(info, hook, "TeamCity user '${authData.userId}' which created webhook no longer exists", Status.NO_INFO)
                continue
            }

            val tokens = myTokensHelper.getExistingTokens(listOf(connection), user).entries.firstOrNull()?.value.orEmpty()
            if (tokens.isEmpty()) {
                LOG.warn("No OAuth tokens to access repository '$info'")
                report(info, hook, "No OAuth tokens found to access repository", Status.NO_INFO)
                continue
            }

            if (ignoredServers.contains(info.server)) {
                // Server ignored for some time due to error on github
                continue
            }

            val ghc = GitHubClientFactory.createGitHubClient(connection.parameters[GitHubConstants.GITHUB_URL_PARAM]!!)

            var success = false
            var retry = false
            tokens@for (token in tokens) {
                ghc.setOAuth2Token(token.accessToken)
                try {
                    LOG.debug("Checking webhook status for '$info' repository")
                    // GetAllWebHooksAction will automatically update statuses in all hooks for repository if succeed
                    val loaded = GetAllWebHooksAction.doRun(info, ghc, myWebHooksManager)
                    LOG.debug("Successfully fetched webhooks for '$info' repository from GitHub server")

                    // Since we've loaded all hooks for repository 'info' it's safe to remove others for same repo from queue
                    toCheck.removeAll { it.first == info }

                    // Remove hooks removed on remote server from storages.
                    val removed = myWebHooksStorage.getHooks(info).filter { it.status == Status.MISSING }
                    if (!removed.isEmpty()) {
                        LOG.info("$removed ${removed.size.pluralize("webhook")} missing on remote server and would be removed locally")
                        val pubKeysToRemove = removed.map { GitHubWebHookListener.getPubKeyFromRequestPath(it.callbackUrl) }.filterNotNull()
                        myWebHooksStorage.delete(info) {it in removed}
                        myAuthDataStorage.findAllForRepository(info).filter { it.public in pubKeysToRemove }.forEach { myAuthDataStorage.remove(it) }
                    }

                    // Update info for all loaded hooks
                    for ((key, hook) in loaded) {
                        val lastResponse = key.lastResponse
                        if (lastResponse == null) {
                            LOG.debug("No last response info for hook ${key.url!!}")
                            // Lets ask GH to send us ping request, so next time there would be some 'lastResponse'
                            toPing.add(Triple(info, ghc to token.accessToken, hook))
                            continue
                        }
                        when (lastResponse.code) {
                            in 200..299 -> {
                                LOG.debug("Last response is OK")
                                hook.status = if (!key.isActive) Status.DISABLED else Status.OK
                            }
                            in 400..599 -> {
                                val reason = "Last payload delivery failed: (${lastResponse.code}) ${lastResponse.message}"
                                LOG.debug(reason)
                                report(info, hook, reason, Status.PAYLOAD_DELIVERY_FAILED)
                            }
                            else -> {
                                val reason = "Unexpected payload delivery response: (${lastResponse.code}) ${lastResponse.message}"
                                LOG.debug(reason)
                                report(info, hook, reason, Status.PAYLOAD_DELIVERY_FAILED)
                            }
                        }
                    }
                    success = true
                    break@tokens
                } catch(e: GitHubAccessException) {
                    when (e.type) {
                        GitHubAccessException.Type.InvalidCredentials -> {
                            LOG.warn("Removing incorrect (outdated) token (user:${token.oauthLogin}, scope:${token.scope})")
                            myOAuthTokensStorage.removeToken(connection.id, token.accessToken)
                            retry = true
                        }
                        GitHubAccessException.Type.TokenScopeMismatch -> {
                            LOG.warn("Token (user:${token.oauthLogin}, scope:${token.scope}) scope is not enough to check hook status")
                            myTokensHelper.markTokenIncorrect(token)
                            retry = true
                        }
                        GitHubAccessException.Type.UserHaveNoAccess -> {
                            LOG.warn("User (TC:${user.describe(false)}, GH:${token.oauthLogin}) have no access to repository $info, cannot check hook status")
                            if (tokens.map { it.oauthLogin }.distinct().size == 1) {
                                report(info, hook, "User (TC:${user.describe(false)}, GH:${token.oauthLogin}) installed webhook have no longer access to repository", Status.NO_INFO)
                            } else {
                                // TODO: ??? Seems TC user has many tokens with different GH users
                            }
                            retry = false
                        }
                        GitHubAccessException.Type.NoAccess -> {
                            LOG.warn("No access to repository $info for unknown reason, cannot check hook status")
                            retry = false
                        }
                        GitHubAccessException.Type.InternalServerError -> {
                            LOG.info("Cannot check hooks status for repository $info: Error on GitHub side. Will try later")
                            ignoredServers.add(info.server)
                            break@tokens
                        }
                    }
                }
            }

            if (!success && retry) {
                toCheck.add(pair)
            }

            checkQuotaLimit(ghc, ignoredServers, info)
        }

        for ((info, pair, hi) in toPing) {
            if (ignoredServers.contains(info.server)) continue
            val ghc = pair.first
            ghc.setOAuth2Token(pair.second)
            try {
                TestWebHookAction.doRun(info, ghc, myWebHooksManager, hi)
            } catch(e: GitHubAccessException) {
                // Ignore
            }
            checkQuotaLimit(ghc, ignoredServers, info)
        }


        LOG.info("Periodical GitHub Webhooks checker finished")
    }

    private fun checkQuotaLimit(ghc: GitHubClientEx, ignoredServers: ArrayList<String>, info: GitHubRepositoryInfo) {
        if (ghc.remainingRequests in 0..10) {
            LOG.debug("Reaching request quota limit (${ghc.remainingRequests}/${ghc.requestLimit}) for server '${info.server}', will try checking it's webhooks later")
            ignoredServers.add(info.server)
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

    // TODO: Should mention HookInfo
    private val myIncorrectHooks: Cache<GitHubRepositoryInfo, String> = CacheBuilder.newBuilder().expireAfterWrite(120, TimeUnit.MINUTES).build()

    private fun report(info: GitHubRepositoryInfo, hook: WebHooksStorage.HookInfo, reason: String, status: Status = Status.INCORRECT) {
        myIncorrectHooks.put(info, reason)
        hook.status = status
    }

}