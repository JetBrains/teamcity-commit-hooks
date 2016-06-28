package org.jetbrains.teamcity.github

import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.BuildServerListener
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.util.cache.CacheProvider
import jetbrains.buildServer.util.cache.SCacheImpl
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class AuthDataStorage(private val myCacheProvider: CacheProvider,
                      private val myServerEventDispatcher: EventDispatcher<BuildServerListener>) {
    companion object {
        private val LOG: Logger = Logger.getInstance(WebHooksStorage::class.java.name)
    }

    data class AuthData(val userId: Long,
                        val secret: String,
                        val public: String) {
        companion object {
            private val gson = GsonBuilder().create()
            fun fromJson(string: String): AuthData? = gson.fromJson(string, AuthData::class.java)
            fun toJson(info: AuthData): String = gson.toJson(info)
        }

        fun toJson(): String = AuthData.toJson(this)
    }

    private val myCache = myCacheProvider.getOrCreateCache("WebHooksAuthCache", String::class.java)

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

    fun find(public: String): AuthData? {
        myCacheLock.read {
            return myCache.read(public)?.let { AuthData.fromJson(it) }
        }
    }

    fun save(user: SUser, public: String, secret: String) {
        val data = AuthData(user.id, secret, public)
        myCacheLock.write {
            myCache.invalidate(public)
            myCache.write(public, data.toJson())
        }
    }

    fun create(user: SUser): AuthData {
        val (public, secret) = generate()
        val data = AuthData(user.id, secret, public)
        myCacheLock.write {
            myCache.invalidate(public)
            myCache.write(public, data.toJson())
        }
        return data
    }

    private fun generate(): Pair<String, String> {
        val public = UUID.randomUUID()
        val secret = UUID.randomUUID()
        return public.toString() to (secret.toString())
    }

    fun removeAllForUser(userId: Long) {
        for (i in 0..2) {
            val keysToRemove =
                    myCacheLock.read {
                        return@read myCache.keys.filter {
                            val data = myCache.read(it)
                            userId == data?.let { AuthData.fromJson(it) }?.userId
                        }
                    }
            if (keysToRemove.isEmpty()) return
            myCacheLock.write {
                keysToRemove.forEach { myCache.invalidate(it) }
            }
        }
    }

}