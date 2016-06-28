package org.jetbrains.teamcity.github.controllers

import com.google.common.io.LimitInputStream
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.controllers.AuthorizationInterceptor
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SecurityContextEx
import jetbrains.buildServer.serverSide.impl.VcsModificationChecker
import jetbrains.buildServer.users.UserModelEx
import jetbrains.buildServer.users.impl.UserEx
import jetbrains.buildServer.util.FileUtil
import jetbrains.buildServer.vcs.OperationRequestor
import jetbrains.buildServer.vcs.VcsManager
import jetbrains.buildServer.vcs.VcsRootInstance
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.WebUtil
import org.eclipse.egit.github.core.client.GsonUtilsEx
import org.eclipse.egit.github.core.event.PingWebHookPayload
import org.eclipse.egit.github.core.event.PushWebHookPayload
import org.jetbrains.teamcity.github.AuthDataStorage
import org.jetbrains.teamcity.github.Util
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.WebHooksManager
import org.springframework.beans.factory.annotation.Autowired
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
                            private val WebHooksManager: WebHooksManager) : BaseController() {

    companion object {
        val PATH = "/app/hooks/github"
        val X_GitHub_Event = "X-GitHub-Event"
        val X_Hub_Signature = "X-Hub-Signature"

        val SupportedEvents = listOf("ping", "push")

        private val LOG = Logger.getInstance(GitHubWebHookListener::class.java.name)
    }

    @Autowired
    lateinit var VcsManager: VcsManager

    fun register(): Unit {
        // Looks like GET is not necessary, POST is enough
        setSupportedMethods(METHOD_POST)
        WebControllerManager.registerController(PATH, this)
        WebControllerManager.registerController(PATH + "/*", this)
        AuthorizationInterceptor.addPathNotRequiringAuth(PATH)
        AuthorizationInterceptor.addPathNotRequiringAuth(PATH + "/*")
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
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
            return null
        }

        LOG.debug("Received hook event with public key in path: $pubKey")

        val authData = getAuthData(pubKey)
        if (authData == null) {
            LOG.warn("No stored auth data (secret key) found for public key '$pubKey'")
            response.status = HttpServletResponse.SC_NOT_FOUND
            return null
        }

        val content: ByteArray
        try {
            var estimatedSize = request.contentLength
            if (estimatedSize == -1) estimatedSize = 8 * 1024

            content = LimitInputStream(request.inputStream, 2 * FileUtil.MEGABYTE.toLong()).readBytes(estimatedSize)
        } catch(e: IOException) {
            LOG.warnAndDebugDetails("Failed to read payload of $eventType event", e)
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            return simpleView("Failed to read payload: ${e.message}")
        }

        FileUtil.close(request.inputStream)

        if (!HMacUtil.checkHMac(content, authData.secret.toByteArray(charset("UTF-8")), signature)) {
            LOG.warn("HMac verification failed for $eventType event")
            response.status = HttpServletResponse.SC_PRECONDITION_FAILED //TODO: Maybe it's ok to return SC_BAD_REQUEST
            return null
        }

        val contentReader = BufferedReader(InputStreamReader(ByteArrayInputStream(content), request.characterEncoding ?: "UTF-8"))

        try {
            when (eventType) {
                "ping" -> {
                    val payload = GsonUtilsEx.fromJson(contentReader, PingWebHookPayload::class.java)
                    response.status = doHandlePingEvent(payload)
                }
                "push" -> {
                    val payload = GsonUtilsEx.fromJson(contentReader, PushWebHookPayload::class.java)
                    response.status = doHandlePushEvent(payload, authData)
                }
                else -> {
                    LOG.info("Received unknown event type: $eventType, ignoring")
                    response.status = HttpServletResponse.SC_ACCEPTED
                }
            }
        } catch(e: Exception) {
            LOG.warnAndDebugDetails("Failed to process request (event type is '$eventType')", e)
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
        }
        return null
    }

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

    private fun getAuthData(subPath: String) = AuthDataStorage.find(subPath)

    private fun doHandlePingEvent(payload: PingWebHookPayload): Int {
        val url = payload.repository?.gitUrl
        LOG.info("Received ping payload from webhook:${payload.hook_id}(${payload.hook.url}) for repo ${payload.repository?.owner?.login}/${payload.repository?.name}")
        if (url == null) {
            LOG.warn("Ping event payload have no repository url specified")
            return HttpServletResponse.SC_BAD_REQUEST
        }
        val info = Util.getGitHubInfo(url)
        if (info == null) {
            LOG.warn("Cannot determine repository info from url '$url'")
            return HttpServletResponse.SC_SERVICE_UNAVAILABLE
        }
        updateLastUsed(info)
        setModificationCheckInterval(info)
        return HttpServletResponse.SC_ACCEPTED
    }

    private fun doHandlePushEvent(payload: PushWebHookPayload, authData: AuthDataStorage.AuthData): Int {
        val url = payload.repository?.gitUrl
        LOG.info("Received push payload from webhook for repo ${payload.repository?.owner?.login}/${payload.repository?.name}")
        if (url == null) {
            LOG.warn("Push event payload have no repository url specified")
            return HttpServletResponse.SC_BAD_REQUEST
        }
        val info = Util.getGitHubInfo(url)
        if (info == null) {
            LOG.warn("Cannot determine repository info from url '$url'")
            return HttpServletResponse.SC_SERVICE_UNAVAILABLE
        }
        updateLastUsed(info)
        updateBranches(info, payload)

        val userId = authData.userId
        val user = UserModel.findUserById(userId)
        if (user == null) {
            AuthDataStorage.removeAllForUser(userId)
            LOG.warn("User with id '$userId' not found")
            return HttpServletResponse.SC_BAD_REQUEST
        }

        val foundVcsInstances = findSuitableVcsRootInstances(info, null)
        doScheduleCheckForPendingChanges(foundVcsInstances, user)
        return HttpServletResponse.SC_ACCEPTED
    }

    private fun updateLastUsed(info: GitHubRepositoryInfo) {
        WebHooksManager.updateLastUsed(info, Date())
    }

    private fun updateBranches(info: GitHubRepositoryInfo, payload: PushWebHookPayload) {
        WebHooksManager.updateBranchRevisions(info, mapOf(payload.ref to payload.after))
    }

    private fun doScheduleCheckForPendingChanges(roots: List<VcsRootInstance>, user: UserEx) {
        // TODO: Or #forceCheckingFor ?
        // TODO: Should use rest api method ?
        SecurityContext.runAs(user, {
            VcsModificationChecker.checkForModificationsAsync(roots, OperationRequestor.COMMIT_HOOK)
        })
    }

    fun findSuitableVcsRootInstances(info: GitHubRepositoryInfo, vcsRootId: String?): List<VcsRootInstance> {
        val roots = HashSet<VcsRootInstance>()
        for (bt in ProjectManager.allBuildTypes) {
            if (bt.project.isArchived) continue
            roots.addAll(bt.vcsRootInstances)
        }
        return roots.filter { info == Util.getGitHubInfo(it) && (vcsRootId == null || it.parent.externalId == vcsRootId) }
    }

    private fun setModificationCheckInterval(info: GitHubRepositoryInfo) {
        // It's worth to update intervals for all git vcs roots with same url ('info')
        val roots = VcsManager.allRegisteredVcsRoots.filter { info == Util.Companion.getGitHubInfo(it) }
        for (root in roots) {
            val value = TimeUnit.HOURS.toSeconds(12).toInt()
            if (root.isUseDefaultModificationCheckInterval || root.modificationCheckInterval < value) {
                root.modificationCheckInterval = value
            }
        }
    }
}