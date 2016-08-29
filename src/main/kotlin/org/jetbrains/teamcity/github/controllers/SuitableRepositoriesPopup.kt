package org.jetbrains.teamcity.github.controllers

import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.controllers.SimpleView
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.jetbrains.teamcity.github.GitHubRepositoryInfo
import org.jetbrains.teamcity.github.Util
import org.springframework.web.servlet.ModelAndView
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class SuitableRepositoriesPopup(descriptor: PluginDescriptor,
                                val myWebControllerManager: WebControllerManager,
                                val myOauthConnectionManager: OAuthConnectionsManager,
                                val myProjectManager: ProjectManager) : BaseController() {

    private val myViewPath = descriptor.getPluginResourcesPath("suitableRepositoriesPopup.jsp")

    fun register(): Unit {
        myWebControllerManager.registerController("/webhooks/github/suitableRepositoriesPopup.html", this)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        val projectId = request.getParameter("projectId")
        val project = myProjectManager.findProjectByExternalId(projectId) ?: return SimpleView.createTextView("Project with id: $projectId does not exist")

        val vcsRoots = Util.getVcsRootsWhereHookCanBeInstalled(project, myOauthConnectionManager)
        val repos = linkedMapOf<GitHubRepositoryInfo?,OAuthConnectionDescriptor>()

        for (vcsRoot in vcsRoots) {
            val info = Util.getGitHubInfo(vcsRoot) ?: continue
            val connections = Util.findConnections(myOauthConnectionManager, project, info.server)
            if (connections.isEmpty()) continue
            repos.put(info, connections[0])
        }

        val mv = ModelAndView(myViewPath)
        mv.model.put("repositoriesMap", repos)
        mv.model.put("hasConnections", myOauthConnectionManager.getAvailableConnections(project).isNotEmpty());
        mv.model.put("project", project);
        return mv
    }
}
