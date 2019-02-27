package org.jetbrains.teamcity.github

import org.assertj.core.api.BDDAssertions.then
import org.eclipse.egit.github.core.RepositoryId
import org.jetbrains.teamcity.github.controllers.Status
import org.testng.annotations.Test
import java.util.*

class WebHooksStorageTest {

    companion object {
        val callback = "__CALLBACK_URL__"
    }


    @Test
    fun testKeyTransformation() {
        doKeyTest("github.com", "JetBrains", "kotlin", "github.com/JetBrains/kotlin")
        doKeyTest("github.com/", "JetBrains", "kotlin", "github.com/JetBrains/kotlin")
        doKeyTest("teamcity-github-enterprise.labs.intellij.net/", "Vlad", "test-repo-1", "teamcity-github-enterprise.labs.intellij.net/Vlad/test-repo-1")
    }

    @Test
    fun testHookInfoSerialization() {
        doHookInfoSerializationTest(WebHooksStorage.HookInfo("http://server/api/v3/repos/owner/repo/hooks/10", callbackUrl = callback, id = 10, status = Status.OK))
        doHookInfoSerializationTest(WebHooksStorage.HookInfo("http://server/api/v3/repos/owner/repo/hooks/10", callbackUrl = callback, id = 10, status = Status.DISABLED, lastUsed = Date(), lastBranchRevisions = mutableMapOf("1" to "2", "3" to "4")))
        doHookInfoSerializationTest(WebHooksStorage.HookInfo("http://server/api/v3/repos/owner/repo/hooks/10", callbackUrl = callback, id = 10, status = Status.INCORRECT))
        doHookInfoSerializationTest(WebHooksStorage.HookInfo("http://server/api/v3/repos/owner/repo/hooks/10", callbackUrl = callback, id = 10, status = Status.MISSING, lastUsed = Date(10)))
        doHookInfoSerializationTest(WebHooksStorage.HookInfo("http://server/api/v3/repos/owner/repo/hooks/10", callbackUrl = callback, id = 10, status = Status.WAITING_FOR_SERVER_RESPONSE, lastUsed = Date(10), lastBranchRevisions = LinkedHashMap(mapOf("1" to "2"))))
    }

    @Test
    fun testHookInfoListSerialization() {
        val first = WebHooksStorage.HookInfo("http://server/api/v3/repos/owner/repo/hooks/1", callbackUrl = callback, id = 1, status = Status.OK)
        val second = WebHooksStorage.HookInfo("http://server/api/v3/repos/owner/repo/hooks/2", callbackUrl = callback, id = 2, status = Status.INCORRECT)
        val json = WebHooksStorage.HookInfo.toJson(listOf(first, second))
        val list: List<WebHooksStorage.HookInfo>? = WebHooksStorage.HookInfo.fromJson(json)
        then(list).containsOnly(first, second).isEqualTo(listOf(first, second))
    }

    @Test
    fun testBothDeserialization() {
        val first = WebHooksStorage.HookInfo("http://server/api/v3/repos/owner/repo/hooks/1", callbackUrl = callback, id = 1, status = Status.OK)
        val second = WebHooksStorage.HookInfo("http://server/api/v3/repos/owner/repo/hooks/2", callbackUrl = callback, id = 2, status = Status.INCORRECT)
        val list = listOf(first, second)

        val json = WebHooksStorage.HookInfo.toJson(list)
        then(json).isNotNull()
        then(WebHooksStorage.HookInfo.fromJson(json)).isNotNull().isEqualTo(list)

        val singleList = WebHooksStorage.HookInfo.fromJson(first.toJson())
        then(singleList).isEqualTo(listOf(first))
    }

    @Test
    fun testHookURLToKey() {
        doHookURLToKey("https://teamcity-github-enterprise.labs.intellij.net/api/v3/repos/Vlad/test/hooks/88", "teamcity-github-enterprise.labs.intellij.net", "Vlad", "test", 88)
        doHookURLToKey("https://api.github.com/repos/VladRassokhin/intellij-hcl/hooks/9124004", "github.com", "VladRassokhin", "intellij-hcl", 9124004)
    }

    @Test
    fun testDataToJsonAndBack() {
        val hook = WebHooksStorage.HookInfo("http://server/api/v3/repos/owner/repo/hooks/1", callback, status = Status.OK)
        val obj = WebHooksStorage.getJsonObjectFromData(listOf(hook))
        val json = WebHooksStorage.gson.toJson(obj)
        then(json).contains("\"version\"").contains("\"hooks\"")

        val map = WebHooksStorage.getDataFromJsonObject(obj)
        then(map).isNotNull()
        val key = WebHooksStorage.MapKey("server", "owner", "repo")
        then(map!!).containsOnlyKeys(key)
        then(map[key]).containsOnly(hook)
    }

    private fun doHookURLToKey(url: String, server: String, owner: String, name: String, id: Long) {
        val key = WebHooksStorage.Key.fromHookUrl(url)
        val (s, o, n, i) = key
        then(s).isEqualTo(server)
        then(o).isEqualTo(owner)
        then(n).isEqualTo(name)
        then(i).isEqualTo(id)
        then(WebHooksStorage.Key.fromString(key.toString())).isEqualTo(key)
    }

    private fun doHookInfoSerializationTest(first: WebHooksStorage.HookInfo) {
        val json = first.toJson()
        val second = WebHooksStorage.HookInfo.fromJson(json).firstOrNull()
        then(second).isNotNull()
        second!!
        then(second.id).isEqualTo(first.id)
        then(second.lastUsed).isEqualTo(first.lastUsed)
        then(second.lastBranchRevisions).isEqualTo(first.lastBranchRevisions)
        then(second.url).isEqualTo(first.url)
        then(second.callbackUrl).isEqualTo(first.callbackUrl)
        then(second.toJson()).isEqualTo(json)
        then(second.hashCode()).isEqualTo(first.hashCode())
        then(second).isEqualTo(first)
        then(second.key).isEqualTo(first.key)

        then(json).doesNotContain("\"key\"")
    }

    fun doKeyTest(server: String, owner: String, name: String, expectedKey: String) {
        val key = WebHooksStorage.MapKey(server, RepositoryId.create(owner, name))
        then(key.toString()).isEqualTo(expectedKey)
        val (a, b, c) = key
        then(Triple(a, b, c)).isEqualTo(Triple(server.trimEnd('/'), owner, name))
    }
}