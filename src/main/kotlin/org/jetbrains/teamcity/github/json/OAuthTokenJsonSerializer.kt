

package org.jetbrains.teamcity.github.json

import com.google.gson.*
import jetbrains.buildServer.serverSide.crypt.EncryptUtil
import jetbrains.buildServer.serverSide.oauth.OAuthToken
import java.lang.reflect.Type
import java.util.*

object OAuthTokenJsonSerializer : JsonSerializer<OAuthToken>, JsonDeserializer<OAuthToken> {
    val simple: Gson = GsonBuilder().registerTypeAdapter(Date::class.java, SimpleDateTypeAdapter).create()

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