package org.jetbrains.teamcity.github

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.BuildServerListener
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.util.cache.CacheProvider
import jetbrains.buildServer.util.cache.SCacheImpl
import org.eclipse.egit.github.core.RepositoryId
import java.io.Serializable
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

public class WebHooksStorage(private val myCacheProvider: CacheProvider,
                             private val myServerEventDispatcher: EventDispatcher<BuildServerListener>) {
    companion object {
        private val LOG: Logger = Logger.getInstance(WebHooksStorage::class.java.name)
        private val CACHE_VALUE_CALCULATOR = { PerServerMap() }
    }

    data class HookInfo(val id: Long,
                        val url: String, // API URL
                        var correct: Boolean = true,
                        var lastUsed: Date? = null,
                        var lastBranchRevisions: MutableMap<String, String>? = null) : Serializable {
        companion object {
            private val serialVersionUID = -363494826767181860L
        }
    }

    class PerServerMap : HashMap<RepositoryId, HookInfo>(), Serializable {
        companion object {
            private val serialVersionUID = 363494826767181860L
        }
    };
    private val myCache = myCacheProvider.getOrCreateCache("WebHooksCache", PerServerMap::class.java)


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

    fun store(info: VcsRootGitHubInfo, hook: HookInfo) {
        store(info.server, info.getRepositoryId(), hook)
    }

    fun store(server: String, repo: RepositoryId, hook: HookInfo) {
        myCacheLock.write {
            val map = myCache.fetch(server, CACHE_VALUE_CALCULATOR);
            map.put(repo, hook)
            myCache.write(server, map)
        }
    }

    fun add(info: VcsRootGitHubInfo, builder: () -> HookInfo) {
        add(info.server, info.getRepositoryId(), builder)
    }

    /**
     * Adds hooks if it not existed previously
     */
    fun add(server: String, repo: RepositoryId, builder: () -> HookInfo): HookInfo {
        val hook = getHook(server, repo)
        if (hook != null) return hook
        myCacheLock.write {
            val map = myCache.fetch(server, CACHE_VALUE_CALCULATOR);
            var info = map[repo]
            if (info != null) {
                return info
            }
            info = builder()
            map.put(repo, info)
            myCache.write(server, map)
            return info
        }
    }

    fun delete(server: String, repo: RepositoryId) {
        myCacheLock.write {
            val map = myCache.read(server) ?: return
            val hook = map[repo] ?: return
            map.remove(repo)
            myCache.write(server, map)
        }
    }

    fun update(info: VcsRootGitHubInfo, update: (HookInfo) -> Unit): Boolean {
        return update(info.server, info.getRepositoryId(), update)
    }

    fun update(server: String, repo: RepositoryId, update: (HookInfo) -> Unit): Boolean {
        myCacheLock.write {
            val map = myCache.read(server) ?: return false
            val hook = map[repo] ?: return false
            update(hook)
            map.put(repo, hook)
            return true
        }
    }

    fun getHook(info: VcsRootGitHubInfo): WebHooksStorage.HookInfo? {
        return getHook(info.server, info.getRepositoryId())
    }

    fun getHook(server: String, repositoryId: RepositoryId): WebHooksStorage.HookInfo? {
        // TODO: Populate map in background
        myCacheLock.read {
            val map = myCache.read(server) ?: return null
            val hook = map[repositoryId] ?: return null
            return hook
        }
    }

    fun isHasIncorrectHooks(): Boolean {
        val keys = myCacheLock.read {
            myCache.keys
        }
        for (key in keys) {
            myCacheLock.read {
                val map = myCache.read(key)
                if (map != null) {
                    if (map.values.any { !it.correct }) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun getIncorrectHooks(): List<Pair<VcsRootGitHubInfo, WebHooksStorage.HookInfo>> {
        val keys = myCacheLock.read {
            myCache.keys
        }
        val result = ArrayList<Pair<VcsRootGitHubInfo, WebHooksStorage.HookInfo>>()
        for (key in keys) {
            myCacheLock.read {
                val map = myCache.read(key)
                if (map != null) {
                    result.addAll(map.entries.filter { !it.value.correct }.map { VcsRootGitHubInfo(key, it.key.owner, it.key.name) to it.value })
                }
            }
        }
        return result
    }

}