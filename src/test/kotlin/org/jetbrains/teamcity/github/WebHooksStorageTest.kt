package org.jetbrains.teamcity.github

import org.assertj.core.api.BDDAssertions.then
import org.eclipse.egit.github.core.RepositoryId
import org.jetbrains.teamcity.github.controllers.Status
import org.testng.annotations.Test
import java.util.*

class WebHooksStorageTest {

    @Test
    fun testKeyTransformation() {
        doKeyTest("github.com", "JetBrains", "kotlin", "github.com/JetBrains/kotlin")
        doKeyTest("github.com/", "JetBrains", "kotlin", "github.com/JetBrains/kotlin")
        doKeyTest("teamcity-github-enterprise.labs.intellij.net/", "Vlad", "test-repo-1", "teamcity-github-enterprise.labs.intellij.net/Vlad/test-repo-1")
    }

    @Test
    fun testHookInfoSerialization() {
        val callback = "__CALLBACK_URL__"
        doHookInfoSerializationTest(WebHooksStorage.HookInfo(10, "abc", Status.OK, callbackUrl = callback))
        doHookInfoSerializationTest(WebHooksStorage.HookInfo(10, "abc", Status.DISABLED, Date(), mutableMapOf("1" to "2", "3" to "4"), callbackUrl = callback))
        doHookInfoSerializationTest(WebHooksStorage.HookInfo(10, "abc", Status.INCORRECT, callbackUrl = callback))
        doHookInfoSerializationTest(WebHooksStorage.HookInfo(10, "abc", Status.MISSING, Date(10), callbackUrl = callback))
        doHookInfoSerializationTest(WebHooksStorage.HookInfo(10, "abc", Status.WAITING_FOR_SERVER_RESPONSE, Date(10), LinkedHashMap(mapOf("1" to "2")), callbackUrl = callback))
    }

    private fun doHookInfoSerializationTest(first: WebHooksStorage.HookInfo) {
        val second = WebHooksStorage.HookInfo.fromJson(first.toJson())
        then(second).isNotNull()
        second!!
        then(second.id).isEqualTo(first.id)
        then(second.lastUsed).isEqualTo(first.lastUsed)
        then(second.lastBranchRevisions).isEqualTo(first.lastBranchRevisions)
        then(second.url).isEqualTo(first.url)
        then(second.callbackUrl).isEqualTo(first.callbackUrl)
        then(second.toJson()).isEqualTo(first.toJson())
        then(second.hashCode()).isEqualTo(first.hashCode())
        then(second).isEqualTo(first)
    }

    fun doKeyTest(server: String, owner: String, name: String, expectedKey: String) {
        val key = WebHooksStorage.toKey(server, RepositoryId.create(owner, name))
        then(key).isEqualTo(expectedKey)
        val triple = WebHooksStorage.fromKey(key)
        then(triple).isEqualTo(Triple(server.trimEnd('/'), owner, name))
    }
}