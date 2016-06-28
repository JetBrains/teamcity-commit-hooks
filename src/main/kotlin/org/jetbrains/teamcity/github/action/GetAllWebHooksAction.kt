package org.jetbrains.teamcity.github.action

import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.GitHubAccessException
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.TokensHelper
import org.jetbrains.teamcity.github.callbackUrl

/**
 * Fetches all webhooks points to this server for given repository
 */
object GetAllWebHooksAction : Action<HooksGetOperationResult, ActionContext> {
    @Throws(GitHubAccessException::class)
    override fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext): HooksGetOperationResult {
        val service = RepositoryService(client)
        val repo = info.getRepositoryId()
        try {
            val hooks = service.getHooks(repo)
            val filtered = hooks.filter {
                val url = it.callbackUrl

                "web" == it.name
                && url != null
                && url.startsWith(context.getCallbackUrl())
                && "json" == it.config["content_type"]
            }
            context.updateHooks(info.server, repo, filtered)
        } catch(e: RequestException) {
            when (e.status) {
                401 -> {
                    throw GitHubAccessException(GitHubAccessException.Type.InvalidCredentials)
                }
                403, 404 -> {
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
        return HooksGetOperationResult.Ok
    }
}