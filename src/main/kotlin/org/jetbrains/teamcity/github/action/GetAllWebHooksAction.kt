package org.jetbrains.teamcity.github.action

import com.intellij.openapi.diagnostic.Logger
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

    private val LOG = Logger.getInstance(GetAllWebHooksAction::class.java.name)

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
            if (filtered.size > 0) {
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
    }
}