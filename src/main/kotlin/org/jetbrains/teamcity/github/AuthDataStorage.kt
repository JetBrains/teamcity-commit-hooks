package org.jetbrains.teamcity.github

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.BuildServerListener
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.util.cache.CacheProvider
import jetbrains.buildServer.util.cache.SCacheImpl
import java.io.Serializable
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
                        val secretKey: String,
                        val public: String) : Serializable {
        companion object {
            val serialVersionUID = 987149781384712L
        }
    }

    private val myCache = myCacheProvider.getOrCreateCache("WebHooksAuthCache", AuthData::class.java)

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
            return myCache.read(public)
        }
    }

    fun save(user: SUser, public: String, secret: String) {
        val data = AuthData(user.id, secret, public)
        myCacheLock.write {
            myCache.invalidate(public)
            myCache.write(public, data)
        }
    }

    fun create(user: SUser): AuthData {
        val (public, secret) = generate()
        val data = AuthData(user.id, secret, public)
        myCacheLock.write {
            myCache.invalidate(public)
            myCache.write(public, data)
        }
        return data
    }

    private fun generate(): Pair<String, String> {
        val public = UUID.randomUUID()
        val secret = UUID.randomUUID()
        return public.toString() to (secret.toString())
    }

    fun removeAllForUser(userId: Long) {
        0.rangeTo(2).forEach {
            val keysToRemove =
                    myCacheLock.read {
                        return@read myCache.keys.filter {
                            val data = myCache.read(it)
                            userId == data?.userId
                        }
                    }
            myCacheLock.write {
                keysToRemove.forEach { myCache.invalidate(it) }
            }
        }
    }

}