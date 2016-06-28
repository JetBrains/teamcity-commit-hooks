package org.jetbrains.teamcity.github

import com.google.gson.stream.JsonWriter
import jetbrains.buildServer.util.StringUtil
import org.eclipse.egit.github.core.RepositoryId
import java.io.StringWriter

data class GitHubRepositoryInfo(val server: String, val owner: String, val name: String) {
    fun getRepositoryId(): RepositoryId = RepositoryId.create(owner, name)
    fun toJson(): String {
        val sw = StringWriter()
        val writer = JsonWriter(sw)
        writer.beginObject()
        writer.name("server").value(server)
        writer.name("owner").value(owner)
        writer.name("name").value(name)
        writer.endObject();
        writer.flush()
        return sw.toString()
    }

    fun isHasParameterReferences(): Boolean {
        return StringUtil.hasParameterReferences(server) || StringUtil.hasParameterReferences(owner) || StringUtil.hasParameterReferences(name);
    }

    override fun toString(): String {
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
        val builder = StringBuilder()
        // TODO: Uncomment next line. It's workaround for our non-https GHE server
        //builder.append("https:")
        builder.append("//")
        builder.append(server)
        if (!builder.endsWith('/')) {
            builder.append('/')
        }
        builder.append(owner)
        builder.append('/')
        builder.append(name)
        return builder.toString()
    }

    fun getIdentifier(): String {
        return toString().replace("/", "_")
    }
}