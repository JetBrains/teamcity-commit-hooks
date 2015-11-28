package org.jetbrains.teamcity.github

import com.google.gson.Gson
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.healthStatus.HealthStatusItemPageExtension
import javax.servlet.http.HttpServletRequest

public class GitHubWebHookAvailablePageExtension(private val myPluginDescriptor: PluginDescriptor, places: PagePlaces) : HealthStatusItemPageExtension(GitHubWebHookAvailableHealthReport.TYPE, places) {
    init {
        includeUrl = myPluginDescriptor.getPluginResourcesPath("gh-webhook-health-item.jsp")
        isVisibleOutsideAdminArea = true
    }

    override fun fillModel(model: MutableMap<String, Any>, request: HttpServletRequest) {
        super.fillModel(model, request)
        model.put("gson", Gson())
        model.put("pluginResourcesPath", myPluginDescriptor.pluginResourcesPath)
    }
}