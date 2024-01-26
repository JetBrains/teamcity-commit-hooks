

package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.healthStatus.HealthStatusItemPageExtension
import jetbrains.buildServer.web.util.SessionUser
import javax.servlet.http.HttpServletRequest

class GitHubWebHookIncorrectPageExtension(descriptor: PluginDescriptor, places: PagePlaces) : HealthStatusItemPageExtension(WebhookPeriodicalChecker.TYPE, places) {
    init {
        includeUrl = descriptor.getPluginResourcesPath("gh-webhook-incorrect-health-item.jsp")
        isVisibleOutsideAdminArea = true
    }

    override fun isAvailable(request: HttpServletRequest): Boolean {
        if (!super.isAvailable(request)) return false
        val item = getStatusItem(request)
        @Suppress("UNCHECKED_CAST")
        val projects = item.additionalData["Projects"] as Set<SProject>? ?: return false
        val user = SessionUser.getUser(request)

        return projects.any { user.isPermissionGrantedForProject(it.projectId, Permission.EDIT_PROJECT) }
    }
}