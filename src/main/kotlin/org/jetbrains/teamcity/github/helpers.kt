package org.jetbrains.teamcity.github

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.util.StringUtil
import jetbrains.buildServer.vcs.SVcsRoot
import org.eclipse.egit.github.core.RepositoryHook
import org.jetbrains.teamcity.github.controllers.Status


val RepositoryHook.callbackUrl: String?
    get() = this.config["url"]


val Int.s: String get() = if (this != 1) "s" else ""
fun Int.pluralize(text: String): String = StringUtil.pluralize(text, this)


fun RepositoryHook.getStatus(): Status {
    if (this.lastResponse != null) {
        if (this.lastResponse.code in 200..299) {
            if (!this.isActive) return Status.DISABLED
            return Status.OK
        } else {
            return Status.PAYLOAD_DELIVERY_FAILED
        }
    } else {
        if (!this.isActive) return Status.DISABLED
        return Status.WAITING_FOR_SERVER_RESPONSE
    }
}


fun Iterable<Map.Entry<GitHubRepositoryInfo, Set<SVcsRoot>>>.filterKnownServers(connectionsManager: OAuthConnectionsManager): List<Map.Entry<GitHubRepositoryInfo, Set<SVcsRoot>>> {
    val cache: Cache<SProject, List<OAuthConnectionDescriptor>> = CacheBuilder.newBuilder().build()
    return this.filter { entry ->
        Util.getProjects(entry.value).any { project ->
            val connections = cache[project, { connectionsManager.getAvailableConnections(project).filterNotNull() }];
            connections.filter { Util.isConnectionToServer(it, entry.key.server) }.isNotEmpty()
        }
    }
}