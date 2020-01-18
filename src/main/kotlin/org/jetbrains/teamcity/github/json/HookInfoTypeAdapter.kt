/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.teamcity.github.json

import com.google.gson.*
import org.jetbrains.teamcity.github.WebHooksStorage
import org.jetbrains.teamcity.github.controllers.Status
import java.lang.reflect.Type
import java.util.*

object HookInfoTypeAdapter : JsonDeserializer<WebHooksStorage.HookInfo>, JsonSerializer<WebHooksStorage.HookInfo> {
    override fun serialize(src: WebHooksStorage.HookInfo, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()

        obj.addProperty("url", src.url)
        obj.addProperty("callbackUrl", src.callbackUrl)

        obj.add("status", context.serialize(src.status))
        src.lastUsed?.let { obj.add("lastUsed", context.serialize(it)) }
        src.lastBranchRevisions?.let { obj.add("lastBranchRevisions", context.serialize(it)) }

        return obj
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): WebHooksStorage.HookInfo {
        if (json !is JsonObject) throw JsonParseException("JsonObject expected")

        val url = json.getAsJsonPrimitive("url").asString
        val callbackUrl = json.getAsJsonPrimitive("callbackUrl").asString

        val key = WebHooksStorage.Key.fromHookUrl(url)

        val status = context.deserialize<Status>(json.get("status"), Status::class.java)
        val lastUsed = json.get("lastUsed")?.let { context.deserialize<Date>(it, Date::class.java) }
        val lastBranchRevisions = json.get("lastBranchRevisions")?.let { context.deserialize<Map<String, String>>(it, Map::class.java) }?.let { HashMap(it) }

        return WebHooksStorage.HookInfo(url, callbackUrl, key, key.id, status, lastUsed, lastBranchRevisions)
    }

}