package org.jetbrains.teamcity.github

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.BuildServerListener
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.util.cache.CacheProvider
import jetbrains.buildServer.util.cache.SCacheImpl
import org.eclipse.egit.github.core.RepositoryId
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

public class WebHooksStorage(private val myCacheProvider: CacheProvider,
                             private val myServerEventDispatcher: EventDispatcher<BuildServerListener>) {
    companion object {
        private val LOG: Logger = Logger.getInstance(WebHooksStorage::class.java.name)
    }

    data class HookInfo(val id: Long, val url: String) {
        var correct: Boolean = true
        var lastUsed: Date? = null
        var lastBranchRevisions: MutableMap<String, String>? = null
    }

    class PerServerMap : HashMap<RepositoryId, HookInfo>();
    private val myCache = myCacheProvider.getOrCreateCache("WebHooksCache", PerServerMap::class.java)


    private val myCacheLock = ReentrantReadWriteLock()

    private val myServerListener = object : BuildServerAdapter() {

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

    fun storeHook(info: VcsRootGitHubInfo, hook: HookInfo) {
        storeHook(info.server, info.getRepositoryId(), hook)
    }

    fun storeHook(server: String, repo: RepositoryId, hook: HookInfo) {
        myCacheLock.write {
            val map = myCache.fetch(server, { PerServerMap() });
            map.put(repo, hook)
            myCache.write(server, map)
        }
    }

    fun getHook(info: VcsRootGitHubInfo): WebHooksStorage.HookInfo? {
        // TODO: Populate map in background
        myCacheLock.read {
            val map = myCache.read(info.server) ?: return null
            val hook = map[info.getRepositoryId()] ?: return null
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