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

import jetbrains.buildServer.controllers.admin.projects.EditProjectTab
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.web.openapi.*
import jetbrains.buildServer.web.util.CameFromSupport
import jetbrains.buildServer.web.util.SessionUser
import org.jetbrains.teamcity.github.TokensHelper
import org.jetbrains.teamcity.github.Util
import org.jetbrains.teamcity.github.nullIfBlank
import javax.servlet.http.HttpServletRequest

class InstallWebhookTab(places: PagePlaces, descriptor: PluginDescriptor,
                        private val tokensHelper: TokensHelper,
                        private val connectionsManager: OAuthConnectionsManager,
                        private val projectsManager: ProjectManager
) : EditProjectTab(places, "installWebHook", descriptor.getPluginResourcesPath("installPage.jsp"), "Install GitHub Webhook") {

    private val LOG = Util.getLogger(InstallWebhookTab::class.java)

    init {
        addCssFile("/css/admin/projectConfig.css")

        addJsFile("/js/bs/systemProblemsMonitor.js")
        addJsFile("${descriptor.pluginResourcesPath}gh-webhook.js")
        addCssFile("${descriptor.pluginResourcesPath}webhook.css")

        setPosition(PositionConstraint.last())

        val projectMenuExtension = object : SimplePageExtension(myPagePlaces) {
            override fun isAvailable(request: HttpServletRequest): Boolean {
                val project = request.getAttribute("project") as SProject? ?: return false
                val user = SessionUser.getUser(request) ?: return false
                return user.isPermissionGrantedForProject(project.projectId, Permission.EDIT_PROJECT)
                       && Util.isVcsRootsWhereHookCanBeInstalled(project, connectionsManager)
            }
        }
        projectMenuExtension.pluginName = "installWebhookAction"
        projectMenuExtension.placeId = PlaceId.ADMIN_EDIT_PROJECT_ACTIONS_PAGE
        projectMenuExtension.includeUrl = descriptor.getPluginResourcesPath("installWebhookAction.jsp")
        projectMenuExtension.register()
    }

    override fun isVisible(): Boolean = false

    override fun fillModel(model: MutableMap<String, Any?>, request: HttpServletRequest) {
        super.fillModel(model, request)
        val project = getProject(request) ?: return
        val user = SessionUser.getUser(request) ?: return

        val repository = request.getParameter("repository")
        val info = repository?.let { org.jetbrains.teamcity.github.Util.Companion.parseGitRepoUrl(it) }
        model["repository"] = repository.orEmpty()

        var connection: OAuthConnectionDescriptor? = info?.let { getConnection(request, project) }

        val hasToken: Boolean
        val hasConnections: Boolean
        if (info == null) {
            hasToken = false
            hasConnections = false
        } else if (connection != null) {
            hasToken = tokensHelper.getExistingTokens(listOf(connection), user).isNotEmpty()
            hasConnections = true
        } else {
            val pair = getConnections(project, user, info.server)
            val connections = pair?.first
            if (connections != null && connections.size == 1) {
                connection = connections.first()
            }
            hasConnections = connections?.isNotEmpty() == true
            hasToken = pair?.second == true
        }

        model["connectionId"] = connection?.id.orEmpty()
        model["connectionProjectId"] = connection?.project?.externalId.orEmpty()

        model["has_connections"] = hasConnections
        model["has_tokens"] = hasToken

        model["info"] = info


        val cameFrom = CameFromSupport()
        cameFrom.setUrlFromRequest(request, "/admin/editProject.html?projectId=${project.externalId}")
        cameFrom.setTitleFromRequest(request, null)
        model["cameFrom"] = cameFrom
    }

    private fun getConnection(request: HttpServletRequest, project: SProject): OAuthConnectionDescriptor? {
        val connectionId = request.getParameter("connectionId").nullIfBlank()
        val connectionProjectId = request.getParameter("connectionProjectId").nullIfBlank()

        if (connectionId != null) {
            val connectionProject: SProject?
            if (connectionProjectId != null) {
                connectionProject = projectsManager.findProjectByExternalId(connectionProjectId)
            } else {
                connectionProject = project
            }
            return connectionProject?.let { connectionsManager.findConnectionById(it, connectionId) }
        }
        return null
    }

    private fun getConnections(project: SProject, user: SUser, server: String): Pair<Collection<OAuthConnectionDescriptor>, Boolean>? {
        val connections = tokensHelper.getConnections(project, server)
        if (connections.isEmpty()) {
            LOG.debug("There's no connections to GitHub server $server in project ${project.describe(true)} and parents")
            return null
        }
        val tokens = tokensHelper.getExistingTokens(connections, user)
        if (tokens.isEmpty()) {
            LOG.debug("Found ${connections.size} to GitHub server $server, but no tokens for user '$user'")
            return connections to false
        } else if (tokens.size == 1) {
            LOG.debug("Found connection/token for user '$user' to GitHub server $server. Will use it")
        } else {
            LOG.debug("Found ${tokens.size} connections/tokens for user '$user' to GitHub server $server. Cannot determine which to use")
        }
        return tokens.keys to true
    }

    override fun isAvailable(request: HttpServletRequest): Boolean {
        val project = getProject(request)
        return project != null && SessionUser.getUser(request).isPermissionGrantedForProject(project.projectId, Permission.EDIT_PROJECT)
    }
}
