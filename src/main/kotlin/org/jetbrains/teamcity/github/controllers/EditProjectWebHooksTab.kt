/*
 * Copyright 2000-2021 JetBrains s.r.o.
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

package org.jetbrains.teamcity.github.controllers

import com.google.gson.JsonElement
import com.intellij.util.SmartList
import jetbrains.buildServer.Used
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.controllers.FormUtil
import jetbrains.buildServer.controllers.admin.projects.EditProjectTab
import jetbrains.buildServer.log.LogUtil
import jetbrains.buildServer.serverSide.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.versionedSettings.VersionedSettingsManager
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.util.Pager
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.SessionUser
import org.jetbrains.teamcity.github.*
import org.springframework.web.servlet.ModelAndView
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class EditProjectWebHooksTab(places: PagePlaces, descriptor: PluginDescriptor,
                             val webHooksManager: WebHooksManager,
                             val versionedSettingsManager: VersionedSettingsManager,
                             val tokensHelper: TokensHelper,
                             val oAuthConnectionsManager: OAuthConnectionsManager) : EditProjectTab(places, "editProjectWebHooks", descriptor.getPluginResourcesPath("editProjectWebHooksTab.jsp"), TAB_TITLE_PREFIX) {
    companion object {
        val TAB_TITLE_PREFIX = "GitHub Webhooks"
        val TAB_ENABLE_INTERNAL_PROPERTY = "teamcity.github-webhooks.tab.enabled"
    }

    init {
        addCssFile("/css/pager.css")
        addCssFile("/css/admin/projectConfig.css")
        addCssFile("/css/admin/vcsRootsTable.css")
    }

    override fun getTabTitle(request: HttpServletRequest): String {
        val project = getProject(request) ?: return super.getTabTitle(request)
        val user = SessionUser.getUser(request) ?: return super.getTabTitle(request)

        // TODO: Do not calculate full data, just estimate webhooks count
        val webHooksBean = ProjectWebHooksBean(project, webHooksManager, versionedSettingsManager, tokensHelper, user, oAuthConnectionsManager)
        webHooksBean.applyFilter()

        val num = webHooksBean.getNumberOfCorrectWebHooks()
        if (num > 0) {
            return "$TAB_TITLE_PREFIX ($num)"
        }
        return TAB_TITLE_PREFIX
    }

    /**
     * Available only if there's at least one github repository in project and subprojects
     */
    override fun isAvailable(request: HttpServletRequest): Boolean {
        if (!TeamCityProperties.getBoolean(TAB_ENABLE_INTERNAL_PROPERTY)) return false

        val superIsAvailable = super.isAvailable(request)
        if (!superIsAvailable) return false
        val project = getProject(request) ?: return false
        val user = SessionUser.getUser(request) ?: return false

        // TODO: Do not calculate full data, just estimate webhooks count
        val webHooksBean = ProjectWebHooksBean(project, webHooksManager, versionedSettingsManager, tokensHelper, user, oAuthConnectionsManager)
        webHooksBean.form.recursive = true
        webHooksBean.applyFilter()

        val num = webHooksBean.getNumberOfAvailableWebHooks()
        return num > 0
    }
}

class EditProjectWebHooksController(server: SBuildServer, wcm: WebControllerManager,
                                    val descriptor: PluginDescriptor,
                                    val webHooksManager: WebHooksManager,
                                    val projectManager: ProjectManager,
                                    val versionedSettingsManager: VersionedSettingsManager,
                                    val tokensHelper: TokensHelper,
                                    val oAuthConnectionsManager: OAuthConnectionsManager) : BaseController(server) {
    private val jsp = descriptor.getPluginResourcesPath("editProjectWebHooks.jsp")

    init {
        wcm.registerController("/admin/project/webhooks/edit.html", this)

    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val projectExternalId = request.getParameter("projectId")
        val project = projectManager.findProjectByExternalId(projectExternalId) ?: return simpleView("Project with id '$projectExternalId' does not exist anymore.")
        val user = SessionUser.getUser(request) ?: return simpleView("Session User not found.")

        val webHooksBean = ProjectWebHooksBean(project, webHooksManager, versionedSettingsManager, tokensHelper, user, oAuthConnectionsManager)

        FormUtil.bindFromRequest(request, webHooksBean.form)
        webHooksBean.applyFilter()
        webHooksBean.updatePager()

        val modelAndView = ModelAndView(jsp)
        modelAndView.model.put("webHooksBean", webHooksBean)
        modelAndView.model.put("pluginResourcesPath", descriptor.pluginResourcesPath)
        return modelAndView
    }


}

