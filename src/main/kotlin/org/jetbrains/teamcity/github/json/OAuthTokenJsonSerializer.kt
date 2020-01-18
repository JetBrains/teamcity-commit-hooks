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
import jetbrains.buildServer.serverSide.crypt.EncryptUtil
import jetbrains.buildServer.serverSide.oauth.OAuthToken
import java.lang.reflect.Type
import java.util.*

object OAuthTokenJsonSerializer : JsonSerializer<OAuthToken>, JsonDeserializer<OAuthToken> {
    val simple = GsonBuilder().registerTypeAdapter(Date::class.java, SimpleDateTypeAdapter).create()

    private val AccessTokenField = "myAccessToken"

    override fun serialize(src: OAuthToken, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = simple.toJsonTree(src) as JsonObject
        obj.addProperty(AccessTokenField, EncryptUtil.scramble(src.accessToken))
        return obj
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): OAuthToken {
        if (json !is JsonObject) throw JsonParseException("JsonObject expected")
        if (!json.has(AccessTokenField)) throw JsonParseException("myAccessToken property expected in json object")
        json.addProperty(AccessTokenField, EncryptUtil.unscramble(json[AccessTokenField].asJsonPrimitive.asString))
        return simple.fromJson(json, OAuthToken::class.java)
    }

}