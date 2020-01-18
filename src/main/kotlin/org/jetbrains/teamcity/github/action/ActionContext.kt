/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.teamcity.github.action

import jetbrains.buildServer.serverSide.WebLinks
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryId
import org.eclipse.egit.github.core.client.RequestException
import org.jetbrains.teamcity.github.*
import org.jetbrains.teamcity.github.controllers.GitHubWebHookListener
import org.jetbrains.teamcity.github.controllers.Status
import org.jetbrains.teamcity.github.controllers.bad
import java.net.HttpURLConnection
import java.util.*

open class ActionContext(val storage: WebHooksStorage,
                         val authDataStorage: AuthDataStorage,
                         protected val links: WebLinks) {

    companion object {
        private val LOG = Util.getLogger(ActionContext::class.java)
    }

    fun getCallbackUrl(authData: AuthDataStorage.AuthData? = null): String {
        // It's not possible to add some url parameters, since GitHub ignores that part of url
        val base = links.rootUrl.removeSuffix("/") + GitHubWebHookListener.PATH
        if (authData == null) {
            return base
        }
        return base + '/' + authData.public
    }

    fun updateHooks(server: String, repo: RepositoryId, filtered: List<RepositoryHook>): Map<RepositoryHook, WebHooksStorage.HookInfo> {
        // TODO: Report more than one active webhook in storage as misconfiguration
        if (filtered.isEmpty()) {
            // Mark old hooks as removed
            storage.update(server, repo) {
                it.status = Status.MISSING
            }
            return emptyMap()
        }
        val result = HashMap<RepositoryHook, WebHooksStorage.HookInfo>()
        val hooks = storage.getHooks(server, repo).toMutableList()

        val missing = hooks.any { hi ->
            !filtered.any { rh -> hi.isSame(rh) }
        }
        if (missing) {
            storage.update(server, repo) { hi ->
                val rh = filtered.firstOrNull { rh -> hi.isSame(rh) }
                if (rh == null) {
                    hi.status = Status.MISSING
                } else if (!rh.isActive) {
                    // TODO: Should check that status is OK?
                    hi.status = Status.DISABLED
                } else {
                    // TODO: Should update status?
                    if (hi.status in listOf(Status.MISSING, Status.DISABLED)) {
                        hi.status = Status.WAITING_FOR_SERVER_RESPONSE
                    }
                }
            }
        }

        for (hook in filtered) {
            if (hooks.isEmpty()) {
                result.put(hook, addHook(hook)!!)
            } else {
                val same = hooks.firstOrNull { it.isSame(hook) }
                if (same != null) {
                    result.put(hook, same)
                    continue
                } else {
                    result.put(hook, addHook(hook)!!)
                }
            }
        }
        return result
    }

    fun updateOneHook(server: String, repo: RepositoryId, rh: RepositoryHook): WebHooksStorage.HookInfo? {
        val hooks = storage.getHooks(server, repo).toMutableList()
        var result: WebHooksStorage.HookInfo? = null
        if (!hooks.any { it.isSame(rh) }) {
            return addHook(rh)
        } else {
            storage.update(server, repo) {
                if (it.isSame(rh)) {
                    if (!rh.isActive) {
                        it.status = Status.DISABLED
                    } else {
                        // TODO: Should update status?
                        if (it.status in listOf(Status.MISSING, Status.DISABLED)) {
                            it.status = Status.WAITING_FOR_SERVER_RESPONSE
                        }
                    }
                    result = it
                }
            }
        }
        return result
    }

    fun addHook(created: RepositoryHook): WebHooksStorage.HookInfo? {
        val callbackUrl = created.callbackUrl
        if (callbackUrl == null) {
            LOG.warn("Received RepositoryHook without callback url, ignoring it")
            return null
        }
        val info = storage.getOrAdd(created)
        // If there was already hook info, update status
        info.status = created.getStatus()
        return info
    }

    fun getHook(info: GitHubRepositoryInfo): WebHooksStorage.HookInfo? {
        val hooks = storage.getHooks(info)
        return hooks.firstOrNull { !it.status.bad } ?: hooks.firstOrNull()
    }

    @Throws(GitHubAccessException::class)
    fun handleCommonErrors(e: RequestException) {
        when (e.status) {
            HttpURLConnection.HTTP_INTERNAL_ERROR -> {
                throw GitHubAccessException(GitHubAccessException.Type.InternalServerError)
            }
            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                throw GitHubAccessException(GitHubAccessException.Type.InvalidCredentials)
            }
        }
    }
}