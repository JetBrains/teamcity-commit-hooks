package org.jetbrains.teamcity.github

import com.google.gson.stream.JsonWriter
import jetbrains.buildServer.util.StringUtil
import org.eclipse.egit.github.core.RepositoryId
import java.io.StringWriter

public data class VcsRootGitHubInfo(val server: String, val owner: String, val name: String){
    public fun getRepositoryId(): RepositoryId = RepositoryId.create(owner, name)
    public fun toJson(): String {
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

    public fun isHasParameterReferences(): Boolean {
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

    public fun getRepositoryUrl(): String {
        val builder = StringBuilder()
        builder.append("https://")
        builder.append(server)
        if (!builder.endsWith('/')) {
            builder.append('/')
        }
        builder.append(owner)
        builder.append('/')
        builder.append(name)
        return builder.toString()
    }

    public fun getIdentifier(): String {
        return toString().replace("/", "_")
    }
}