class ProjectWebHooksForm {
    var keyword: String? = null
    var recursive: Boolean = false
    var page: Int = 0
}

class ProjectWebHooksBean(val project: SProject,
                          val webHooksManager: WebHooksManager,
                          val versionedSettingsManager: VersionedSettingsManager,
                          val helper: TokensHelper,
                          val user: SUser,
                          val oAuthConnectionsManager: OAuthConnectionsManager) {
    val hooks: SortedMap<GitHubRepositoryInfo, WebHookDetails> = TreeMap(GitHubRepositoryInfo.LexicographicalComparator)

    val form: ProjectWebHooksForm = ProjectWebHooksForm()
    val pager: Pager = Pager(50)

    fun getNumberOfAvailableWebHooks(): Int {
        return hooks.size
    }

    fun getNumberOfCorrectWebHooks(): Int {
        return hooks.count { getHookStatus(it.value.info).status.good }
    }

    @Used("jps") fun getVisibleHooks(): List<Map.Entry<GitHubRepositoryInfo, WebHookDetails>> {
        val origin = hooks.entries.toList()
        return pager.getCurrentPageData(origin)
    }

    fun applyFilter() {
        val keyword = form.keyword
        val keywordFiltering = !keyword.isNullOrBlank()

        val allGitVcsRoots = HashSet<SVcsRoot>()
        Util.findSuitableRoots(project, recursive = form.recursive) {
            allGitVcsRoots.add(it)
            true
        }
        val split = GitHubWebHookSuggestion.splitRoots(allGitVcsRoots)
        val filtered = split.entrySet()
                .filterKnownServers(oAuthConnectionsManager)
        for ((info, roots) in filtered) {
            if (keywordFiltering) {
                if (!info.id.contains(keyword!!, true)) continue
            }

            val hook = webHooksManager.getHook(info)
            hooks.put(info, WebHookDetails(hook, roots.toList(), project, versionedSettingsManager))
        }
    }

    @Used("jps")
    fun getEnforcePopupData(): Map<String, Boolean> {
        val map = HashMap<String, Boolean>()
        val hooks = getVisibleHooks()
        val servers = hooks.map { it.key.server }.toHashSet()
        for (server in servers) {
            val connections = helper.getConnections(project, server)
            val tokens = helper.getExistingTokens(connections, user)
            map.put(server, !connections.isNotEmpty() || !tokens.isNotEmpty())
        }
        return map
    }


    fun updatePager() {
        pager.setNumberOfRecords(getNumberOfAvailableWebHooks())
        pager.currentPage = form.page
    }

    fun getDataJson(info: GitHubRepositoryInfo): JsonElement {
        return WebHooksController.Companion.getRepositoryInfo(info, webHooksManager)
    }
}

class WebHookDetails(val info: WebHooksStorage.HookInfo?,
                     val roots: List<SVcsRoot>,
                     val project: SProject,
                     val versionedSettingsManager: VersionedSettingsManager
) {
    @Used("jps")
    val totalUsagesCount: Int by lazy { totalUsages.total }

    val usagesMap: Map<SVcsRoot, VcsRootUsages> by lazy {
        val map = HashMap<SVcsRoot, VcsRootUsages>()
        for (root in roots) {
            map.put(root, VcsRootUsagesBean(root, project, versionedSettingsManager))
        }
        map
    }

    @Used("jps")
    private val totalUsages: VcsRootUsages by lazy {
        val combined = VcsRootUsagesBeanCombined()
        usagesMap.values.forEach { combined.add(it) }
        combined
    }
}

interface VcsRootUsages {
    val total: Int
    val templates: Collection<BuildTypeTemplate>
    val buildTypes: Collection<SBuildType>
    val versionedSettings: Collection<SProject>
}

