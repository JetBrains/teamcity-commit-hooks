package org.jetbrains.teamcity.github

import com.google.gson.Gson
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
import kotlin.collections.ArrayList
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * AuthData storage
 * Backend: 'commit-hooks/auth-data.json' file under pluginData folder
 *
 * It's safe to check modifications via modification counter since data values are unmodifiable
 *
 * Synchronization:
 * Internal data store - ReentrantReadWriteLock
 * File read/write (#load(), #persist()) - on object
 */
class AuthDataStorage(executorServices: ExecutorServices,
                      private val myServerPaths: ServerPaths,
                      private val myServerEventDispatcher: EventDispatcher<BuildServerListener>) {
    companion object {
        private val LOG: Logger = Util.getLogger(WebHooksStorage::class.java)
        private val ourDataTypeToken: TypeToken<Map<String, AuthData>> = object : TypeToken<Map<String, AuthData>>() {}
        internal val gson: Gson = GsonBuilder()
                .setPrettyPrinting()
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
                        val repository: GitHubRepositoryInfo?,
                        val connection: ConnectionInfo) {
        companion object {
            fun fromJson(string: String): AuthData? = gson.fromJson(string, AuthData::class.java)
        }

        fun toJson(): String = gson.toJson(this)
    }

    private val myData = TreeMap<String, AuthData>()
    private val myDataLock = ReentrantReadWriteLock()
    private var myDataModificationCounter: Int = 0
    private var myStoredDataModificationCounter: Int = 0

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
        LOG.info("Created auth data $data")
        return data
    }

    fun store(data: AuthData) {
        myDataLock.write {
            myData.remove(data.public)
            myData.put(data.public, data)
            myDataModificationCounter++
        }
        LOG.info("Stored auth data $data")
        schedulePersisting()
    }

    fun remove(data: AuthData) {
        myDataLock.write {
            myData.remove(data.public)?.run {
                myDataModificationCounter++
            }
        }
        LOG.info("Removed auth data $data")
        schedulePersisting()
    }

    fun remove(datas: Collection<AuthData>) {
        if (datas.isEmpty()) return
        val keysToRemove = datas.map { it.public }.toHashSet()
        if (keysToRemove.isEmpty()) return
        myDataLock.write {
            if (myData.keys.removeAll(keysToRemove)) myDataModificationCounter++
        }
        LOG.info("Removed auth data $datas")
        schedulePersisting()
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
            if (keysToRemove.isEmpty()) break
            myDataLock.write {
                myData.keys.removeAll(keysToRemove)
                myDataModificationCounter++
            }
            schedulePersisting()
        }
        LOG.info("Removed all auth data related for user $userId")
    }

    fun delete(pubKey: String) {
        myDataLock.write {
            myData.remove(pubKey)?.run {
                myDataModificationCounter++
            }
        }
        LOG.info("Removed auth data for pubkey $pubKey")
        schedulePersisting()
    }

    // NOTE: Should not be called inside myDataLock
    private fun schedulePersisting() {
        LOG.debug("Scheduling persisting of internal storage onto disk")
        assert(!myDataLock.isWriteLockedByCurrentThread)
        try {
            myExecutor.submit { persist() }
        } catch (e: RejectedExecutionException) {
            persist()
        }
    }

    @Synchronized private fun persist(): Boolean {
        val (data, counter) = myDataLock.read {
            if (myDataModificationCounter == myStoredDataModificationCounter) {
                LOG.info("Storage is not modified, nothing to save on disk")
                return true
            }
            TreeMap(myData) to myDataModificationCounter
        }
        LOG.info("Persisting internal storage onto disk, MC=$counter, SMC=$myStoredDataModificationCounter")

        val file = getStorageFile()

        try {
            FileUtil.createParentDirs(file)
            val writer = file.writer(Charsets.UTF_8).buffered()
            writer.use {
                gson.toJson(data, ourDataTypeToken.type, it)
            }
            myDataLock.write { myStoredDataModificationCounter = counter }
            return true
        } catch(e: Exception) {
            LOG.warnAndDebugDetails("Cannot write auth-data to file '${file.absolutePath}'", e)
            return false
        }
    }

    @Synchronized private fun load(): Boolean {
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

        LOG.info("Loaded ${map.size} elements into internal storage from disk")

        myDataLock.write {
            myData.clear()
            myData.putAll(map)
            val counter = Math.max(myDataModificationCounter, myStoredDataModificationCounter) + 1
            myDataModificationCounter = counter
            myStoredDataModificationCounter = counter
        }
        return true
    }

    private fun getStorageFile(): File {
        return File(myServerPaths.pluginDataDirectory, "commit-hooks/auth-data.json")
    }

    fun findAllForRepository(repository: GitHubRepositoryInfo): List<AuthData> {
        return myData.values.filter { it.repository == repository }
    }

    fun getAll(): Collection<AuthData> {
        return ArrayList(myData.values)
    }
}