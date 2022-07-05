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

package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.TeamCityProperties
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
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
        private val myProjectManager: ProjectManager,
        private val myWebHooksManager: WebHooksManager,
        private val myOAuthConnectionsManager: OAuthConnectionsManager,
        private val myTokensHelper: TokensHelper
) : SetupFromUrlExtension {
    companion object {
        private val LOG = Util.getLogger(SetupFromUrlGitHubWebhooksExtension::class.java)
    }

    override fun afterCreate(buildType: SBuildType, user: SUser) {
        val gitRoots = HashSet<SVcsRoot>()
        Util.findSuitableRoots(listOf(buildType)) {
            gitRoots.add(it)
        }
        if (gitRoots.isEmpty()) return
        val split = GitHubWebHookSuggestion.splitRoots(gitRoots)

        val filtered = split.entrySet()
                .filterKnownServers(myOAuthConnectionsManager)
                .filter {
                    TeamCityProperties.getBoolean("teamcity.commitHooks.github.autoInstall") || !it.key.server.equals("github.com", true)
                }
                .map { it.key to it.value }.toMap()

        if (filtered.isEmpty()) return

        val affectedRootsCount = filtered.values.sumOf { it.size }
        LOG.info("Will try to install GitHub webhooks to ${filtered.size} ${filtered.size.pluralize("repository")} (used in $affectedRootsCount vcs ${affectedRootsCount.pluralize("root")})")

        val infos = filtered.keys

        val project = buildType.project

        // Load webhooks for repository and install new one if there's no good webhooks there
        infos@for (info in infos) {
            val connections = myTokensHelper.getConnections(project, info.server)
                    .plus(myWebHooksManager.authDataStorage.findAllForRepository(info).mapNotNull { getConnection(it) })
                    // Like #toSet with custom #equals:
                    .map { (it.project to it.id) to it }.toMap().values
            val connectionToTokensMap = myTokensHelper.getExistingTokens(project, connections, user)
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

    private fun getConnection(authData: AuthDataStorage.AuthData): OAuthConnectionDescriptor? {
        val info = authData.connection
        val project = myProjectManager.findProjectByExternalId(info.projectExternalId) ?: return null
        return myOAuthConnectionsManager.findConnectionById(project, info.id)
    }
}