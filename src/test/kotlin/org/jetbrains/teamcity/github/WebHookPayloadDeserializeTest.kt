package org.jetbrains.teamcity.github

import org.assertj.core.api.Assertions
import org.eclipse.egit.github.core.client.GsonUtilsEx
import org.eclipse.egit.github.core.event.PingWebHookPayload
import org.eclipse.egit.github.core.event.PushWebHookPayload
import org.testng.annotations.Test
import java.io.File

class WebHookPayloadDeserializeTest {

    @Test
    fun testPushPayloadParsed() {
        val file = getTestFile("example-push-payload.json")
        Assertions.assertThat(file).exists().isFile()
        val input = file.readText()
        Assertions.assertThat(input).isNotEmpty();
        val event = GsonUtilsEx.fromJson(input, PushWebHookPayload::class.java)
        Assertions.assertThat(event).isNotNull()
        Assertions.assertThat(event.repository).isNotNull()
        Assertions.assertThat(event.ref).isNotNull()
    }

    @Test
    fun testPingPayloadParsed() {
        val file = getTestFile("example-ping-payload.json")
        Assertions.assertThat(file).exists().isFile()
        val input = file.readText()
        Assertions.assertThat(input).isNotEmpty();
        val event = GsonUtilsEx.fromJson(input, PingWebHookPayload::class.java)
        Assertions.assertThat(event).isNotNull()
        Assertions.assertThat(event.hook).isNotNull()
        Assertions.assertThat(event.repository).isNotNull()
        Assertions.assertThat(event.sender).isNotNull()
        Assertions.assertThat(event.zen).isNotNull()
        Assertions.assertThat(event.hook_id).isEqualTo(event.hook.id.toInt())
    }

    private fun getTestFile(path: String): File {
        val root = File("src/test/resources")
        return File(root, path)
    }
}