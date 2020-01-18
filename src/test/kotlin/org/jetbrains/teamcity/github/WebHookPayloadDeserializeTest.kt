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

package org.jetbrains.teamcity.github

import org.assertj.core.api.Assertions
import org.eclipse.egit.github.core.client.GsonUtilsEx
import org.eclipse.egit.github.core.client.IGitHubConstants
import org.eclipse.egit.github.core.event.PingWebHookPayload
import org.eclipse.egit.github.core.event.PullRequestPayloadEx
import org.eclipse.egit.github.core.event.PushWebHookPayload
import org.testng.annotations.BeforeClass
import org.testng.annotations.DataProvider
import org.testng.annotations.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class WebHookPayloadDeserializeTest {
    @BeforeClass
    fun setUp() {
        val format = SimpleDateFormat(IGitHubConstants.DATE_FORMAT_V2_2)
        format.timeZone = TimeZone.getTimeZone("Zulu")
        Assertions.registerCustomDateFormat(format)
    }

    @Test
    fun testPushPayloadParsed() {
        val file = getTestFile("example-push-payload.json")
        Assertions.assertThat(file).exists().isFile()
        val input = file.readText()
        Assertions.assertThat(input).isNotEmpty()
        val event = GsonUtilsEx.fromJson(input, PushWebHookPayload::class.java)
        Assertions.assertThat(event).isNotNull()
        Assertions.assertThat(event.repository).isNotNull()
        Assertions.assertThat(event.ref).isNotNull()

        Assertions.assertThat(event.repository.pushedAt).isEqualTo("2015-11-27T17:50:23Z")
        Assertions.assertThat(event.repository.updatedAt).isEqualTo("2015-11-09T19:16:01Z")
    }

    @Test
    fun testPingPayloadParsed() {
        val file = getTestFile("example-ping-payload.json")
        Assertions.assertThat(file).exists().isFile()
        val input = file.readText()
        Assertions.assertThat(input).isNotEmpty()
        val event = GsonUtilsEx.fromJson(input, PingWebHookPayload::class.java)
        Assertions.assertThat(event).isNotNull()
        Assertions.assertThat(event.hook).isNotNull()
        Assertions.assertThat(event.repository).isNotNull()
        Assertions.assertThat(event.sender).isNotNull()
        Assertions.assertThat(event.zen).isNotNull()
        Assertions.assertThat(event.hook_id).isEqualTo(event.hook.id.toInt())
    }

    @DataProvider(name = "PullRequestPayloads")
    fun PullRequestPayloads(): Array<Array<String>> {
        return listOf("example-pull-request-opened-payload.json",
                      "example-pull-request-synchronize-payload.json")
                .map { arrayOf(it) }.toTypedArray()
    }

    @Test(dataProvider = "PullRequestPayloads")
    fun testPullRequestPayloadParsed(fileName: String) {
        val file = getTestFile(fileName)
        Assertions.assertThat(file).exists().isFile()
        val input = file.readText()
        Assertions.assertThat(input).isNotEmpty()
        val event = GsonUtilsEx.fromJson(input, PullRequestPayloadEx::class.java)
        Assertions.assertThat(event).isNotNull()
        Assertions.assertThat(event.action).isNotNull()
        Assertions.assertThat(event.number).isNotNull()
        Assertions.assertThat(event.pullRequest).isNotNull()
        Assertions.assertThat(event.pullRequest.head).isNotNull()
        Assertions.assertThat(event.pullRequest.head.repo).isNotNull()
        Assertions.assertThat(event.pullRequest.head.repo.htmlUrl).isNotNull()
        Assertions.assertThat(event.pullRequest.head.sha).isNotNull()
        Assertions.assertThat(event.pullRequest.mergeCommitSha).isNotNull()
    }

    private fun getTestFile(path: String): File {
        val root = File("src/test/resources")
        return File(root, path)
    }
}