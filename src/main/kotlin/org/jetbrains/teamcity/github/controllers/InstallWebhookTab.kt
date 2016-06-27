package org.jetbrains.teamcity.github.controllers

import jetbrains.buildServer.controllers.admin.projects.EditProjectTab
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.util.CameFromSupport
import org.jetbrains.teamcity.github.Util
import javax.servlet.http.HttpServletRequest

class InstallWebhookTab(places: PagePlaces, descriptor: PluginDescriptor,
                        val oAuthConnectionsManager: OAuthConnectionsManager
) : EditProjectTab(places, "installWebHook", descriptor.getPluginResourcesPath("installPage.jsp"), "Install Webhook") {

    init {
        addCssFile("/css/admin/projectConfig.css")

        addJsFile("/js/bs/systemProblemsMonitor.js")
        addJsFile("${descriptor.pluginResourcesPath}gh-webhook.js")
        addCssFile("${descriptor.pluginResourcesPath}webhook.css")
    }

    override fun isVisible(): Boolean = false

    override fun fillModel(model: MutableMap<String, Any?>, request: HttpServletRequest) {
        super.fillModel(model, request)
        val project = getProject(request) ?: return

        val repository = request.getParameter("repository")
        val info = repository?.let { org.jetbrains.teamcity.github.Util.Companion.parseGitRepoUrl(it) }
        model["repository"] = repository ?: ""
        model["connectionId"] = request.getParameter("connectionId") ?: info?.let { Util.findConnections(oAuthConnectionsManager, project, it.server).firstOrNull()?.id } ?: ""

        model["info"] = info


        val cameFrom = CameFromSupport();
        cameFrom.setUrlFromRequest(request, "/admin/editProject.html?projectId=${project.externalId}");
        model["cameFrom"] = cameFrom;
    }
}

