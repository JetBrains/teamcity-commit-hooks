package org.jetbrains.teamcity.github.action

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.GitHubAccessException
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.TokensHelper
import org.jetbrains.teamcity.github.callbackUrl
import java.net.HttpURLConnection.*

/**
 * Fetches all webhooks points to this server for given repository
 */
object GetAllWebHooksAction {

    private val LOG = Logger.getInstance(GetAllWebHooksAction::class.java.name)

    @Throws(GitHubAccessException::class)
    fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, context: ActionContext): HooksGetOperationResult {
        val service = RepositoryService(client)
        val repo = info.getRepositoryId()
        try {
            LOG.debug("Loading webhooks for repository $info")
            val hooks = service.getHooks(repo)
            val filtered = hooks.filter {
                val url = it.callbackUrl

                "web" == it.name
                && url != null
                && url.startsWith(context.getCallbackUrl())
                && "json" == it.config["content_type"]
            }
            val active = filtered.filter { it.isActive }
            if (filtered.size > 0) {
                LOG.debug("Found ${filtered.size} webhook for repository $info; ${active.size} - active; ${hooks.size - filtered.size} - other hooks")
            } else {
                LOG.debug("No webhooks found for repository $info")
            }
            if (active.size > 1) {
                LOG.info("More than one (${active.size} active webhooks found for repository $info")
            }
            context.updateHooks(info.server, repo, filtered)
        } catch(e: RequestException) {
            LOG.warnAndDebugDetails("Failed to load webhooks for repository $info: ${e.status}", e)
            when (e.status) {
                HTTP_UNAUTHORIZED -> {
                    throw GitHubAccessException(GitHubAccessException.Type.InvalidCredentials)
                }
                HTTP_NOT_FOUND, HTTP_FORBIDDEN -> {
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
                HTTP_INTERNAL_ERROR -> {
                    // GH error, try later
                    // TODO: Throw proper exception
                }
            }
            throw e
        }
        return HooksGetOperationResult.Ok
    }
}