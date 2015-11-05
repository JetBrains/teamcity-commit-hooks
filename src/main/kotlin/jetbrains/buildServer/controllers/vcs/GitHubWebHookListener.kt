package jetbrains.buildServer.controllers.vcs

import com.google.gson.JsonObject
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.impl.VcsModificationChecker
import jetbrains.buildServer.vcs.VcsRootInstance
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.eclipse.egit.github.core.client.GsonUtils
import org.springframework.web.servlet.ModelAndView
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

public class GitHubWebHookListener(val myWebControllerManager: WebControllerManager,
                                   val myProjectManager: ProjectManager,
                                   val myVcsModificationChecker: VcsModificationChecker,
                                   server: SBuildServer) : BaseController(server) {

    public fun register(): Unit {
        myWebControllerManager.registerController("/hooks/github", this)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        try {
            val obj = GsonUtils.fromJson(request.reader, JsonObject::class.java)
            val repo = obj.getAsJsonObject("repository")
            val url = repo.getAsJsonPrimitive("git_url").asString
            val roots = findRoots(url)
            doScheduleCheckForPendingChanges(roots)
            response.status = 200
        } catch(e: Exception) {
            logger.error("WebHook listener failed to process request: " + e.message, e)
            response.status = 503
        }
        return null;
    }

    private fun doScheduleCheckForPendingChanges(roots: List<VcsRootInstance>) {
        // TODO: Or #forceCheckingFor ?
        myVcsModificationChecker.checkForModificationsAsync(roots)
    }

    public fun findRoots(url: String): List<VcsRootInstance> {
        val rootInstances = HashSet<VcsRootInstance>()
        for (bt in myProjectManager.allBuildTypes) {
            if (bt.project.isArchived) continue
            rootInstances.addAll(bt.vcsRootInstances)
        }
        val roots = rootInstances
        // TODO: Check constants exact value
        val gitRoots = roots.filter { it.vcsName == "jetbrains.git" }
        // TODO: Use better search
        return gitRoots.filter { it.properties["url"] == url }
    }
}