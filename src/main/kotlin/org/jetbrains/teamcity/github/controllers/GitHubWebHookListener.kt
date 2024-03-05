/*
 * Copyright 2000-2021 JetBrains s.r.o.
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

package org.jetbrains.teamcity.github.controllers

import com.google.common.io.LimitInputStream
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import jetbrains.buildServer.controllers.AuthorizationInterceptor
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.SecurityContextEx
import jetbrains.buildServer.serverSide.TeamCityProperties
import jetbrains.buildServer.users.UserModelEx
import jetbrains.buildServer.users.impl.UserEx
import jetbrains.buildServer.util.FileUtil
import jetbrains.buildServer.util.StringUtil
import jetbrains.buildServer.util.ThreadUtil
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.SessionUser
import jetbrains.buildServer.web.util.WebUtil
import org.eclipse.egit.github.core.client.GsonUtilsEx
import org.eclipse.egit.github.core.event.PingWebHookPayload
import org.eclipse.egit.github.core.event.PullRequestPayloadEx
import org.eclipse.egit.github.core.event.PushWebHookPayload
import org.intellij.lang.annotations.MagicConstant
import org.jetbrains.teamcity.github.*
import org.jetbrains.teamcity.github.util.LayeredHttpServletRequest
import org.springframework.http.MediaType
import org.springframework.web.servlet.ModelAndView
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletResponse.*
import javax.servlet.http.HttpServletResponseWrapper

class GitHubWebHookListener(private val WebControllerManager: WebControllerManager,
                            private val AuthorizationInterceptor: AuthorizationInterceptor,
                            private val AuthDataStorage: AuthDataStorage,
                            private val UserModel: UserModelEx,
                            private val PullRequestMergeBranchChecker: PullRequestMergeBranchChecker,
                            private val WebHooksManager: WebHooksManager,
                            private val SecurityContext: SecurityContextEx) : BaseController() {

    companion object {
        const val PATH = "/app/hooks/github"
        const val X_GitHub_Event = "X-GitHub-Event"
        const val X_Hub_Signature = "X-Hub-Signature"

        val SupportedEvents = listOf("ping", "push", "pull_request")

        val MaxPayloadSize = TeamCityProperties.getLong("teamcity.githubWebhooks.payload.maxKb", 5 * 1024L) * 1024L

        private val AcceptedPullRequestActions = listOf("opened", "edited", "closed", "reopened", "synchronize", "labeled", "unlabeled")

        private val LOG = Util.getLogger(GitHubWebHookListener::class.java)

        fun getPubKeyFromRequestPath(path: String): String? {
            val indexOfPathPart = path.indexOf("$PATH/")
            return if (indexOfPathPart != -1) {
                val substring = path.substring(indexOfPathPart + PATH.length + 1)
                if (substring.isNotBlank()) substring else null
            } else {
                null
            }
        }

        private fun simpleText(response: HttpServletResponse, @MagicConstant(valuesFromClass = HttpServletResponse::class) status: Int, text: String): ModelAndView? {
            response.status = status
            response.contentType = MediaType.TEXT_PLAIN_VALUE
            response.characterEncoding = "UTF-8"
            response.setContentLength(text.length + 2)
            response.outputStream.use {
                it.println(text)
            }
            return null
        }
    }

    fun register() {
        // Looks like GET is not necessary, POST is enough
        setSupportedMethods(METHOD_POST)
        WebControllerManager.registerController(PATH, this)
        WebControllerManager.registerController("$PATH/**", this)
        AuthorizationInterceptor.addPathNotRequiringAuth(PATH)
        AuthorizationInterceptor.addPathNotRequiringAuth("$PATH/**")
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        //TODO: Check User-Agent
        // From documentation: Also, the User-Agent for the requests will have the prefix GitHub-Hookshot/.

        val eventType: String? = request.getHeader(X_GitHub_Event)
        @Suppress("IfNullToElvis")
        if (eventType == null) {
            return simpleText(response, SC_BAD_REQUEST, "'$X_GitHub_Event' header is missing")
        }

        if (!SupportedEvents.contains(eventType)) {
            LOG.info("Received unsupported event type '$eventType', ignoring")
            return simpleText(response, SC_ACCEPTED, "Unsupported event type '$eventType'")
        }

        val signature = request.getHeader(X_Hub_Signature)
        if (signature == null || signature.isNullOrBlank()) {
            LOG.warn("Received event without signature ($X_Hub_Signature header)")
            return simpleText(response, SC_BAD_REQUEST, "'$X_Hub_Signature' header is missing")
        }

        val path = WebUtil.getPathWithoutAuthenticationType(request)
        val pubKey: String? = getPubKeyFromRequestPath(path)
        if (pubKey == null) {
            LOG.warn("Received hook event with signature header but without public key part of url")
            return simpleText(response, SC_BAD_REQUEST, "'$X_Hub_Signature' is present but request url does not contains public key part")
        }

        LOG.debug("Received hook event with public key in path: $pubKey")

        val authData = getAuthData(pubKey)
        if (authData == null) {
            LOG.warn("No stored auth data (secret key) found for public key '$pubKey'")
            return simpleText(response, SC_NOT_FOUND, "No stored auth data (secret key) found for public key '$pubKey'. Seems hook created not by this TeamCity server. Reinstall hook via TeamCity UI.")
        }
        val userId = authData.userId
        val user = UserModel.findUserById(userId)
        if (user == null) {
            AuthDataStorage.removeAllForUser(userId)
            LOG.warn("User with id '$userId' not found")
            return simpleText(response, SC_NOT_FOUND, "User installed webhook no longer registered in TeamCity. Remove and reinstall webhook.")
        }

        val hookInfo = getHookInfoWithWaiting(authData, eventType)
        if (hookInfo == null) {
            // Seems local cache was cleared or it's a organization hook
            LOG.warn("No stored hook info found for public key '$pubKey' and repository '${authData.repository}'")
        }

        if (request.contentLength >= MaxPayloadSize) {
            val message = "Payload size exceed ${StringUtil.formatFileSize(MaxPayloadSize, 0)} limit"
            LOG.info("$message. Requests url: $path")
            return simpleText(response, SC_REQUEST_ENTITY_TOO_LARGE, message)
        }

        val content: ByteArray
        try {
            var estimatedSize = request.contentLength
            if (estimatedSize == -1) estimatedSize = 8 * 1024

            content = LimitInputStream(request.inputStream, MaxPayloadSize).readBytes(estimatedSize)
        } catch(e: IOException) {
            LOG.warnAndDebugDetails("Failed to read payload of $eventType event", e)
            return simpleText(response, SC_SERVICE_UNAVAILABLE, "Failed to read payload: ${e.message}")
        } finally {
            FileUtil.close(request.inputStream)
        }
        if (content.size >= MaxPayloadSize) {
            val message = "Payload size exceed ${StringUtil.formatFileSize(MaxPayloadSize, 0)} limit"
            LOG.info("$message. Requests url: $path")
            return simpleText(response, SC_REQUEST_ENTITY_TOO_LARGE, message)
        }

        if (!HMacUtil.checkHMac(content, authData.secret.toByteArray(charset("UTF-8")), signature)) {
            LOG.warn("HMac verification failed for $eventType event")
            return simpleText(response, SC_FORBIDDEN, "Payload signature verification failed. Ensure request url, '$X_Hub_Signature' header and payload are correct")
        }

        val contentReader = BufferedReader(InputStreamReader(ByteArrayInputStream(content), request.characterEncoding ?: "UTF-8"))

        try {
            when (eventType) {
                "ping" -> {
                    val payload = GsonUtilsEx.fromJson(contentReader, PingWebHookPayload::class.java)
                    val pair = doHandlePingEvent(payload, hookInfo, request, wrapResponseIfNeeded(response, authData), user)
                    return pair?.let { simpleText(response, pair.first, pair.second) }
                }
                "push" -> {
                    val payload = GsonUtilsEx.fromJson(contentReader, PushWebHookPayload::class.java)
                    val pair = doHandlePushEvent(payload, hookInfo, request, wrapResponseIfNeeded(response, authData), user)
                    return pair?.let { simpleText(response, pair.first, pair.second) }
                }
                "pull_request" -> {
                    val payload = GsonUtilsEx.fromJson(contentReader, PullRequestPayloadEx::class.java)
                    val pair = doHandlePullRequestEvent(payload, hookInfo, request, wrapResponseIfNeeded(response, authData), user)
                    return pair?.let { simpleText(response, pair.first, pair.second) }
                }
            }
        } catch(e: Exception) {
            val message = if (e is JsonSyntaxException || e is JsonIOException) {
                "Failed to parse payload"
            } else {
                "Failed to process request (event type is '$eventType')"
            }
            LOG.warnAndDebugDetails(message, e)
            return simpleText(response, SC_SERVICE_UNAVAILABLE, message + ": ${e.message}")
        }
        return null
    }

    private fun getHookInfoWithWaiting(authData: AuthDataStorage.AuthData, eventType: String?): WebHookInfo? {
        // There's possibility that listener invoked prior to 'CreateWebHookAction' finishes storing it in WebHooksManager
        // Seems it's ok to do that since we already checked that request presumable comes from GitHub
        for (i in 1..10) {
            val info = WebHooksManager.getHookForPubKey(authData)
            if (info != null) return info
            if (eventType != "ping") return null
            ThreadUtil.sleep(TimeUnit.SECONDS.toMillis(1))
        }
        return null
    }

    private fun getAuthData(subPath: String) = AuthDataStorage.find(subPath)

    private fun doHandlePingEvent(payload: PingWebHookPayload, hookInfo: WebHookInfo?, request: HttpServletRequest, response: HttpServletResponse, user: UserEx): Pair<Int, String>? {
        val url = payload.repository?.gitUrl
        LOG.info("Received ping payload from webhook:${payload.hook_id}(${payload.hook.url}) for repo ${payload.repository?.owner?.login}/${payload.repository?.name}")
        if (url == null) {
            val message = "Ping event payload have no repository url specified"
            LOG.warn(message)
            return SC_BAD_REQUEST to message
        }
        val info = Util.getGitHubInfo(url)
        if (info == null) {
            val message = "Cannot determine repository info from url '$url'"
            LOG.warn(message)
            return SC_SERVICE_UNAVAILABLE to message
        }
        if (hookInfo != null) {
            updateLastUsed(hookInfo)
        }

        return doForwardToRestApi(info, user, request, response)
    }

    private fun doHandlePushEvent(payload: PushWebHookPayload, hookInfo: WebHookInfo?, request: HttpServletRequest, response: HttpServletResponse, user: UserEx): Pair<Int, String>? {
        val url = payload.repository?.gitUrl
        LOG.info("Received push payload from webhook for repo ${payload.repository?.owner?.login}/${payload.repository?.name}")
        if (url == null) {
            val message = "Push event payload have no repository url specified"
            LOG.warn(message)
            return SC_BAD_REQUEST to message
        }
        val info = Util.getGitHubInfo(url)
        if (info == null) {
            val message = "Cannot determine repository info from url '$url'"
            LOG.warn(message)
            return SC_SERVICE_UNAVAILABLE to message
        }
        if (hookInfo != null) {
            updateLastUsed(hookInfo)
            updateBranches(hookInfo, payload.ref, payload.after)
        }

        return doForwardToRestApi(info, user, request, response)
    }

    private fun doHandlePullRequestEvent(payload: PullRequestPayloadEx, hookInfo: WebHookInfo?, request: HttpServletRequest, response: HttpServletResponse, user: UserEx): Pair<Int, String>? {
        if (payload.action !in AcceptedPullRequestActions) {
            LOG.info("Ignoring 'pull_request' event with action '${payload.action}' as unrelated for repo $hookInfo")
            return SC_ACCEPTED to "Unrelated action, expected one of $AcceptedPullRequestActions"
        }
        val repository = payload.pullRequest?.base?.repo
        val url = repository?.htmlUrl
        if (url == null) {
            val message = "pull_request' event payload has no repository url specified in object path 'pull_request.base.repo.html_url'"
            LOG.warn(message)
            return SC_BAD_REQUEST to message
        }
        LOG.info("Received pull_request payload from webhook for repo ${repository.owner?.login}/${repository.name}, action: ${payload.action}")
        val info = Util.getGitHubInfo(url)
        if (info == null) {
            val message = "Cannot determine repository info from url '$url'"
            LOG.warn(message)
            return SC_SERVICE_UNAVAILABLE to message
        }
        if (hookInfo != null) {
            updateLastUsed(hookInfo)
            val id = payload.number
            updateBranches(hookInfo, "refs/pull/$id/head", payload.pullRequest.head.sha)

            val mergeCommitSha = payload.pullRequest.mergeCommitSha
            val mergeBranchName = "refs/pull/$id/merge"
            if (!mergeCommitSha.isNullOrBlank()) {
                updateBranches(hookInfo, mergeBranchName, mergeCommitSha)
            } else if (hookInfo.lastBranchRevisions?.get(mergeBranchName).isNullOrEmpty()) {
                // Firstly discovered merge branch, probably PR is just created.
                // Lets wait for branch to appear in background (using REST API polling)
                // then notify git subsystem to schedule checking for changes (using mock rest request)
                PullRequestMergeBranchChecker.schedule(info, hookInfo, user, id)
            }
        }
        return doForwardToRestApi(info, user, request, response)
    }

    private fun doForwardToRestApi(info: GitHubRepositoryInfo, user: UserEx, request: HttpServletRequest, response: HttpServletResponse): Pair<Int, String>? {
        val httpId = info.id
        val sshId = info.server + ":" + info.owner + "/" + info.name
        val dispatcher = request.getRequestDispatcher("/app/rest/vcs-root-instances/commitHookNotification?" +
                                                      "locator=type:jetbrains.git,or:(property:(name:url,value:$httpId,matchType:contains,ignoreCase:true),property:(name:url,value:$sshId,matchType:contains,ignoreCase:true))")
        if (dispatcher != null) {
            val layered = LayeredHttpServletRequest(request)
            SessionUser.setUser(layered, user)
            layered.setAttribute("INTERNAL_REQUEST", true)
            SecurityContext.runAs(user) {
                dispatcher.forward(layered, response)
            }
            return null
        }
        return SC_SERVICE_UNAVAILABLE to "Cannot forward to REST API"
    }

    private fun updateLastUsed(hookInfo: WebHookInfo) {
        WebHooksManager.updateLastUsed(hookInfo, Date())
    }

    private fun updateBranches(hookInfo: WebHookInfo, branch: String, commitSha: String) {
        WebHooksManager.updateBranchRevisions(hookInfo, mapOf(branch to commitSha))
    }

    private fun wrapResponseIfNeeded(response: HttpServletResponse, authData: AuthDataStorage.AuthData): HttpServletResponse {
        if (authData.repository != null) return response

        // If per-organization GitHub webhook sends us information about repository TC not aware of,
        // TC server (REST API) would return 404,
        // After several non 2xx codes GitHub would no longer send webhooks at all.
        // We've to emulate that TC is aware of all repos
        return object : HttpServletResponseWrapper(response) {
            private fun fix404(sc: Int): Int = if (sc == 404) 200 else sc

            override fun setStatus(sc: Int) {
                super.setStatus(fix404(sc))
            }

            override fun setStatus(sc: Int, sm: String?) {
                super.setStatus(fix404(sc), sm)
            }

            override fun sendError(sc: Int, msg: String?) {
                super.sendError(fix404(sc), msg)
            }

            override fun sendError(sc: Int) {
                super.sendError(fix404(sc))
            }
        }
    }
}