class VcsRootUsagesBean(val root: SVcsRoot, val project: SProject, val VersionedSettingsManager: VersionedSettingsManager) : VcsRootUsages {
    override val total: Int by lazy { templates.size + buildTypes.size + versionedSettings.size }

    override val templates: List<BuildTypeTemplate> by lazy {
        val templates = project.ownBuildTypeTemplates
        templates.filter { it.containsVcsRoot(root.id) }
    }
    override val buildTypes: List<SBuildType> by lazy {
        SmartList<SBuildType>(root.usagesInConfigurations)
    }
    override val versionedSettings: List<SProject> by lazy {
        SmartList<SProject>(VersionedSettingsManager.getProjectsBySettingsRoot(root))
    }
}

class VcsRootUsagesBeanCombined(usages: List<VcsRootUsagesBean> = emptyList()) : VcsRootUsages {
    override val total: Int get() {
        return templates.size + buildTypes.size + versionedSettings.size
    }

    override val templates: MutableSet<BuildTypeTemplate> by lazy {
        val list = HashSet<BuildTypeTemplate>()
        for (usage in usages) {
            list.addAll(usage.templates)
        }
        list
    }
    override val buildTypes: MutableSet<SBuildType> by lazy {
        val list = HashSet<SBuildType>()
        for (usage in usages) {
            list.addAll(usage.buildTypes)
        }
        list
    }
    override val versionedSettings: MutableSet<SProject> by lazy {
        val list = HashSet<SProject>()
        for (usage in usages) {
            list.addAll(usage.versionedSettings)
        }
        list
    }

    fun add(usages: VcsRootUsages) {
        this.templates.addAll(usages.templates)
        this.buildTypes.addAll(usages.buildTypes)
        this.versionedSettings.addAll(usages.versionedSettings)
    }

    override fun toString(): String {
        return "VcsRootUsagesBeanCombined{total=$total}"
    }
}

data class WebHooksStatus(val status: Status, val hook: WebHooksStorage.HookInfo?) {
    @Used("jps")
    fun getActions(): List<String> {
        return when (status) {
            Status.NO_INFO -> listOf("Check")
            Status.NOT_FOUND -> listOf("Add")
            Status.OK -> listOf("Delete", "Check")
            Status.WAITING_FOR_SERVER_RESPONSE -> listOf("Delete", "Ping", "Check")
            Status.INCORRECT -> listOf("Add", "Check")
            Status.MISSING -> listOf("Add", "Check")
            Status.DISABLED -> listOf("Delete", "Ping", "Check") // TODO: 'Enable'
            Status.PAYLOAD_DELIVERY_FAILED -> listOf("Delete", "Ping", "Check")
            Status.OUTDATED -> listOf("Ping", "Delete", "Check")
        }
    }
}

enum class Status {
    NO_INFO, // Cannot load details from GitHub due to auth problems

    NOT_FOUND, // UI only, to provide 'Add' action

    INCORRECT, // Something wrong on our side
    MISSING, // Deleted on GitHub side

    OK, // Works fine
    WAITING_FOR_SERVER_RESPONSE, // Waiting for first payload to be received

    DISABLED, // Disabled on GitHub side

    PAYLOAD_DELIVERY_FAILED, // GitHub failed to deliver payload, probably TC server not accessible from GH
    OUTDATED // We haven't received payload, but found some changes after manual checking for changes. Related to PAYLOAD_DELIVERY_FAILED
}

val Status.bad: Boolean
    get() {
        return this in listOf(Status.INCORRECT, Status.MISSING)
    }

// TODO: Support more 'good' statuses?
val Status.good: Boolean
    get() {
        return this in listOf(Status.OK, Status.WAITING_FOR_SERVER_RESPONSE)
    }

fun getHookStatus(hook: WebHooksStorage.HookInfo?): WebHooksStatus {
    if (hook == null) {
        return WebHooksStatus(Status.NOT_FOUND, hook)
    }
    if (hook.lastUsed == null) {
        return WebHooksStatus(Status.WAITING_FOR_SERVER_RESPONSE, hook)
    }
    return WebHooksStatus(hook.status, hook)
}
