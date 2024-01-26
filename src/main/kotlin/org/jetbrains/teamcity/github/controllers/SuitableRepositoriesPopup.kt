

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
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class SuitableRepositoriesPopup(descriptor: PluginDescriptor,
                                val myWebControllerManager: WebControllerManager,
                                val myOauthConnectionManager: OAuthConnectionsManager,
                                val myProjectManager: ProjectManager) : BaseController() {

    private val myViewPath = descriptor.getPluginResourcesPath("suitableRepositoriesPopup.jsp")

    fun register() {
        myWebControllerManager.registerController("/webhooks/github/suitableRepositoriesPopup.html", this)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        val projectId = request.getParameter("projectId")
        val project = myProjectManager.findProjectByExternalId(projectId) ?: return SimpleView.createTextView("Project with id: $projectId does not exist")

        val hasConnections = myOauthConnectionManager.getAvailableConnections(project).isNotEmpty()
        val repos: SortedMap<GitHubRepositoryInfo, OAuthConnectionDescriptor> = TreeMap(GitHubRepositoryInfo.LexicographicalComparator)

        if (hasConnections) {
            val vcsRoots = Util.getVcsRootsWhereHookCanBeInstalled(project, myOauthConnectionManager)
            vcsRoots.mapNotNull { Util.getGitHubInfo(it) }
                    .toSet()
                    .forEach { info ->
                        val connections = Util.findConnections(myOauthConnectionManager, project, info.server)
                        if (connections.isNotEmpty()) repos[info] = connections.first()
                    }

        }

        val mv = ModelAndView(myViewPath)
        mv.model["repositoriesMap"] = repos
        mv.model["hasConnections"] = hasConnections
        mv.model["project"] = project
        return mv
    }
}