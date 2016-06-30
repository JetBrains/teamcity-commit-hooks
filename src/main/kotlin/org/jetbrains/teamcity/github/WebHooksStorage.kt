package org.jetbrains.teamcity.github

import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.BuildServerListener
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.util.cache.CacheProvider
import jetbrains.buildServer.util.cache.SCacheImpl
import org.eclipse.egit.github.core.RepositoryId
import org.jetbrains.teamcity.github.json.SimpleDateTypeAdapter
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class WebHooksStorage(private val myCacheProvider: CacheProvider,
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
                        var correct: Boolean = true,
                        var lastUsed: Date? = null,
                        var lastBranchRevisions: MutableMap<String, String>? = null,
                        val callbackUrl: String) {
        companion object {
            private val gson = GsonBuilder().registerTypeAdapter(Date::class.java, SimpleDateTypeAdapter).create()

            fun fromJson(string: String): HookInfo? = gson.fromJson(string, HookInfo::class.java)
        }

        fun toJson(): String = gson.toJson(this)
    }


    private val myCache = myCacheProvider.getOrCreateCache("WebHooksCache", String::class.java)


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

    fun store(info: GitHubRepositoryInfo, hook: HookInfo) {
        store(info.server, info.getRepositoryId(), hook)
    }

    fun store(server: String, repo: RepositoryId, hook: HookInfo) {
        myCacheLock.write {
            myCache.write(toKey(server, repo), hook.toJson())
        }
    }

    fun add(info: GitHubRepositoryInfo, builder: () -> HookInfo) {
        add(info.server, info.getRepositoryId(), builder)
    }

    /**
     * Adds hooks if it not existed previously
     */
    fun add(server: String, repo: RepositoryId, builder: () -> HookInfo): HookInfo {
        val hook = getHook(server, repo)
        if (hook != null) return hook
        myCacheLock.write {
            var info: HookInfo? = myCache.read(toKey(server, repo))?.let { HookInfo.fromJson(it) }
            if (info != null) {
                return info
            }
            info = builder()
            myCache.write(toKey(server, repo), info.toJson())
            return info
        }
    }

    fun delete(info: GitHubRepositoryInfo) {
        delete(info.server, info.getRepositoryId())
    }

    fun delete(server: String, repo: RepositoryId) {
        myCacheLock.write {
            myCache.invalidate(toKey(server, repo))
        }
    }

    fun update(info: GitHubRepositoryInfo, update: (HookInfo) -> Unit): Boolean {
        return update(info.server, info.getRepositoryId(), update)
    }

    fun update(server: String, repo: RepositoryId, update: (HookInfo) -> Unit): Boolean {
        myCacheLock.write {
            val hook = myCache.read(toKey(server, repo))?.let { HookInfo.fromJson(it) } ?: return false
            update(hook)
            myCache.write(toKey(server, repo), hook.toJson())
            return true
        }
    }

    fun getHook(info: GitHubRepositoryInfo): WebHooksStorage.HookInfo? {
        return getHook(info.server, info.getRepositoryId())
    }

    fun getHook(server: String, repo: RepositoryId): WebHooksStorage.HookInfo? {
        // TODO: Populate map in background
        myCacheLock.read {
            return myCache.read(toKey(server, repo))?.let { HookInfo.fromJson(it) }
        }
    }

    fun isHasIncorrectHooks(): Boolean {
        val keys = myCacheLock.read {
            myCache.keys
        }
        for (key in keys) {
            myCacheLock.read {
                val info = myCache.read(key)?.let { HookInfo.fromJson(it) }
                if (info != null) {
                    if (!info.correct) return true
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
                val info = myCache.read(key)?.let { HookInfo.fromJson(it) }
                if (info != null) {
                    if (!info.correct) {
                        val (server, owner, name) = fromKey(key)
                        result.add(GitHubRepositoryInfo(server, owner, name) to info)
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
                val info = myCache.read(key)?.let { HookInfo.fromJson(it) }
                if (info != null) {
                    val (server, owner, name) = fromKey(key)
                    result.add(GitHubRepositoryInfo(server, owner, name) to info)
                }
            }
        }
        return result
    }

}