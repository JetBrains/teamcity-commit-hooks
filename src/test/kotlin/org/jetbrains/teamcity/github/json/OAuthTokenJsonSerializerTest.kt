package org.jetbrains.teamcity.github.json

import com.google.gson.GsonBuilder
import jetbrains.buildServer.serverSide.oauth.OAuthToken
import org.assertj.core.api.BDDAssertions.then
import org.jetbrains.teamcity.github.json.OAuthTokenJsonSerializer
import org.testng.annotations.Test

class OAuthTokenJsonSerializerTest {
    @Test
    fun testTwoWay() {
        val gson = GsonBuilder().registerTypeAdapter(OAuthToken::class.java, OAuthTokenJsonSerializer).create()
        val origin = OAuthToken("__TOKEN__", "__SCOPE__", "__LOGIN__", 9000, 1000)
        var json = gson.toJson(origin)
        then(json).contains("__SCOPE__", "__LOGIN__", "9000", "1000", origin.createDate.time.toString()).doesNotContain("__TOKEN__")

        val parsed = gson.fromJson(json, OAuthToken::class.java)

        then(parsed).isEqualToComparingFieldByField(origin).isEqualTo(origin)

        json = gson.toJson(parsed)
        then(json).contains("__SCOPE__", "__LOGIN__", "9000", "1000", origin.createDate.time.toString()).doesNotContain("__TOKEN__")
    }
}