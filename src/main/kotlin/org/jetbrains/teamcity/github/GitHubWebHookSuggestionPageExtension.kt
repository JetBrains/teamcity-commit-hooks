/*
 * Copyright 2000-2020 JetBrains s.r.o.
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