package org.jetbrains.teamcity.github

import org.eclipse.egit.github.core.RepositoryHook
import org.jetbrains.teamcity.github.controllers.Status
import java.util.*

/**
 * It's highly recommended not to modify any 'var' field outside of WebHooksStorage#update methods
 * since modifications may be not stored on disk
 */
class WebHookInfo(val url: String, // API URL
                  val callbackUrl: String, // TC URL (GitHubWebHookListener)

                  val key: WebHooksStorage.Key = WebHooksStorage.Key.fromHookUrl(url),
                  val id: Long = key.id,

                  var status: Status,
                  var lastUsed: Date? = null,
                  var lastBranchRevisions: MutableMap<String, String>? = null
) {
    companion object {
        private fun oneFromJson(string: String): WebHookInfo? = WebHooksStorage.gson.fromJson(string, WebHookInfo::class.java)
        private fun listFromJson(string: String): List<WebHookInfo> = WebHooksStorage.gson.fromJson(string, WebHooksStorage.hooksListType) ?: emptyList()

        fun fromJson(json: String): List<WebHookInfo> {
            return when {
                json.startsWith("{") -> {
                    listOfNotNull(oneFromJson(json))
                }
                json.startsWith("[") -> {
                    listFromJson(json)
                }
                else -> {
                    throw IllegalArgumentException("Unexpected content: $json")
                }
            }
        }

        fun toJson(hooks: List<WebHookInfo>): String {
            return WebHooksStorage.gson.toJson(hooks)
        }
    }

    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated("")
    fun toJson(): String = WebHooksStorage.gson.toJson(this)

    fun isSame(hook: RepositoryHook): Boolean {
        return id == hook.id && url == hook.url && callbackUrl == hook.callbackUrl
    }

    fun getUIUrl(): String {
        return "https://${key.server}/${key.owner}/${key.name}/settings/hooks/$id"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false
        other as WebHookInfo

        if (url != other.url) return false
        if (callbackUrl != other.callbackUrl) return false

        return true
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + callbackUrl.hashCode()
        return result
    }

    fun updateBranchMapping(update: Map<String, String>): Map<String, String> {
        if (lastBranchRevisions == null) lastBranchRevisions = HashMap()
        lastBranchRevisions!!.putAll(update)
        return lastBranchRevisions!!
    }

    override fun toString(): String {
        return "HookInfo(url='$url', callbackUrl='$callbackUrl', status=$status, lastUsed=$lastUsed)"
    }
}