package org.jetbrains.teamcity.github

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.BuildServerListener
import jetbrains.buildServer.serverSide.ServerPaths
import jetbrains.buildServer.serverSide.executors.ExecutorServices
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.util.FileUtil
import org.jetbrains.teamcity.github.json.SimpleDateTypeAdapter
import java.io.File
import java.util.*
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class AuthDataStorage(executorServices: ExecutorServices,
                      private val myServerPaths: ServerPaths,
                      private val myServerEventDispatcher: EventDispatcher<BuildServerListener>) {
    companion object {
        private val LOG: Logger = Logger.getInstance(WebHooksStorage::class.java.name)
        private val ourDataTypeToken: TypeToken<Map<String, AuthData>> = object : TypeToken<Map<String, AuthData>>() {}
        internal val gson = GsonBuilder()
                .registerTypeAdapter(Date::class.java, SimpleDateTypeAdapter)
                .create()
    }

    data class ConnectionInfo(val id: String,
                              val projectExternalId: String) {
        constructor(connection: OAuthConnectionDescriptor) : this(connection.id, connection.project.externalId)
    }

    data class AuthData(val userId: Long,
                        val public: String,
                        val secret: String,
                        val repository: GitHubRepositoryInfo,
                        val connection: ConnectionInfo) {
        companion object {
            fun fromJson(string: String): AuthData? = gson.fromJson(string, AuthData::class.java)
        }

        fun toJson(): String = gson.toJson(this)
    }

    private val myData = TreeMap<String, AuthData>()
    private val myDataLock = ReentrantReadWriteLock()

    private val myExecutor = executorServices.lowPriorityExecutorService

    private val myServerListener = object : BuildServerAdapter() {
        override fun serverStartup() {
            load()
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

    fun find(public: String): AuthData? {
        myDataLock.read {
            return myData[public]
        }
    }

    fun create(user: SUser, repository: GitHubRepositoryInfo, connection: OAuthConnectionDescriptor, store: Boolean = true): AuthData {
        val (public, secret) = generate()
        val data = AuthData(user.id, public, secret, repository, ConnectionInfo(connection))
        if (store) {
            store(data)
        }
        return data
    }

    fun store(data: AuthData) {
        myDataLock.write {
            myData.remove(data.public)
            myData.put(data.public, data)
        }
        schedulePersisting()
    }

    fun remove(data: AuthData) {
        myDataLock.write {
            myData.remove(data.public)?.run { schedulePersisting() }
        }
    }

    private fun generate(): Pair<String, String> {
        val public = UUID.randomUUID()
        val secret = UUID.randomUUID()
        return public.toString() to (secret.toString())
    }

    fun removeAllForUser(userId: Long) {
        while (true) {
            val keysToRemove: Collection<String> =
                    myDataLock.read {
                        myData.entries.filter { it.value.userId == userId }.map { it.key }
                    }
            if (keysToRemove.isEmpty()) return
            myDataLock.write {
                myData.keys.removeAll(keysToRemove)
            }
            schedulePersisting()
        }
    }

    fun delete(pubKey: String) {
        myDataLock.write {
            myData.remove(pubKey)?.run { schedulePersisting() }
        }
    }

    private fun schedulePersisting() {
        try {
            myExecutor.submit { persist() }
        } catch (e: RejectedExecutionException) {
            persist()
        }
    }

    // TODO: Check whether data was actually modified
    private fun persist(): Boolean {
        val data = myDataLock.read {
            TreeMap(myData)
        }

        val file = getStorageFile()

        try {
            FileUtil.createParentDirs(file)
            val writer = file.writer(Charsets.UTF_8).buffered()
            writer.use {
                gson.toJson(data, ourDataTypeToken.type, it)
            }
            return true
        } catch(e: Exception) {
            LOG.warnAndDebugDetails("Cannot write auth-data to file '${file.absolutePath}'", e)
            return false
        }
    }

    private fun load(): Boolean {
        val file = getStorageFile()

        val map: Map<String, AuthData>?
        try {
            FileUtil.createParentDirs(file)
            if (!file.isFile) return false
            map = file.reader(Charsets.UTF_8).buffered().use {
                gson.fromJson<Map<String, AuthData>>(it, ourDataTypeToken.type)
            }
        } catch(e: Exception) {
            LOG.warnAndDebugDetails("Cannot read auth-data from file '${file.absolutePath}'", e)
            return false
        }

        if (map == null) {
            LOG.warn("Stored map is null")
            return false
        }

        myDataLock.write {
            myData.clear()
            myData.putAll(map)
        }
        return true
    }

    private fun getStorageFile(): File {
        return File(myServerPaths.pluginDataDirectory, "commit-hooks/auth-data.json")
    }
}