package org.jetbrains.teamcity.github

import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.healthStatus.HealthStatusItemPageExtension

class GitHubWebHookIncorrectPageExtension(descriptor: PluginDescriptor, places: PagePlaces) : HealthStatusItemPageExtension(WebhookPeriodicalChecker.TYPE, places) {
    init {
        includeUrl = descriptor.getPluginResourcesPath("gh-webhook-incorrect-health-item.jsp")
        isVisibleOutsideAdminArea = true
    }
}