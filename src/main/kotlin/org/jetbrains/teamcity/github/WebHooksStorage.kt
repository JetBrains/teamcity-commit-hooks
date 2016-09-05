package org.jetbrains.teamcity.github

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.BuildServerListener
import jetbrains.buildServer.serverSide.ServerPaths
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.util.FileUtil
import jetbrains.buildServer.util.cache.CacheProvider
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryId
import org.jetbrains.teamcity.github.controllers.Status
import org.jetbrains.teamcity.github.controllers.bad
import org.jetbrains.teamcity.github.json.HookInfoTypeAdapter
import org.jetbrains.teamcity.github.json.SimpleDateTypeAdapter
import java.io.File
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Webhooks info storage
 * Backend: 'commit-hooks/webhooks.json' file under pluginData folder
 *
 * Data loaded from disk only on server start
 * Data persisted onto disk only on server stop
 */
class WebHooksStorage(cacheProvider: CacheProvider,
                      private val myServerPaths: ServerPaths,
                      private val myServerEventDispatcher: EventDispatcher<BuildServerListener>) {
    companion object {
        private val LOG: Logger = Logger.getInstance(WebHooksStorage::class.java.name)

        private val VERSION: Int = 1;

        private val hooksListType = object : TypeToken<List<HookInfo>>() {}.type
        val gson: Gson = GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(Date::class.java, SimpleDateTypeAdapter)
                .registerTypeAdapter(HookInfo::class.java, HookInfoTypeAdapter)
                .create()!!

        internal fun getJsonObjectFromData(data: List<HookInfo>): JsonObject {
            val list = data.toHashSet().toMutableList()
            list.sortWith(Comparator { a, b -> a.key.toString().compareTo(b.key.toString()) })

            val obj = JsonObject()
            obj.addProperty("version", VERSION)
            obj.add("hooks", gson.toJsonTree(list, hooksListType))
            return obj
        }

        internal fun getDataFromJsonObject(obj: JsonObject): Map<MapKey, List<HookInfo>>? {
            val version = obj.getAsJsonPrimitive("version").asInt
            if (version != VERSION) {
                LOG.warn("Stored data have outdated version $version")
                return null
            }
            val array = obj.getAsJsonArray("hooks")
            val hooks = gson.fromJson<List<HookInfo>>(array, hooksListType)

            return hooks.groupBy { it.key.toMapKey() }
        }
    }

    // URLs:
    // http://teamcity-github-enterprise.labs.intellij.net/api/v3/repos/Vlad/test/hooks/88
    // https://api.github.com/repos/VladRassokhin/intellij-hcl/hooks/9124004

    // Keys:
    // teamcity-github-enterprise.labs.intellij.net/Vlad/test/88
    // github.com/VladRassokhin/intellij-hcl/9124004

    data class Key(val server: String, val owner: String, val name: String, val id: Long) {
        companion object {
            fun fromString(serialized: String): Key {
                val split = serialized.split('/')
                if (split.size < 4) throw IllegalArgumentException("Not an proper key: \"$serialized\"")
                val id = split[split.lastIndex].toLong()
                val name = split[split.lastIndex - 1]
                val owner = split[split.lastIndex - 2]
                val server = split.dropLast(3).joinToString("/")
                return Key(server, owner, name, id)
            }

            fun fromHookUrl(hookUrl: String): Key {
                val split = ArrayDeque(hookUrl.split('/'))
                assert(split.size >= 8)
                val id = split.pollLast().toLong()
                split.pollLast() // "hooks"
                val name = split.pollLast()
                val owner = split.pollLast()
                split.pollLast() // "repos"
                val serverOfV3 = split.pollLast()
                val server: String
                if (serverOfV3 == "api.github.com") {
                    server = "github.com"
                } else {
                    split.pollLast()
                    server = split.pollLast()
                }
                return Key(server, owner, name, id)
            }
        }

        override fun toString(): String {
            return "$server/$owner/$name/$id"
        }

        fun toMapKey(): MapKey {
            return MapKey(server.trimEnd('/'), owner, name)
        }
    }

    /**
     * It's highly recommended not to modify any 'var' field outside of WebHooksStorage#update methods
     * since modifications may be not stored on disk
     */
    class HookInfo(val url: String, // API URL
                   val callbackUrl: String, // TC URL (GitHubWebHookListener)

                   val key: Key = Key.fromHookUrl(url),
                   val id: Long = key.id,

                   var status: Status,
                   var lastUsed: Date? = null,
                   var lastBranchRevisions: MutableMap<String, String>? = null
    ) {
        companion object {
            private fun oneFromJson(string: String): HookInfo? = gson.fromJson(string, HookInfo::class.java)
            private fun listFromJson(string: String): List<HookInfo> = gson.fromJson(string, hooksListType) ?: emptyList()

            fun fromJson(json: String): List<HookInfo> {
                if (json.startsWith("{")) {
                    return listOf(HookInfo.oneFromJson(json)).filterNotNull()
                } else if (json.startsWith("[")) {
                    return HookInfo.listFromJson(json)
                } else {
                    throw IllegalArgumentException("Unexpected content: $json")
                }
            }

            fun toJson(hooks: List<HookInfo>): String {
                return gson.toJson(hooks)
            }
        }

        @Suppress("DeprecatedCallableAddReplaceWith")
        @Deprecated("")
        fun toJson(): String = gson.toJson(this)

        fun isSame(hook: RepositoryHook): Boolean {
            return id == hook.id && url == hook.url && callbackUrl == hook.callbackUrl
        }

        fun getUIUrl(): String {
            return "https://${key.server}/${key.owner}/${key.name}/settings/hooks/$id"
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other?.javaClass != javaClass) return false
            other as HookInfo

            if (url != other.url) return false
            if (callbackUrl != other.callbackUrl) return false

            return true
        }

        override fun hashCode(): Int {
            var result = url.hashCode()
            result = 31 * result + callbackUrl.hashCode()
            return result
        }

        override fun toString(): String {
            return "HookInfo(url='$url', callbackUrl='$callbackUrl', status=$status, lastUsed=$lastUsed, lastBranchRevisions=$lastBranchRevisions)"
        }
    }

    data class MapKey internal constructor(val server: String, val owner: String, val name: String) {
        constructor(server: String, repo: RepositoryId) : this(server.trimEnd('/'), repo.owner, repo.name)

        override fun toString(): String {
            return "$server/$owner/$name"
        }

        fun toInfo(): GitHubRepositoryInfo = GitHubRepositoryInfo(server, owner, name)
    }

    private val myData = HashMap<MapKey, MutableList<HookInfo>>()
    private val myDataLock = ReentrantReadWriteLock()

    private val myServerListener = object : BuildServerAdapter() {
        override fun serverStartup() {
            load()

            // Drop old caches from pre-release versions of plugin
            try {
                cacheProvider.destroyCache("WebHooksCache")
                cacheProvider.destroyCache("WebHooksAuthCache")
            } catch(e: Exception) {
            }
        }

        override fun serverShutdown() {
            persist()
        }
    }

    fun init(): Unit {
        myServerEventDispatcher.addListener(myServerListener)
    }

    fun destroy(): Unit {
        myServerEventDispatcher.removeListener(myServerListener)
    }

    /**
     * Adds hook if it not existed previously
     */
    fun getOrAdd(created: RepositoryHook): HookInfo {
        val key = Key.fromHookUrl(created.url)
        val mapKey = key.toMapKey()

        val hooks = getHooks(mapKey)
        val hook = hooks.firstOrNull { it.isSame(created) }
        if (hook != null) return hook

        myDataLock.write {
            @Suppress("NAME_SHADOWING")
            var hooks = myData[mapKey]
            @Suppress("NAME_SHADOWING")
            val hook = hooks?.firstOrNull { it.isSame(created) }
            if (hook != null) return hook

            val toAdd = WebHooksStorage.HookInfo(url = created.url, callbackUrl = created.callbackUrl!!, key = key, status = created.getStatus())

            if (hooks == null || hooks.isEmpty()) {
                hooks = mutableListOf(toAdd)
                myData.put(mapKey, hooks)
            } else {
                hooks = hooks;
                hooks.add(toAdd)
                myData.put(mapKey, hooks)
            }
            return toAdd
        }
    }

    fun delete(hookInfo: HookInfo) {
        myDataLock.write {
            myData[hookInfo.key.toMapKey()]?.remove(hookInfo)
        }
    }

    fun delete(info: GitHubRepositoryInfo, deleteFilter: (HookInfo) -> Boolean) {
        if (!getHooks(info).any { deleteFilter(it) }) return

        val key = MapKey(info.server, info.getRepositoryId())
        myDataLock.write {
            val hooks = myData[key]?.toMutableList() ?: return
            val filtered = hooks.filter { !deleteFilter(it) }.toMutableList()
            if (filtered.isEmpty()) {
                myData.remove(key)
            } else {
                myData.put(key, filtered)
            }
        }
    }

    fun update(server: String, repo: RepositoryId, update: (HookInfo) -> Unit): Boolean {
        val key = MapKey(server, repo)
        val hooks = myDataLock.read {
            myData[key]?.toMutableList() ?: return false
        }
        for (hook in hooks) {
            update(hook)
        }
        return true
    }

    fun getHooks(info: GitHubRepositoryInfo): List<WebHooksStorage.HookInfo> {
        return getHooks(info.server, info.getRepositoryId())
    }

    fun getHooks(server: String, repo: RepositoryId): List<WebHooksStorage.HookInfo> {
        return getHooks(MapKey(server, repo))
    }

    private fun getHooks(key: MapKey): List<HookInfo> {
        return myDataLock.read {
            myData[key]?.let { it.toList(); }
        } ?: emptyList()
    }

    fun isHasIncorrectHooks(): Boolean {
        myDataLock.read {
            for (value in myData.values) {
                if (value.any { it.status.bad }) return true
            }
        }
        return false
    }

    fun getIncorrectHooks(): List<Pair<GitHubRepositoryInfo, WebHooksStorage.HookInfo>> {
        val result = ArrayList<Pair<GitHubRepositoryInfo, WebHooksStorage.HookInfo>>()
        myDataLock.read {
            for ((key, hooks) in myData) {
                val bad = hooks.filter { it.status.bad }
                if (bad.isNotEmpty()) {
                    val info = key.toInfo()
                    bad.map { info to it }.toCollection(result)
                }
            }
        }
        return result
    }

    fun getAll(): List<Pair<GitHubRepositoryInfo, HookInfo>> {
        val result = ArrayList<Pair<GitHubRepositoryInfo, WebHooksStorage.HookInfo>>()
        myDataLock.read {
            for ((key, hooks) in myData) {
                val info = key.toInfo()
                hooks.map { info to it }.toCollection(result)
            }
        }
        return result
    }

    private fun getStorageFile(): File {
        return File(myServerPaths.pluginDataDirectory, "commit-hooks/webhooks.json")
    }

    @Synchronized private fun persist(): Boolean {
        val obj = myDataLock.read {
            getJsonObjectFromData(myData.values.flatten())
        }

        val file = getStorageFile()

        try {
            FileUtil.createParentDirs(file)
            val writer = file.writer(Charsets.UTF_8).buffered()
            writer.use {
                gson.toJson(obj, it)
            }
            return true
        } catch(e: Exception) {
            LOG.warnAndDebugDetails("Cannot write auth-data to file '${file.absolutePath}'", e)
            return false
        }
    }


    @Synchronized private fun load(): Boolean {
        val file = getStorageFile()

        val obj: JsonObject?
        try {
            FileUtil.createParentDirs(file)
            if (!file.isFile) return false
            obj = file.reader(Charsets.UTF_8).buffered().use {
                gson.fromJson<JsonObject>(it, JsonObject::class.java)
            }
        } catch(e: Exception) {
            LOG.warnAndDebugDetails("Cannot read auth-data from file '${file.absolutePath}'", e)
            return false
        }

        if (obj == null) {
            LOG.warn("Stored object is null")
            return false
        }

        val data = getDataFromJsonObject(obj) ?: return false

        myDataLock.write {
            myData.clear()
            for ((key, value) in data) {
                myData.put(key, value.toMutableList())
            }
        }
        return true
    }
}