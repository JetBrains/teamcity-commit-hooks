

package org.jetbrains.teamcity.github.json

import com.google.gson.*
import org.jetbrains.teamcity.github.HookKey
import org.jetbrains.teamcity.github.WebHookInfo
import org.jetbrains.teamcity.github.controllers.Status
import java.lang.reflect.Type
import java.util.*

object HookInfoTypeAdapter : JsonDeserializer<WebHookInfo>, JsonSerializer<WebHookInfo> {
    override fun serialize(src: WebHookInfo, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()

        obj.addProperty("url", src.url)
        obj.addProperty("callbackUrl", src.callbackUrl)

        obj.add("status", context.serialize(src.status))
        src.lastUsed?.let { obj.add("lastUsed", context.serialize(it)) }
        src.lastBranchRevisions?.let { obj.add("lastBranchRevisions", context.serialize(it)) }

        return obj
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): WebHookInfo {
        if (json !is JsonObject) throw JsonParseException("JsonObject expected")

        val url = json.getAsJsonPrimitive("url").asString
        val callbackUrl = json.getAsJsonPrimitive("callbackUrl").asString

        val key = HookKey.fromHookUrl(url)

        val status = context.deserialize<Status>(json.get("status"), Status::class.java)
        val lastUsed = json.get("lastUsed")?.let { context.deserialize<Date>(it, Date::class.java) }
        val lastBranchRevisions = json.get("lastBranchRevisions")?.let { context.deserialize<Map<String, String>>(it, Map::class.java) }?.let { HashMap(it) }

        return WebHookInfo(url, callbackUrl, key, key.id, status, lastUsed, lastBranchRevisions)
    }

}