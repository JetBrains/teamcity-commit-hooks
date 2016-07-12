package org.jetbrains.teamcity.github.controllers

import com.google.common.io.LimitInputStream
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.controllers.AuthorizationInterceptor
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SecurityContextEx
import jetbrains.buildServer.serverSide.impl.VcsModificationChecker
import jetbrains.buildServer.users.UserModelEx
import jetbrains.buildServer.users.impl.UserEx
import jetbrains.buildServer.util.FileUtil
import jetbrains.buildServer.util.StringUtil
import jetbrains.buildServer.vcs.OperationRequestor
import jetbrains.buildServer.vcs.VcsManager
import jetbrains.buildServer.vcs.VcsRootInstance
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.WebUtil
import org.eclipse.egit.github.core.client.GsonUtilsEx
import org.eclipse.egit.github.core.event.PingWebHookPayload
import org.eclipse.egit.github.core.event.PushWebHookPayload
import org.jetbrains.teamcity.github.*
import org.springframework.web.servlet.ModelAndView
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class GitHubWebHookListener(private val WebControllerManager: WebControllerManager,
                            private val ProjectManager: ProjectManager,
                            private val VcsModificationChecker: VcsModificationChecker,
                            private val AuthorizationInterceptor: AuthorizationInterceptor,
                            private val AuthDataStorage: AuthDataStorage,
                            private val UserModel: UserModelEx,
                            private val SecurityContext: SecurityContextEx,
                            private val VcsManager: VcsManager,
                            private val WebHooksManager: WebHooksManager) : BaseController() {

    companion object {
        val PATH = "/app/hooks/github"
        val X_GitHub_Event = "X-GitHub-Event"
        val X_Hub_Signature = "X-Hub-Signature"

        val SupportedEvents = listOf("ping", "push")

        val MaxPayloadSize = 5 * FileUtil.MEGABYTE.toLong() + 1

        private val LOG = Logger.getInstance(GitHubWebHookListener::class.java.name)

        fun getPubKeyFromRequestPath(path: String): String? {
            val indexOfPathPart = path.indexOf(PATH + "/")
            val pubKey: String?
            if (indexOfPathPart != -1) {
                val substring = path.substring(indexOfPathPart + PATH.length + 1)
                pubKey = if (substring.isNotBlank()) substring else null
            } else {
                pubKey = null
            }
            return pubKey
        }
    }

    fun register(): Unit {
        // Looks like GET is not necessary, POST is enough
        setSupportedMethods(METHOD_POST)
        WebControllerManager.registerController(PATH, this)
        WebControllerManager.registerController(PATH + "/*", this)
        AuthorizationInterceptor.addPathNotRequiringAuth(PATH)
        AuthorizationInterceptor.addPathNotRequiringAuth(PATH + "/*")
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        //TODO: Check User-Agent
        // From documentation: Also, the User-Agent for the requests will have the prefix GitHub-Hookshot/.

        val eventType: String? = request.getHeader(X_GitHub_Event)
        if (eventType == null) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return simpleView("'$X_GitHub_Event' header is missing")
        }

        if (!SupportedEvents.contains(eventType)) {
            LOG.info("Received unsupported event type '$eventType', ignoring")
            response.status = HttpServletResponse.SC_ACCEPTED
            return simpleView("Unsupported event type '$eventType'")
        }

        val signature = request.getHeader(X_Hub_Signature)
        if (signature == null || signature.isNullOrBlank()) {
            LOG.warn("Received event without signature ($X_Hub_Signature header)")
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return simpleView("'$X_Hub_Signature' header is missing")
        }

        val path = WebUtil.getPathWithoutAuthenticationType(request)
        val pubKey: String? = getPubKeyFromRequestPath(path)
        if (pubKey == null) {
            LOG.warn("Received hook event with signature header but without public key part of url")
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return simpleView("'$X_Hub_Signature' is present but request url does not contains public key part")
        }

        LOG.debug("Received hook event with public key in path: $pubKey")

        val authData = getAuthData(pubKey)
        if (authData == null) {
            LOG.warn("No stored auth data (secret key) found for public key '$pubKey'")
            response.status = HttpServletResponse.SC_NOT_FOUND
            return simpleView("No stored auth data (secret key) found for public key '$pubKey'. Seems hook created not by this TeamCity server. Reinstall hook via TeamCity UI.")
        }

        val hookInfo = WebHooksManager.getHookForPubKey(authData.repository, pubKey)
        if (hookInfo == null) {
            // Seems local cache was cleared
            LOG.warn("No stored hook info found for public key '$pubKey' and repository '${authData.repository}'")
            AuthDataStorage.delete(pubKey)
            response.status = HttpServletResponse.SC_NOT_FOUND
            return simpleView("No stored hook info found for public key '$pubKey'. Seems hook created not by this TeamCity server. Reinstall hook via TeamCity UI.")
        }

        val content: ByteArray
        try {
            var estimatedSize = request.contentLength
            if (estimatedSize == -1) estimatedSize = 8 * 1024

            content = LimitInputStream(request.inputStream, MaxPayloadSize).readBytes(estimatedSize)
        } catch(e: IOException) {
            LOG.warnAndDebugDetails("Failed to read payload of $eventType event", e)
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            return simpleView("Failed to read payload: ${e.message}")
        } finally {
            FileUtil.close(request.inputStream)
        }
        if (content.size >= MaxPayloadSize) {
            val message = "Payload size exceed ${StringUtil.formatFileSize(MaxPayloadSize, 0)} limit"
            LOG.info("$message. Requests url: $path")
            response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
            return simpleView(message)
        }

        if (!HMacUtil.checkHMac(content, authData.secret.toByteArray(charset("UTF-8")), signature)) {
            LOG.warn("HMac verification failed for $eventType event")
            response.status = HttpServletResponse.SC_FORBIDDEN
            return simpleView("Payload signature verification failed. Ensure request url, '$X_Hub_Signature' header and payload are correct")
        }

        val contentReader = BufferedReader(InputStreamReader(ByteArrayInputStream(content), request.characterEncoding ?: "UTF-8"))

        try {
            when (eventType) {
                "ping" -> {
                    val payload = GsonUtilsEx.fromJson(contentReader, PingWebHookPayload::class.java)
                    val pair = doHandlePingEvent(payload, hookInfo)
                    response.status = pair.first
                    return simpleView(pair.second)
                }
                "push" -> {
                    val payload = GsonUtilsEx.fromJson(contentReader, PushWebHookPayload::class.java)
                    val pair = doHandlePushEvent(payload, authData, hookInfo)
                    response.status = pair.first
                    return simpleView(pair.second)
                }
            }
        } catch(e: Exception) {
            val message: String
            if (e is JsonSyntaxException || e is JsonIOException) {
                message = "Failed to parse payload"
            } else {
                message = "Failed to process request (event type is '$eventType')"
            }
            LOG.warnAndDebugDetails(message, e)
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            return simpleView(message + ": ${e.message}")
        }
        return null
    }


    private fun getAuthData(subPath: String) = AuthDataStorage.find(subPath)

    private fun doHandlePingEvent(payload: PingWebHookPayload, hookInfo: WebHooksStorage.HookInfo): Pair<Int, String> {
        val url = payload.repository?.gitUrl
        LOG.info("Received ping payload from webhook:${payload.hook_id}(${payload.hook.url}) for repo ${payload.repository?.owner?.login}/${payload.repository?.name}")
        if (url == null) {
            val message = "Ping event payload have no repository url specified"
            LOG.warn(message)
            return HttpServletResponse.SC_BAD_REQUEST to message
        }
        val info = Util.getGitHubInfo(url)
        if (info == null) {
            val message = "Cannot determine repository info from url '$url'"
            LOG.warn(message)
            return HttpServletResponse.SC_SERVICE_UNAVAILABLE to message
        }
        updateLastUsed(info, hookInfo)

        val foundVcsInstances = findSuitableVcsRootInstances(info)
        if (foundVcsInstances.isEmpty()) {
            return HttpServletResponse.SC_NOT_FOUND to "There are no VCS roots referencing '$info' repository"
        }

        return HttpServletResponse.SC_ACCEPTED to "Acknowledged"
    }

    private fun doHandlePushEvent(payload: PushWebHookPayload, authData: AuthDataStorage.AuthData, hookInfo: WebHooksStorage.HookInfo): Pair<Int, String> {
        val url = payload.repository?.gitUrl
        LOG.info("Received push payload from webhook for repo ${payload.repository?.owner?.login}/${payload.repository?.name}")
        if (url == null) {
            val message = "Push event payload have no repository url specified"
            LOG.warn(message)
            return HttpServletResponse.SC_BAD_REQUEST to message
        }
        val info = Util.getGitHubInfo(url)
        if (info == null) {
            val message = "Cannot determine repository info from url '$url'"
            LOG.warn(message)
            return HttpServletResponse.SC_SERVICE_UNAVAILABLE to message
        }
        updateLastUsed(info, hookInfo)
        updateBranches(info, payload, hookInfo)

        val userId = authData.userId
        val user = UserModel.findUserById(userId)
        if (user == null) {
            AuthDataStorage.removeAllForUser(userId)
            LOG.warn("User with id '$userId' not found")
            return HttpServletResponse.SC_BAD_REQUEST to "User installed webhook no longer registered in TeamCity. Remove and reinstall webhook."
        }

        val foundVcsInstances = findSuitableVcsRootInstances(info)
        doScheduleCheckForPendingChanges(foundVcsInstances, user)
        if (foundVcsInstances.isEmpty()) {
            return HttpServletResponse.SC_NOT_FOUND to "There are no VCS roots referencing '$info' repository"
        }
        return HttpServletResponse.SC_ACCEPTED to "Scheduled check for pending changes in ${foundVcsInstances.size} vcs root ${StringUtil.pluralize("instance", foundVcsInstances.size)}"
    }

    private fun updateLastUsed(info: GitHubRepositoryInfo, hookInfo: WebHooksStorage.HookInfo) {
        WebHooksManager.updateLastUsed(info, Date(), hookInfo)
    }

    private fun updateBranches(info: GitHubRepositoryInfo, payload: PushWebHookPayload, hookInfo: WebHooksStorage.HookInfo) {
        WebHooksManager.updateBranchRevisions(info, mapOf(payload.ref to payload.after), hookInfo)
    }

    private fun doScheduleCheckForPendingChanges(roots: List<VcsRootInstance>, user: UserEx) {
        // TODO: Or #forceCheckingFor ?
        // TODO: Should use rest api method ?
        SecurityContext.runAs(user, {
            VcsModificationChecker.checkForModificationsAsync(roots, OperationRequestor.COMMIT_HOOK)
        })
    }

    fun findSuitableVcsRootInstances(info: GitHubRepositoryInfo): List<VcsRootInstance> {
        val instances = HashSet<VcsRootInstance>()
        for (bt in ProjectManager.allBuildTypes) {
            if (bt.project.isArchived) continue
            val roots = bt.vcsRoots.filter { Util.isSuitableVcsRoot(it, false) }
            roots.map { bt.getVcsRootInstanceForParent(it) }.filterNotNull().filter { Util.isSuitableVcsRoot(it, true) }.toCollection(instances)
        }
        return instances.filter { info == Util.getGitHubInfo(it) }
    }
}