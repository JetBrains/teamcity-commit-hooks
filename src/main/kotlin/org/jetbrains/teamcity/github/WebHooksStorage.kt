package org.jetbrains.teamcity.github

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.BuildServerListener
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.util.cache.CacheProvider
import jetbrains.buildServer.util.cache.SCacheImpl
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryId
import org.jetbrains.teamcity.github.controllers.Status
import org.jetbrains.teamcity.github.controllers.bad
import org.jetbrains.teamcity.github.json.SimpleDateTypeAdapter
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class WebHooksStorage(cacheProvider: CacheProvider,
                      private val myServerEventDispatcher: EventDispatcher<BuildServerListener>) {
    companion object {
        private val LOG: Logger = Logger.getInstance(WebHooksStorage::class.java.name)

        fun toKey(server: String, repo: RepositoryId): String {
            return buildString {
                append(server.trimEnd('/'))
                append('/')
                append(repo.generateId())
            }
        }

        fun fromKey(key: String): Triple<String, String, String> {
            val split = key.split('/')
            if (split.size < 3) throw IllegalArgumentException("Not an proper key: \"$key\"")
            val name = split[split.lastIndex]
            val owner = split[split.lastIndex - 1]
            val server = split.dropLast(2).joinToString("/")
            return Triple(server, owner, name)
        }
    }

    data class HookInfo(val id: Long,
                        val url: String, // API URL
                        var status: Status,
                        var lastUsed: Date? = null,
                        var lastBranchRevisions: MutableMap<String, String>? = null,
                        val callbackUrl: String) {
        companion object {
            val gson: Gson = GsonBuilder().registerTypeAdapter(Date::class.java, SimpleDateTypeAdapter).create()!!
            private val listType = object : TypeToken<List<HookInfo>>() {}.type

            private fun oneFromJson(string: String): HookInfo? = gson.fromJson(string, HookInfo::class.java)
            private fun listFromJson(string: String): List<HookInfo> = gson.fromJson(string, listType) ?: emptyList()

            fun fromJson(json: String): List<HookInfo> {
                if (json.startsWith("{")) {
                    return listOf(HookInfo.oneFromJson(json)).filterNotNull()
                } else if (json.startsWith("[")) {
                    return HookInfo.listFromJson(json)
                } else {
                    throw IllegalArgumentException("Unexpected content: ${json}")
                }
            }

            fun toJson(hooks: List<HookInfo>): String {
                return gson.toJson(hooks)
            }
        }

        @Deprecated("")
        fun toJson(): String = gson.toJson(this)

        fun isSame(other: HookInfo): Boolean {
            return id == other.id && url == other.url && callbackUrl == other.callbackUrl
        }

        fun isSame(hook: RepositoryHook): Boolean {
            return id == hook.id && url == hook.url && callbackUrl == hook.callbackUrl
        }
    }


    private val myCache = cacheProvider.getOrCreateCache("WebHooksCache", String::class.java)


    private val myCacheLock = ReentrantReadWriteLock()

    private val myServerListener = object : BuildServerAdapter() {
        override fun serverStartup() {
            myCacheLock.write {
                if (myCache is SCacheImpl) {
                    if (!myCache.isAlive) {
                        LOG.warn("Cache ${myCache.name} is not alive")
                        return
                    }
                }
                LOG.info("Cache keys: ${myCache.keys}")
            }
        }

        override fun serverShutdown() {
            myCacheLock.write {
                if (myCache is SCacheImpl) {
                    if (!myCache.isAlive) {
                        LOG.warn("Cache ${myCache.name} is not alive")
                        return
                    }
                }
                myCache.flush()
            }
        }
    }

    fun init(): Unit {
        myServerEventDispatcher.addListener(myServerListener)
    }

    fun destroy(): Unit {
        myServerEventDispatcher.removeListener(myServerListener)
    }

    /**
     * Adds hooks if it not existed previously
     */
    fun add(server: String, repo: RepositoryId, builder: () -> HookInfo): HookInfo {
        val toAdd = builder()

        val hooks = getHooks(server, repo)
        val hook = hooks.firstOrNull { it.isSame(toAdd) }
        if (hook != null) return hook

        myCacheLock.write {
            @Suppress("NAME_SHADOWING")
            val hooks = getHooks(server, repo).toMutableList()
            @Suppress("NAME_SHADOWING")
            val hook = hooks.firstOrNull { it.isSame(toAdd) }
            if (hook != null) return hook

            hooks.add(toAdd)
            myCache.write(toKey(server, repo), HookInfo.toJson(hooks))
            return toAdd
        }
    }

    fun delete(info: GitHubRepositoryInfo) {
        delete(info.server, info.getRepositoryId())
    }

    private fun delete(server: String, repo: RepositoryId) {
        myCacheLock.write {
            myCache.invalidate(toKey(server, repo))
        }
    }

    fun update(info: GitHubRepositoryInfo, update: (HookInfo) -> Unit): Boolean {
        return update(info.server, info.getRepositoryId(), update)
    }

    fun update(server: String, repo: RepositoryId, update: (HookInfo) -> Unit): Boolean {
        myCacheLock.write {
            val hooks = myCache.read(toKey(server, repo))?.let { HookInfo.fromJson(it) }?.toMutableList() ?: return false
            for (hook in hooks) {
                update(hook)
            }
            myCache.write(toKey(server, repo), HookInfo.toJson(hooks))
            return true
        }
    }

    fun getHooks(info: GitHubRepositoryInfo): List<WebHooksStorage.HookInfo> {
        return getHooks(info.server, info.getRepositoryId())
    }

    fun getHooks(server: String, repo: RepositoryId): List<WebHooksStorage.HookInfo> {
        val read = myCacheLock.read {
            myCache.read(toKey(server, repo))
        } ?: return emptyList()
        return HookInfo.fromJson(read)
    }

    fun isHasIncorrectHooks(): Boolean {
        val keys = myCacheLock.read {
            myCache.keys
        }
        for (key in keys) {
            myCacheLock.read {
                val hooks = myCache.read(key)?.let { HookInfo.fromJson(it) }
                if (hooks != null) {
                    if (hooks.any { it.status.bad }) return true
                }
            }
        }
        return false
    }

    fun getIncorrectHooks(): List<Pair<GitHubRepositoryInfo, WebHooksStorage.HookInfo>> {
        val keys = myCacheLock.read {
            myCache.keys
        }
        val result = ArrayList<Pair<GitHubRepositoryInfo, WebHooksStorage.HookInfo>>()
        for (key in keys) {
            myCacheLock.read {
                val hooks = myCache.read(key)?.let { HookInfo.fromJson(it) }
                if (hooks != null) {
                    val bad = hooks.filter { it.status.bad }
                    if (bad.isNotEmpty()) {
                        val (server, owner, name) = fromKey(key)
                        bad.map { GitHubRepositoryInfo(server, owner, name) to it }.toCollection(result)
                    }
                }
            }
        }
        return result
    }

    fun getAll(): List<Pair<GitHubRepositoryInfo, HookInfo>> {
        val keys = myCacheLock.read {
            myCache.keys
        }
        val result = ArrayList<Pair<GitHubRepositoryInfo, WebHooksStorage.HookInfo>>()
        for (key in keys) {
            myCacheLock.read {
                val hooks = myCache.read(key)?.let { HookInfo.fromJson(it) }
                if (hooks != null) {
                    val (server, owner, name) = fromKey(key)
                    hooks.map { GitHubRepositoryInfo(server, owner, name) to it }.toCollection(result)
                }
            }
        }
        return result
    }

}