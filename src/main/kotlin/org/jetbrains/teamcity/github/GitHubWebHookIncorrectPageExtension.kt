package org.jetbrains.teamcity.github

import com.google.gson.Gson
import jetbrains.buildServer.serverSide.ProjectComparator
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.healthStatus.HealthStatusItemPageExtension
import jetbrains.buildServer.web.util.SessionUser
import javax.servlet.http.HttpServletRequest

class GitHubWebHookIncorrectPageExtension(descriptor: PluginDescriptor, places: PagePlaces) : HealthStatusItemPageExtension(WebhookPeriodicalChecker.TYPE, places) {

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
        val projects: Set<SProject>? = item.additionalData["Projects"] as Set<SProject>?
        val project = projects?.firstOrNull { user.isPermissionGrantedForProject(it.projectId, Permission.EDIT_PROJECT) }
        @Suppress("IfNullToElvis")
        if (project == null) {
            // User cannot edit at least one project with GitHub repo / VCS root declaration
            return false
        }
        return true
    }

    override fun fillModel(model: MutableMap<String, Any>, request: HttpServletRequest) {
        super.fillModel(model, request)
        val item = getStatusItem(request)
        val user = SessionUser.getUser(request)!!

        @Suppress("UNCHECKED_CAST")
        val projects: Set<SProject> = item.additionalData["Projects"] as Set<SProject>

        val projectsSorted = projects.toMutableList()
        if (projectsSorted.size > 1) {
            projectsSorted.sortWith(ProjectComparator(false))
        }

        val project = projectsSorted.firstOrNull { user.isPermissionGrantedForProject(it.projectId, Permission.EDIT_PROJECT) }!!
        model["Project"] = project

        model["gson"] = Gson()
        model["pluginResourcesPath"] = myResourcesPath
    }
}