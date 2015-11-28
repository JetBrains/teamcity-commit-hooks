package org.jetbrains.teamcity.github

import com.google.gson.stream.JsonWriter
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
}