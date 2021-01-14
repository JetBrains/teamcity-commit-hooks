/*
 * Copyright 2000-2021 JetBrains s.r.o.
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