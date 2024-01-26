

package org.jetbrains.teamcity.github

import com.google.gson.stream.JsonWriter
import jetbrains.buildServer.util.StringUtil
import org.eclipse.egit.github.core.RepositoryId
import java.io.StringWriter
import java.util.*

data class GitHubRepositoryInfo(val server: String, val owner: String, val name: String) {
    companion object {
        val LexicographicalComparator = Comparator<GitHubRepositoryInfo> { a, b ->
            var r: Int = a.server.compareTo(b.server)
            if (r != 0) return@Comparator r
            r = a.owner.compareTo(b.owner)
            if (r != 0) return@Comparator r
            r = a.name.compareTo(b.name)
            if (r != 0) return@Comparator r
            0
        }
    }

    fun getRepositoryId(): RepositoryId = RepositoryId.create(owner, name)

    fun toJson(): String {
        val sw = StringWriter()
        val writer = JsonWriter(sw)
        writer.beginObject()
        writer.name("server").value(server)
        writer.name("owner").value(owner)
        writer.name("name").value(name)
        writer.endObject()
        writer.flush()
        return sw.toString()
    }

    fun isHasParameterReferences(): Boolean {
        return StringUtil.hasParameterReferences(server) || StringUtil.hasParameterReferences(owner) || StringUtil.hasParameterReferences(name)
    }

    /**
     * Returns id in 'server/owner/name' format
     */
    val id: String
        get() {
            val builder = StringBuilder()
            builder.append(server)
            if (!builder.endsWith('/')) {
                builder.append('/')
            }
            builder.append(owner)
            builder.append('/')
            builder.append(name)
            return builder.toString()
        }


    fun getRepositoryUrl(): String {
        // We expect that all GHE servers has https mode enabled.
        // One thing that may broke: links in ui. Internal logic uses connection url anyway.
        return "https://$id"
    }

}