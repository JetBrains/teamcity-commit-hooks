package org.jetbrains.teamcity.github.action

import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import org.eclipse.egit.github.core.PullRequestEx
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.PullRequestServiceEx
import org.jetbrains.teamcity.github.GitHubAccessException
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.TokensHelper
import org.jetbrains.teamcity.github.Util
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_NOT_FOUND

/**
 * Fetches pull-request data for given repository and PR number
 */
object GetPullRequestDetailsAction {

    private val LOG = Util.getLogger(GetPullRequestDetailsAction::class.java)

    @Throws(GitHubAccessException::class)
    fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, context: ActionContext, number: Int): PullRequestEx {
        val service = PullRequestServiceEx(client)
        val repo = info.getRepositoryId()
        try {
            LOG.debug("Loading pull request #$number data for repository ${info.id}")
            return service.getPullRequestEx(repo, number)
        } catch (e: RequestException) {
            LOG.warnAndDebugDetails("Failed loading pull request #$number data for repository ${info.id}: ${e.status}", e)
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