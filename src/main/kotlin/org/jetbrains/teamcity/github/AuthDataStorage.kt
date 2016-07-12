package org.jetbrains.teamcity.github

import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.BuildServerListener
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.util.cache.CacheProvider
import jetbrains.buildServer.util.cache.SCacheImpl
import org.jetbrains.teamcity.github.json.SimpleDateTypeAdapter
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class AuthDataStorage(cacheProvider: CacheProvider,
                      private val myServerEventDispatcher: EventDispatcher<BuildServerListener>) {
    companion object {
        private val LOG: Logger = Logger.getInstance(WebHooksStorage::class.java.name)
    }

    data class ConnectionInfo(val id: String,
                              val projectExternalId: String) {
        constructor(connection: OAuthConnectionDescriptor) : this(connection.id, connection.project.externalId)
    }

    data class AuthData(val userId: Long,
                        val secret: String,
                        val public: String,
                        val repository: GitHubRepositoryInfo,
                        val connection: ConnectionInfo) {
        companion object {
            private val gson = GsonBuilder()
                    .registerTypeAdapter(Date::class.java, SimpleDateTypeAdapter)
                    .create()

            fun fromJson(string: String): AuthData? = gson.fromJson(string, AuthData::class.java)
        }

        fun toJson(): String = gson.toJson(this)
    }

    private val myCache = cacheProvider.getOrCreateCache("WebHooksAuthCache", String::class.java)

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

    fun create(user: SUser, repository: GitHubRepositoryInfo, connection: OAuthConnectionDescriptor, store: Boolean = true): AuthData {
        val (public, secret) = generate()
        val data = AuthData(user.id, secret, public, repository, ConnectionInfo(connection))
        if (store) {
            store(data)
        }
        return data
    }

    fun store(data: AuthData) {
        myCacheLock.write {
            myCache.invalidate(data.public)
            myCache.write(data.public, data.toJson())
        }
    }

    fun remove(data: AuthData) {
        myCacheLock.write {
            myCache.invalidate(data.public)
        }
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

    fun delete(pubKey: String) {
        myCacheLock.write {
            myCache.invalidate(pubKey)
        }
    }

}