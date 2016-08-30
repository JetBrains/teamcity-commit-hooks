package org.jetbrains.teamcity.github

import com.google.gson.Gson
import jetbrains.buildServer.serverSide.ProjectComparator
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.healthStatus.HealthStatusItemPageExtension
import jetbrains.buildServer.web.util.SessionUser
import org.springframework.beans.factory.annotation.Autowired
import javax.servlet.http.HttpServletRequest

class GitHubWebHookIncorrectPageExtension(descriptor: PluginDescriptor, places: PagePlaces) : HealthStatusItemPageExtension(WebhookPeriodicalChecker.TYPE, places) {
    @Autowired
    lateinit var helper: TokensHelper
    private val myResourcesPath = descriptor.pluginResourcesPath

    init {
        includeUrl = descriptor.getPluginResourcesPath("gh-webhook-incorrect-health-item.jsp")
        isVisibleOutsideAdminArea = false
    }

    override fun isAvailable(request: HttpServletRequest): Boolean {
        if (!super.isAvailable(request)) return false
        val item = getStatusItem(request)
        val user = SessionUser.getUser(request) ?: return false
        @Suppress("UNCHECKED_CAST")
        val projects = item.additionalData["Projects"] as Set<SProject>? ?: return false
        // Check that user can edit at least one project with GitHub repo / VCS root declaration
        return projects.any { user.isPermissionGrantedForProject(it.projectId, Permission.EDIT_PROJECT) }
    }

    override fun fillModel(model: MutableMap<String, Any>, request: HttpServletRequest) {
        super.fillModel(model, request)
        val item = getStatusItem(request)
        val user = SessionUser.getUser(request)!!

        @Suppress("UNCHECKED_CAST")
        val projects: Set<SProject> = item.additionalData["Projects"] as Set<SProject>
        val info: GitHubRepositoryInfo = item.additionalData["GitHubInfo"] as GitHubRepositoryInfo

        val projectsSorted = projects.toMutableList()
        if (projectsSorted.size > 1) {
            projectsSorted.sortWith(ProjectComparator(false))
        }
        val editableSortedProjects = projectsSorted.filter {
            user.isPermissionGrantedForProject(it.projectId, Permission.EDIT_PROJECT)
        }

        val project = editableSortedProjects.firstOrNull {
            val connections = helper.getConnections(it, info.server)
            val tokens = helper.getExistingTokens(connections, user)
            tokens.isNotEmpty()
        } ?: editableSortedProjects.first()
        model["Project"] = project

        val connections = helper.getConnections(project, info.server)
        model.put("has_connections", connections.isNotEmpty())
        val tokens = helper.getExistingTokens(connections, user)
        model.put("has_tokens", tokens.isNotEmpty())

        model["gson"] = Gson()
        model["pluginResourcesPath"] = myResourcesPath
    }
}