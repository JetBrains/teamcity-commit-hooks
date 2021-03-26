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

package org.jetbrains.teamcity.github.action

import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.*
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_NOT_FOUND

/**
 * Fetches all webhooks points to this server for given repository
 */
object GetAllWebHooksAction {

    private val LOG = Util.getLogger(GetAllWebHooksAction::class.java)

    @Throws(GitHubAccessException::class)
    fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, context: ActionContext): Map<RepositoryHook, WebHooksStorage.HookInfo> {
        val service = RepositoryService(client)
        val repo = info.getRepositoryId()
        try {
            LOG.debug("Loading webhooks for repository ${info.id}")
            val hooks = service.getHooks(repo)
            val filtered = hooks.filter {
                val url = it.callbackUrl

                "web" == it.name
                && url != null
                && url.startsWith(context.getCallbackUrl())
                && "json" == it.config["content_type"]
            }
            val active = filtered.filter { it.isActive }
            if (filtered.isNotEmpty()) {
                LOG.debug("Found ${filtered.size} webhook${filtered.size.s} for repository ${info.id}; ${active.size} - active; ${hooks.size - filtered.size} - other")
            } else {
                LOG.debug("No webhooks found for repository ${info.id}")
            }
            if (active.size > 1) {
                LOG.info("More than one (${active.size} active webhooks found for repository ${info.id}")
            }
            return context.updateHooks(info.server, repo, filtered)
        } catch(e: RequestException) {
            LOG.warnAndDebugDetails("Failed to load webhooks for repository ${info.id}: ${e.status}", e)
            context.handleCommonErrors(e)
            when (e.status) {
                HTTP_NOT_FOUND, HTTP_FORBIDDEN -> {
                    // No access
                    // Probably token does not have permissions
                    val scopes = client.tokenOAuthScopes?.map { it.toLowerCase() } ?: throw GitHubAccessException(GitHubAccessException.Type.NoAccess) // Weird. No header?
                    when (TokensHelper.getHooksAccessType(scopes).first) {
                        TokensHelper.HookAccessType.NO_ACCESS -> throw GitHubAccessException(GitHubAccessException.Type.TokenScopeMismatch)
                        TokensHelper.HookAccessType.READ -> throw GitHubAccessException(GitHubAccessException.Type.TokenScopeMismatch)
                        TokensHelper.HookAccessType.WRITE, TokensHelper.HookAccessType.ADMIN -> throw GitHubAccessException(GitHubAccessException.Type.UserHaveNoAccess)
                    }
                }
            }
            throw e
        }
    }
}