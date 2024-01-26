

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