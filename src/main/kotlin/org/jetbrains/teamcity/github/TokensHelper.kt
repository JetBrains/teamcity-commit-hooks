

package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.connections.ConnectionDescriptor
import jetbrains.buildServer.serverSide.connections.ProjectConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthToken
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import java.util.*

class TokensHelper(
        private val connectionsManager: ProjectConnectionsManager,
        private val storage: OAuthTokensStorage
) {

    companion object {
        fun getHooksAccessType(client: GitHubClientEx): Pair<HookAccessType, RepoAccessType>? {
            val scopes = client.tokenOAuthScopes?.map { it.lowercase() } ?: return null
            return getHooksAccessType(scopes)
        }

        fun getHooksAccessType(scopes: List<String>): Pair<HookAccessType, RepoAccessType> {
            if (scopes.contains("public_repo")) return HookAccessType.ADMIN to RepoAccessType.PublicOnly
            if (scopes.contains("repo")) return HookAccessType.ADMIN to RepoAccessType.All
            if (scopes.contains("admin:repo_hook")) return HookAccessType.ADMIN to RepoAccessType.All
            if (scopes.contains("write:repo_hook")) return HookAccessType.WRITE to RepoAccessType.All
            if (scopes.contains("read:repo_hook")) return HookAccessType.READ to RepoAccessType.All
            return HookAccessType.NO_ACCESS to RepoAccessType.All
        }

        fun isSuitableToken(token: OAuthToken): Boolean {
            if (token.isExpired) return false
            val pair = getHooksAccessType(token.scope.split(',', ' ').filter { it.isNotEmpty() })
            return isSuitableAccessType(pair.first)
        }

        fun isSuitableAccessType(accessType: HookAccessType): Boolean {
            return when (accessType) {
                HookAccessType.NO_ACCESS -> false
                HookAccessType.READ -> false
                HookAccessType.WRITE -> true // Though write is not enough for 'delete' action, we can disable webhook
                HookAccessType.ADMIN -> true
            }
        }
    }

    fun getConnections(project: SProject, server: String): List<OAuthConnectionDescriptor> {
        return Util.findConnections(connectionsManager, project, server)
    }

    fun getExistingTokens(project: SProject, connections: Collection<OAuthConnectionDescriptor>, user: SUser): Map<OAuthConnectionDescriptor, List<OAuthToken>> {
        return connections.map {
            it to storage.getUserTokens(it.id, user, project, true).filter { token -> isSuitableToken(token) && !myIncorrectTokens.contains(token) }
        }.filter { it.second.isNotEmpty() }.toMap()
    }


    enum class HookAccessType : Comparable<HookAccessType> {
        NO_ACCESS, // nothing
        READ, // read + ping
        WRITE, // READ + add + modify
        ADMIN // == WRITE + delete
    }

    enum class RepoAccessType {
        NotSpecified,
        PublicOnly,
        All
    }

    private val myIncorrectTokens = HashSet<OAuthToken>()

    fun markTokenIncorrect(token: OAuthToken) {
        myIncorrectTokens.add(token)
    }
}