

package org.jetbrains.teamcity.github.action

import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.*

object TestWebHookAction {
    private val LOG = Util.getLogger(TestWebHookAction::class.java)

    @Throws(GitHubAccessException::class)
    fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, context: ActionContext, hook: WebHookInfo) {
        val service = RepositoryService(client)
        try {
            service.testHook(info.getRepositoryId(), hook.id.toInt())
        } catch(e: RequestException) {
            LOG.warnAndDebugDetails("Failed to test (redeliver latest 'push' event) webhook for repository ${info.id}: ${e.status}", e)
            context.handleCommonErrors(e)
            when (e.status) {
                403, 404 -> {
                    // ? No access
                    val pair = TokensHelper.getHooksAccessType(client) ?: throw GitHubAccessException(GitHubAccessException.Type.NoAccess)// Weird. No header?
                    if (pair.first <= TokensHelper.HookAccessType.READ) throw GitHubAccessException(GitHubAccessException.Type.TokenScopeMismatch)
                    throw GitHubAccessException(GitHubAccessException.Type.UserHaveNoAccess)
                }
            }
            throw e
        }
    }
}