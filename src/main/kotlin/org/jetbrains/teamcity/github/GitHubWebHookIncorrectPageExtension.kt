package org.jetbrains.teamcity.github

import com.google.gson.Gson
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.vcs.SVcsRoot
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

        val info: GitHubRepositoryInfo = item.additionalData["GitHubInfo"] as GitHubRepositoryInfo
        val hook: WebHooksStorage.HookInfo = item.additionalData["HookInfo"] as WebHooksStorage.HookInfo
        @Suppress("UNCHECKED_CAST")
        val projects: Set<SProject> = item.additionalData["Projects"] as Set<SProject>
        @Suppress("UNCHECKED_CAST")
        val usages: Set<SVcsRoot> = item.additionalData["Usages"] as Set<SVcsRoot>

        val project = projects.firstOrNull { user.isPermissionGrantedForProject(it.projectId, Permission.EDIT_PROJECT) }!!
        model["Project"] = project

        model["gson"] = Gson()
        model["pluginResourcesPath"] = myResourcesPath
    }
}