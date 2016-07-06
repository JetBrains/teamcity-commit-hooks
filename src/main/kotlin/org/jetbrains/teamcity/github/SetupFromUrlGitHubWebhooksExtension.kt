package org.jetbrains.teamcity.github

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.auth.SecurityContext
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientFactory
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.serverSide.setupFromUrl.SetupFromUrlExtension
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.vcs.SVcsRoot
import org.jetbrains.teamcity.github.action.HookAddOperationResult
import java.util.*

class SetupFromUrlGitHubWebhooksExtension(
        private val myWebHooksManager: WebHooksManager,
        private val myOAuthConnectionsManager: OAuthConnectionsManager,
        private val myTokensHelper: TokensHelper,
        private val mySecurityContext: SecurityContext
) : SetupFromUrlExtension {
    companion object {
        private val LOG = Logger.getInstance(SetupFromUrlGitHubWebhooksExtension::class.java.name)
    }

    override fun afterCreate(buildType: SBuildType) {
        val gitRoots = HashSet<SVcsRoot>()
        Util.findSuitableRoots(listOf(buildType)) {
            gitRoots.add(it)
        }
        if (gitRoots.isEmpty()) return
        val split = GitHubWebHookAvailableHealthReport.splitRoots(gitRoots)

        val filtered = split.entrySet()
                .filter {
                    myWebHooksManager.storage.getHooks(it.key).isEmpty()
                }
                .filter {
                    // Filter by known servers
                    it.key.server == "github.com" || GitHubWebHookAvailableHealthReport.getProjects(it.value).any { project -> Util.findConnections(myOAuthConnectionsManager, project, it.key.server).isNotEmpty() }
                }.map { it.key to it.value }.toMap()

        if (filtered.isEmpty()) return

        val affectedRootsCount = filtered.values.sumBy { it.size }
        LOG.info("Will try to install hooks to ${filtered.size} ${filtered.size.pluralize("repository")} (used in $affectedRootsCount vcs ${affectedRootsCount.pluralize("root")})")

        val authorityHolder = mySecurityContext.authorityHolder

        if (authorityHolder !is SUser) {
            LOG.warn("AuthorityHolder is not a user, cannot install webhook${filtered.size.s}")
            // TODO: Somehow handle such case
            return
        }

        val user: SUser = authorityHolder

        infos@for (info in filtered.keys) {
            val connections = myTokensHelper.getConnections(buildType.project, info.server)
            val connectionToTokensMap = myTokensHelper.getExistingTokens(connections, user)
            if (connectionToTokensMap.isEmpty()) {
                LOG.info("Cannot automatically add webhook for '$info' repository: no tokens for user '${user.describe(false)}")
            }
            for ((connection, tokens) in connectionToTokensMap) {
                val ghc: GitHubClientEx = GitHubClientFactory.createGitHubClient(connection.parameters[GitHubConstants.GITHUB_URL_PARAM]!!)
                for (token in tokens) {
                    ghc.setOAuth2Token(token.accessToken)
                    LOG.debug("Trying with token from GH user '${token.oauthLogin}', connection is ${connection.id}")
                    try {
                        val result = myWebHooksManager.doRegisterWebHook(info, ghc, user, connection)
                        when (result) {
                            HookAddOperationResult.Created -> LOG.info("Added webhook for '$info'")
                            HookAddOperationResult.AlreadyExists -> LOG.info("Webhook for '$info' was already there")
                        }
                        continue@infos
                    } catch(e: Exception) {
                        LOG.warnAndDebugDetails("Failed to install webhook for repository '$info' using token of GH user '${token.oauthLogin}'", e)
                    }
                }
            }
        }
    }
}