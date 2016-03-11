package org.jetbrains.teamcity.github

import com.google.gson.Gson
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.util.MultiMap
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.vcs.VcsRootInstance
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.healthStatus.HealthStatusItemPageExtension
import jetbrains.buildServer.web.util.SessionUser
import org.springframework.beans.factory.annotation.Autowired
import javax.servlet.http.HttpServletRequest

public class GitHubWebHookOutdatedPageExtension(descriptor: PluginDescriptor, places: PagePlaces) : HealthStatusItemPageExtension(GitHubWebHookOutdatedHealthReport.TYPE, places) {
    @Autowired
    lateinit var helper: TokensHelper
    private val myResourcesPath = descriptor.pluginResourcesPath

    init {
        includeUrl = descriptor.getPluginResourcesPath("gh-webhook-outdated-health-item.jsp")
        isVisibleOutsideAdminArea = true
    }

    override fun fillModel(model: MutableMap<String, Any>, request: HttpServletRequest) {
        super.fillModel(model, request)
        val item = getStatusItem(request)
        val user = SessionUser.getUser(request)!!

        val info: VcsRootGitHubInfo = item.additionalData["GitHubInfo"] as VcsRootGitHubInfo
        val hook: WebHooksStorage.HookInfo = item.additionalData["HookInfo"] as WebHooksStorage.HookInfo
        @Suppress("UNCHECKED_CAST")
        val projects: Set<SProject> = item.additionalData["Projects"] as Set<SProject>
        @Suppress("UNCHECKED_CAST")
        val map: MultiMap<SVcsRoot?, VcsRootInstance> = item.additionalData["UsageMap"] as MultiMap<SVcsRoot?, VcsRootInstance>

        //        val connections = helper.getConnections(root.project, info.server)
        //        model.put("has_connections", connections.isNotEmpty())
        //        val tokens = helper.getExistingTokens(connections, user)
        //        model.put("has_tokens", tokens.isNotEmpty())

        model.put("gson", Gson())
        model.put("pluginResourcesPath", myResourcesPath)
    }
}