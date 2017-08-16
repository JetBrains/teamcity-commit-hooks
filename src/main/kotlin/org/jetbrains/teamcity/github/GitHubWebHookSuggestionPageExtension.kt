package org.jetbrains.teamcity.github

import com.google.gson.Gson
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.healthStatus.HealthStatusItemPageExtension
import jetbrains.buildServer.web.util.SessionUser
import org.springframework.beans.factory.annotation.Autowired
import javax.servlet.http.HttpServletRequest

class GitHubWebHookSuggestionPageExtension(descriptor: PluginDescriptor, places: PagePlaces, private val connectionsManager: OAuthConnectionsManager) : HealthStatusItemPageExtension(GitHubWebHookSuggestion.TYPE, places) {
    @Autowired
    lateinit var helper: TokensHelper
    private val myResourcesPath = descriptor.pluginResourcesPath

    init {
        includeUrl = descriptor.getPluginResourcesPath("gh-webhook-health-item.jsp")
        isVisibleOutsideAdminArea = true
    }

    override fun fillModel(model: MutableMap<String, Any>, request: HttpServletRequest) {
        super.fillModel(model, request)
        val item = getStatusItem(request)
        val user = SessionUser.getUser(request)!!
        val project: SProject = item.additionalData["Project"] as SProject
        val info: GitHubRepositoryInfo = item.additionalData["GitHubInfo"] as GitHubRepositoryInfo

        val connections = helper.getConnections(project, info.server)
        model.put("has_connections", connections.isNotEmpty())
        val tokens = helper.getExistingTokens(connections, user)
        model.put("has_tokens", tokens.isNotEmpty())

        model.put("gson", Gson())
        model.put("pluginResourcesPath", myResourcesPath)
    }

    override fun isAvailable(request: HttpServletRequest): Boolean {
        val item = getStatusItem(request)
        val project = item.additionalData["Project"] as SProject? ?: return false
        if (!SessionUser.getUser(request).isPermissionGrantedForProject(project.projectId, Permission.EDIT_PROJECT)) return false
        return Util.isVcsRootsWhereHookCanBeInstalled(project, connectionsManager)
    }
}