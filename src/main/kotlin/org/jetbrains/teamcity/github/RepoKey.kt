package org.jetbrains.teamcity.github

import org.eclipse.egit.github.core.RepositoryId
import java.util.*

data class RepoKey internal constructor(val server: String, val owner: String, val name: String) {

    private val hashCode = Objects.hash(server.lowercase(), owner.lowercase(), name.lowercase())

    constructor(server: String, repo: RepositoryId) : this(server.trimEnd('/'), repo.owner, repo.name)

    override fun toString(): String {
        return "$server/$owner/$name"
    }

    fun toInfo(): GitHubRepositoryInfo = GitHubRepositoryInfo(server, owner, name)

    override fun equals(other: Any?): Boolean  {
        return if (other is RepoKey) {
            server.equals(other.server, true) && owner.equals(other.owner, true) && name.equals(other.name, true)
        } else {
            false
        }
    }

    override fun hashCode(): Int {
        return hashCode
    }
}