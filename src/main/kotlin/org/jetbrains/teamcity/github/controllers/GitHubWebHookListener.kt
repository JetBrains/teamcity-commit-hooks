

package org.jetbrains.teamcity.github.controllers

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
import jetbrains.buildServer.web.util.WebUtil
import org.eclipse.egit.github.core.Repository
import org.eclipse.egit.github.core.client.GsonUtilsEx
import org.eclipse.egit.github.core.event.PingWebHookPayload
import org.eclipse.egit.github.core.event.PullRequestPayloadEx
import org.eclipse.egit.github.core.event.PushWebHookPayload
import org.intellij.lang.annotations.MagicConstant
import org.jetbrains.teamcity.github.*
import org.jetbrains.teamcity.github.util.WebHooksHelper
import org.springframework.http.MediaType
import org.springframework.web.servlet.ModelAndView
import java.io.*
import java.util.*
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletResponse.*

class GitHubWebHookListener(private val WebControllerManager: WebControllerManager,
                            private val AuthorizationInterceptor: AuthorizationInterceptor,
                            private val AuthDataStorage: AuthDataStorage,
                            private val UserModel: UserModelEx,
                            private val PullRequestMergeBranchChecker: PullRequestMergeBranchChecker,
                            private val WebHooksManager: WebHooksManager,
                            private val SecurityContext: SecurityContextEx,
                            private val webhooksHelper: WebHooksHelper
) : BaseController() {

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
        if (signature == null || signature.isBlank()) {
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
            content = LimitInputStream(request.inputStream, MaxPayloadSize).readBytes()
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
                    val pair = doHandlePingEvent(payload, hookInfo, user)
                    return pair?.let { simpleText(response, pair.first, pair.second) }
                }
                "push" -> {
                    val payload = GsonUtilsEx.fromJson(contentReader, PushWebHookPayload::class.java)
                    val pair = doHandlePushEvent(payload, hookInfo, user)
                    return pair?.let { simpleText(response, pair.first, pair.second) }
                }
                "pull_request" -> {
                    val payload = GsonUtilsEx.fromJson(contentReader, PullRequestPayloadEx::class.java)
                    val pair = doHandlePullRequestEvent(payload, hookInfo, user)
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

    private fun doHandlePingEvent(payload: PingWebHookPayload, hookInfo: WebHookInfo?, user: UserEx): Pair<Int, String>? {
        LOG.info("Received ping payload from webhook:${payload.hook_id}(${payload.hook.url}) for repo ${payload.repository?.owner?.login}/${payload.repository?.name}")
        if (hookInfo != null) {
            updateLastUsed(hookInfo)
        }

        return scheduleChangesCollection(payload.repository, user)
    }

    private fun doHandlePushEvent(payload: PushWebHookPayload, hookInfo: WebHookInfo?, user: UserEx): Pair<Int, String>? {
        LOG.info("Received push payload from webhook for repo ${payload.repository?.owner?.login}/${payload.repository?.name}")
        if (hookInfo != null) {
            updateLastUsed(hookInfo)
            updateBranches(hookInfo, payload.ref, payload.after)
        }

        return scheduleChangesCollection(payload.repository, user)
    }

    private fun doHandlePullRequestEvent(payload: PullRequestPayloadEx, hookInfo: WebHookInfo?, user: UserEx): Pair<Int, String>? {
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
        return scheduleChangesCollection(repository, user)
    }

    private fun scheduleChangesCollection(repository: Repository, user: UserEx): Pair<Int, String>? {
        return SecurityContext.runAs<Pair<Int, String>>(user) {
            val vcsRoots = webhooksHelper.findRelevantVcsRootInstances(repository)
            if (vcsRoots.isEmpty())
                SC_OK to "No relevant VCS roots found"
            else {
                webhooksHelper.checkForChanges(vcsRoots)
                val vcsRootIdSet = vcsRoots.map { it.parent.externalId }.toSet()
                val vcsRootIds = vcsRootIdSet.joinToString("\n")

                SC_ACCEPTED to "Checking for changes scheduled for ${vcsRoots.size} " +
                       StringUtil.pluralize("instance", vcsRoots.size) + " of the following VCS " +
                       StringUtil.pluralize("root", vcsRootIdSet.size) + ":\n" + vcsRootIds
            }
        }
    }

    private fun updateLastUsed(hookInfo: WebHookInfo) {
        WebHooksManager.updateLastUsed(hookInfo, Date())
    }

    private fun updateBranches(hookInfo: WebHookInfo, branch: String, commitSha: String) {
        WebHooksManager.updateBranchRevisions(hookInfo, mapOf(branch to commitSha))
    }
}

/**
 * Wraps another input stream, limiting the number of bytes which can be read.
 * Copied from Guava 13.0 and transformed to Kotlin
 *
 * @param in the input stream to be wrapped
 * @param limit the maximum number of bytes to be read
 */
class LimitInputStream(`in`: InputStream, limit: Long) : FilterInputStream(`in`) {
    private var left: Long
    private var mark: Long = -1

    @Throws(IOException::class)
    override fun available(): Int {
        return Math.min(`in`.available().toLong(), left).toInt()
    }

    @Synchronized
    override fun mark(readlimit: Int) {
        `in`.mark(readlimit)
        mark = left
        // it's okay to mark even if mark isn't supported, as reset won't work
    }

    @Throws(IOException::class)
    override fun read(): Int {
        if (left == 0L) {
            return -1
        }
        val result = `in`.read()
        if (result != -1) {
            --left
        }
        return result
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left == 0L) {
            return -1
        }
        val result = `in`.read(b, off, Math.min(len.toLong(), left).toInt())
        if (result != -1) {
            left -= result.toLong()
        }
        return result
    }

    @Synchronized
    @Throws(IOException::class)
    override fun reset() {
        if (!`in`.markSupported()) {
            throw IOException("Mark not supported")
        }
        if (mark == -1L) {
            throw IOException("Mark not set")
        }
        `in`.reset()
        left = mark
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        val skipped = `in`.skip(Math.min(n, left))
        left -= skipped
        return skipped
    }

    init {
        left = limit
    }
}