package org.jetbrains.teamcity.github.action

import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.jetbrains.teamcity.github.AuthDataStorage
import org.jetbrains.teamcity.github.GitHubAccessException
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.TokensHelper

object CreateWebHookAction {
    @Throws(GitHubAccessException::class)
    fun doRun(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser, context: ActionContext): Pair<HookAddOperationResult, AuthDataStorage.AuthData?> {
        val repo = info.getRepositoryId()
        val service = RepositoryService(client)

        if (context.getHook(info) != null) {
            // TODO: Check AuthData
            return HookAddOperationResult.AlreadyExists to null
        }

        // First, check for already existing hooks, otherwise Github will answer with code 422
        // If we cannot get hooks, we cannot add new one
        // TODO: Consider handling GitHubAccessException
        GetAllWebHooksAction.doRun(info, client, user, context)

        if (context.getHook(info) != null) {
            // TODO: Check AuthData
            return HookAddOperationResult.AlreadyExists to null
        }

        val authData = context.authDataStorage.create(user, info, false)

        val hook = RepositoryHook().setActive(true).setName("web").setConfig(mapOf(
                "url" to context.getCallbackUrl(authData),
                "content_type" to "json",
                "secret" to authData.secret
                // TODO: Investigate ssl option
        ))

        val created: RepositoryHook
        try {
            created = service.createHook(repo, hook)
        } catch(e: RequestException) {
            when (e.status) {
                401 -> throw GitHubAccessException(GitHubAccessException.Type.InvalidCredentials)
                403, 404 -> {
                    // ? No access
                    val pair = TokensHelper.getHooksAccessType(client) ?: throw GitHubAccessException(GitHubAccessException.Type.NoAccess)// Weird. No header?
                    if (pair.first <= TokensHelper.HookAccessType.READ) throw GitHubAccessException(GitHubAccessException.Type.TokenScopeMismatch)
                    throw GitHubAccessException(GitHubAccessException.Type.UserHaveNoAccess)
                }
                422 -> {
                    if (e.error.errors.any { it.resource.equals("hook", true) && it.message.contains("already exists") }) {
                        // Already exists
                        // TODO: Handle AuthData
                        // TODO: Remove existing hook if there no auth data know here.
                        return HookAddOperationResult.AlreadyExists to null
                    }
                }
            }
            throw e
        }

        context.addHook(created, info.server, repo)

        context.authDataStorage.store(authData)
        return HookAddOperationResult.Created to authData

    }
}