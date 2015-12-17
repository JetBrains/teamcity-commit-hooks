package org.jetbrains.teamcity.github.controllers

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.controllers.AuthorizationInterceptor
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.impl.VcsModificationChecker
import jetbrains.buildServer.vcs.VcsRootInstance
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.eclipse.egit.github.core.client.GsonUtilsEx
import org.eclipse.egit.github.core.event.PingWebHookPayload
import org.eclipse.egit.github.core.event.PushWebHookPayload
import org.jetbrains.teamcity.github.Util
import org.jetbrains.teamcity.github.VcsRootGitHubInfo
import org.jetbrains.teamcity.github.WebHooksManager
import org.springframework.web.servlet.ModelAndView
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

public class GitHubWebHookListener(private val WebControllerManager: WebControllerManager,
                                   private val ProjectManager: ProjectManager,
                                   private val VcsModificationChecker: VcsModificationChecker,
                                   private val AuthorizationInterceptor: AuthorizationInterceptor,
                                   private val WebHooksManager: WebHooksManager) : BaseController() {

    companion object {
        val PATH = "/app/hooks/github"
        val X_GitHub_Event = "X-GitHub-Event"

        private val LOG = Logger.getInstance(GitHubWebHookListener::class.java.name)
    }

    public fun register(): Unit {
        // Looks like GET is not necessary, POST is enough
        setSupportedMethods(METHOD_POST)
        WebControllerManager.registerController(PATH, this)
        AuthorizationInterceptor.addPathNotRequiringAuth(PATH)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val eventType: String? = request.getHeader(X_GitHub_Event)
        if (eventType == null) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return simpleView("'$X_GitHub_Event' header is missing")
        }
        try {
            when(eventType) {
                "ping" -> {
                    val payload = GsonUtilsEx.fromJson(request.reader, PingWebHookPayload::class.java)
                    response.status = doHandlePingEvent(payload)
                }
                "push" -> {
                    val payload = GsonUtilsEx.fromJson(request.reader, PushWebHookPayload::class.java)
                    response.status = doHandlePushEvent(payload)
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
        return null;
    }

    private fun doHandlePingEvent(payload: PingWebHookPayload): Int {
        LOG.info("Received ping payload from webhook:" + payload.hook_id + " " + payload.hook.url)
        updateLastUsed(Util.getGitHubInfo(payload.repository.gitUrl)!!)
        return HttpServletResponse.SC_ACCEPTED
    }

    private fun doHandlePushEvent(payload: PushWebHookPayload): Int {
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
        val foundVcsInstances = findSuitableVcsRootInstances(info)
        doScheduleCheckForPendingChanges(foundVcsInstances)
        return HttpServletResponse.SC_ACCEPTED
    }

    private fun updateLastUsed(info: VcsRootGitHubInfo) {
        WebHooksManager.updateLastUsed(info, Date())
    }

    private fun updateBranches(info: VcsRootGitHubInfo, payload: PushWebHookPayload) {
        WebHooksManager.updateBranchRevisions(info, mapOf(payload.ref to payload.after))
    }

    private fun doScheduleCheckForPendingChanges(roots: List<VcsRootInstance>) {
        // TODO: Or #forceCheckingFor ?
        // TODO: Should use rest api method ?
        VcsModificationChecker.checkForModificationsAsync(roots)
    }

    public fun findSuitableVcsRootInstances(info: VcsRootGitHubInfo): List<VcsRootInstance> {
        val roots = HashSet<VcsRootInstance>()
        for (bt in ProjectManager.allBuildTypes) {
            if (bt.project.isArchived) continue
            roots.addAll(bt.vcsRootInstances)
        }
        return roots.filter { info == Util.getGitHubInfo(it) }
    }
}