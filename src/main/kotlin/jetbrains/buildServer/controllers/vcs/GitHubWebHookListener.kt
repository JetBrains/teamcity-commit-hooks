package jetbrains.buildServer.controllers.vcs

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.controllers.AuthorizationInterceptor
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.impl.VcsModificationChecker
import jetbrains.buildServer.vcs.VcsRootInstance
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.eclipse.egit.github.core.client.GsonUtilsEx
import org.eclipse.egit.github.core.event.PingWebHookPayload
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
                                   server: SBuildServer) : BaseController(server) {

    companion object {
        val PATH = "/app/hooks/github"

        private val LOG = Logger.getInstance(GitHubWebHookListener::class.java.name)
    }

    public fun register(): Unit {
        // Looks like GET is not necessary, POST is enough
        setSupportedMethods(METHOD_POST)
        WebControllerManager.registerController(PATH, this)
        AuthorizationInterceptor.addPathNotRequiringAuth(PATH)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val eventType: String? = request.getHeader("X-GitHub-Event")
        LOG.info("Received request with event $eventType")
        if (eventType == null) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return simpleView("'X-GitHub-Event' header is missing")
        }
        if (eventType == "ping" ) {
            val payload = GsonUtilsEx.fromJson(request.reader, PingWebHookPayload::class.java)
            LOG.info("Received ping payload from webhook:" + payload.hook_id + " " + payload.hook.url)
        } else if (eventType == "push" ) {
            try {
                val obj = GsonUtilsEx.fromJson(request.reader, JsonObject::class.java)
                val repo: JsonObject? = obj.getAsJsonObject("repository")
                val url = repo?.getAsJsonPrimitive("git_url")?.asString
                val found = url?.let { findSuitableVcsRootInstances(it) }
                if (found != null) {
                    doScheduleCheckForPendingChanges(found.second)
                }
                response.status = HttpServletResponse.SC_OK
            } catch(e: Exception) {
                LOG.error("WebHook listener failed to process request: " + e.message, e)
                response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            }
        } else {
            LOG.info("Received unknown event type: $eventType, ignoring")
        }

        return null;
    }

    private fun doScheduleCheckForPendingChanges(roots: List<VcsRootInstance>) {
        // TODO: Or #forceCheckingFor ?
        // TODO: Should use rest api method ?
        VcsModificationChecker.checkForModificationsAsync(roots)
    }

    public fun findSuitableVcsRootInstances(url: String): Pair<VcsRootGitHubInfo, List<VcsRootInstance>>? {
        val info = Util.getGitHubInfo(url) ?: return null
        val rootInstances = HashSet<VcsRootInstance>()
        for (bt in ProjectManager.allBuildTypes) {
            if (bt.project.isArchived) continue
            rootInstances.addAll(bt.vcsRootInstances)
        }
        val roots = rootInstances
        // TODO: Check constants exact value
        val gitRoots = roots.filter { it.vcsName == "jetbrains.git" }
        // TODO: Use better search
        val found = gitRoots.map { it to Util.getGitHubInfo(it) }.filter { info == it.second }
        return info to found.map { it.first }
    }
}