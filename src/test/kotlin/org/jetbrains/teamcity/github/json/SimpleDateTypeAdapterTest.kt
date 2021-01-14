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
import jetbrains.buildServer.util.Dates
import org.assertj.core.api.BDDAssertions.then
import org.jetbrains.teamcity.github.json.SimpleDateTypeAdapter
import org.testng.annotations.Test
import java.util.*

class SimpleDateTypeAdapterTest {
    @Test
    fun testTwoWay() {
        val gson = GsonBuilder().registerTypeAdapter(Date::class.java, SimpleDateTypeAdapter).create()

        val origin = Dates.now()
        val json = gson.toJson(origin)

        then(json).contains(origin.time.toString())

        val parsed = gson.fromJson(json, Date::class.java)

        then(parsed).isEqualTo(origin)
    }
}