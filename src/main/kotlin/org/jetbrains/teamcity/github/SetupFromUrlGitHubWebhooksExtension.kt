package org.jetbrains.teamcity.github

import jetbrains.buildServer.log.Loggers
import jetbrains.buildServer.serverSide.SBuildType
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
        private val myTokensHelper: TokensHelper
) : SetupFromUrlExtension {
    companion object {
        private val LOG = Loggers.SERVER
    }

    override fun afterCreate(buildType: SBuildType, user: SUser) {
        val gitRoots = HashSet<SVcsRoot>()
        Util.findSuitableRoots(listOf(buildType)) {
            gitRoots.add(it)
        }
        if (gitRoots.isEmpty()) return
        val split = GitHubWebHookSuggestion.splitRoots(gitRoots)

        val filtered = split.entrySet()
                .filter {
                    myWebHooksManager.storage.getHooks(it.key).isEmpty()
                }
                .filterKnownServers(myOAuthConnectionsManager)
                .map { it.key to it.value }.toMap()

        if (filtered.isEmpty()) return

        val affectedRootsCount = filtered.values.sumBy { it.size }
        LOG.info("Will try to install GitHub webhooks to ${filtered.size} ${filtered.size.pluralize("repository")} (used in $affectedRootsCount vcs ${affectedRootsCount.pluralize("root")})")

        infos@for (info in filtered.keys) {
            val connections = myTokensHelper.getConnections(buildType.project, info.server)
            val connectionToTokensMap = myTokensHelper.getExistingTokens(connections, user)
            if (connectionToTokensMap.isEmpty()) {
                LOG.warn("Could not install GitHub webhook for '$info' repository: no tokens for user '${user.describe(false)}")
            }
            for ((connection, tokens) in connectionToTokensMap) {
                val ghc: GitHubClientEx = GitHubClientFactory.createGitHubClient(connection.parameters[GitHubConstants.GITHUB_URL_PARAM]!!)
                for (token in tokens) {
                    ghc.setOAuth2Token(token.accessToken)
                    LOG.debug("Trying to install GitHub webhook with token from the GitHub user '${token.oauthLogin}', OAuth connection is ${connection.id}")
                    try {
                        val result = myWebHooksManager.doInstallWebHook(info, ghc, user, connection)
                        when (result.first) {
                            HookAddOperationResult.Created -> LOG.info("Successfully installed GitHub webhook for '$info'")
                            HookAddOperationResult.AlreadyExists -> LOG.info("Skipped installation of the GitHub webhook for '$info' because it was already there")
                        }
                        // TODO: Show message in UI once webhook successfully installed
                        continue@infos
                    } catch(e: Exception) {
                        LOG.warnAndDebugDetails("Failed to install GitHub webhook for the repository '$info' using token of the GitHub user '${token.oauthLogin}'", e)
                    }
                }
            }
        }
    }
}