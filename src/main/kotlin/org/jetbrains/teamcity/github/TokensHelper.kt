package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthToken
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import org.springframework.beans.factory.annotation.Autowired
import java.util.*

class TokensHelper {

    @Autowired
    lateinit var connectionsManager: OAuthConnectionsManager
    @Autowired
    lateinit var storage: OAuthTokensStorage

    companion object {
        fun getHooksAccessType(client: GitHubClientEx): Pair<HookAccessType, RepoAccessType>? {
            val scopes = client.tokenOAuthScopes?.map { it.toLowerCase() } ?: return null
            return getHooksAccessType(scopes);
        }

        fun getHooksAccessType(scopes: List<String>): Pair<HookAccessType, RepoAccessType> {
            if (scopes.contains("public_repo")) return HookAccessType.ADMIN to RepoAccessType.PublicOnly
            if (scopes.contains("repo")) return HookAccessType.ADMIN to RepoAccessType.All
            if (scopes.contains("admin:repo_hook")) return HookAccessType.ADMIN to RepoAccessType.All
            if (scopes.contains("write:repo_hook")) return HookAccessType.WRITE to RepoAccessType.All
            if (scopes.contains("read:repo_hook")) return HookAccessType.READ to RepoAccessType.All
            return HookAccessType.NO_ACCESS to RepoAccessType.All
        }

        fun getRootsAccessType(scopes: List<String>): RepoAccessType {
            if (scopes.contains("public_repo")) return RepoAccessType.PublicOnly
            if (scopes.contains("repo")) return RepoAccessType.All
            return RepoAccessType.NotSpecified
        }

        fun isSuitableToken(token: OAuthToken): Boolean {
            if (token.isExpired) return false
            val pair = getHooksAccessType(token.scope.split(',', ' ').filter { it.isNotEmpty() })
            return isSuitableAccessType(pair.first)
        }

        fun isSuitableAccessType(accessType: HookAccessType): Boolean {
            when (accessType) {
                HookAccessType.NO_ACCESS -> return false
                HookAccessType.READ -> return false

                // TODO: Write is not enough for 'delete' action
                HookAccessType.WRITE -> return true
                HookAccessType.ADMIN -> return true
            }
        }
    }

    fun getConnections(project: SProject, server: String): List<OAuthConnectionDescriptor> {
        return Util.findConnections(connectionsManager, project, server);
    }

    fun getExistingTokens(connections: List<OAuthConnectionDescriptor>, user: SUser): Map<OAuthConnectionDescriptor, List<OAuthToken>> {
        return connections.map {
            it to storage.getUserTokens(it.id, user).filter { isSuitableToken(it) && !myIncorrectTokens.contains(it) }
        }.filter { it.second.isNotEmpty() }.toMap()
    }


    enum class HookAccessType() : Comparable<HookAccessType> {
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