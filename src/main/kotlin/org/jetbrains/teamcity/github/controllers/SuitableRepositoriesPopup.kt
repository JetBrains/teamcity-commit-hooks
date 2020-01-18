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

    fun register(): Unit {
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
                        if (connections.isNotEmpty()) repos.put(info, connections.first())
                    }

        }

        val mv = ModelAndView(myViewPath)
        mv.model.put("repositoriesMap", repos)
        mv.model.put("hasConnections", hasConnections)
        mv.model.put("project", project)
        return mv
    }
}
