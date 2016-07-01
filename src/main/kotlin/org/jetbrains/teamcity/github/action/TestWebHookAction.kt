package org.jetbrains.teamcity.github.action

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.GitHubAccessException
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.TokensHelper

object TestWebHookAction : Action<HookTestOperationResult, ActionContext> {
    private val LOG = Logger.getInstance(TestWebHookAction::class.java.name)


    @Throws(GitHubAccessException::class)
    override fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext): HookTestOperationResult {
        val service = RepositoryService(client)
        val hook = context.storage.getHook(info) ?: return HookTestOperationResult.NotFound
        try {
            service.testHook(info.getRepositoryId(), hook.id.toInt())
        } catch(e: RequestException) {
            LOG.warnAndDebugDetails("Failed to test(ping) webhook for repository $info: ${e.status}", e)
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
        return HookTestOperationResult.Ok
    }
}