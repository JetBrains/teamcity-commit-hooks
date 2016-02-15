package org.jetbrains.teamcity.github.controllers

import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.controllers.admin.projects.EditProjectTab
import jetbrains.buildServer.log.LogUtil
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.vcs.VcsRootInstance
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.jetbrains.teamcity.github.Constants
import org.jetbrains.teamcity.github.GitHubWebHookAvailableHealthReport
import org.jetbrains.teamcity.github.WebHooksManager
import org.springframework.web.servlet.ModelAndView
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

public class EditProjectWebHooksTab(places: PagePlaces, descriptor: PluginDescriptor, val webHooksManager: WebHooksManager) : EditProjectTab(places, "editProjectWebHooks", descriptor.getPluginResourcesPath("editProjectWebHooksTab.jsp"), TAB_TITLE_PREFIX) {
    companion object {
        val TAB_TITLE_PREFIX = "GitHub WebHooks"
    }

    init {
        addCssFile("/css/admin/projectConfig.css")
        addCssFile("/css/admin/vcsRootsTable.css")
    }

    override fun getTabTitle(request: HttpServletRequest): String {
        val project = getProject(request) ?: return super.getTabTitle(request)

        val webHooksBean = ProjectWebHooksBean(project, webHooksManager)

        val num = webHooksBean.getNumberOfAvailableWebHooks()
        if (num > 0) {
            return "$TAB_TITLE_PREFIX ($num)"
        }
        return TAB_TITLE_PREFIX
    }


}

public class EditProjectWebHooksController(server: SBuildServer, wcm: WebControllerManager, descriptor: PluginDescriptor, val webHooksManager: WebHooksManager, val projectManager: ProjectManager) : BaseController(server) {
    private val jsp = descriptor.getPluginResourcesPath("editProjectWebHooks.jsp")

    init {
        wcm.registerController("/admin/project/webhooks/edit.html", this)

    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val projectExternalId = request.getParameter("projectId")
        val project = projectManager.findProjectByExternalId(projectExternalId) ?: return simpleView("Project with id " + LogUtil.quote(projectExternalId) + " does not exist anymore.")

        val webHooksBean = ProjectWebHooksBean(project, webHooksManager)
        val modelAndView = ModelAndView(jsp)
        modelAndView.model.put("webHooksBean", webHooksBean)
        return modelAndView
    }


}

public class ProjectWebHooksBean(val project: SProject, webHooksManager: WebHooksManager) {
    val instances: Map<SVcsRoot, Map<VcsRootInstance, WebHooksStatus>>
    val roots: Map<SVcsRoot, WebHooksStatus>
    val hooks: List<WebHooksManager.HookInfo>

    init {
        val instances = HashMap<SVcsRoot, MutableMap<VcsRootInstance, WebHooksStatus>>()
        val roots = HashMap<SVcsRoot, WebHooksStatus>()
        val hooks = ArrayList<WebHooksManager.HookInfo>()


        val allGitVcsInstances = HashSet<VcsRootInstance>()
        findSuitableRoots(project) {
            allGitVcsInstances.add(it)
        }
        val split = GitHubWebHookAvailableHealthReport.split(allGitVcsInstances)
        for (entry in split) {
            val hook = webHooksManager.getHook(entry.key)
            hook?.let { hooks.add(it) }
            val status = getHookStatus(hook)
            for (root in entry.value.keySet()) {
                if (root != null) {
                    roots.put(root, status)
                }
            }
            val orphans = entry.value[null]
            for (orphan in orphans) {
                instances.getOrPut(orphan.parent) { HashMap() }.put(orphan, status)
            }
        }

        this.instances = instances
        this.roots = roots
        this.hooks = hooks
    }

    fun getNumberOfAvailableWebHooks(): Int {
        return hooks.size
    }


}

data public class WebHooksStatus(val status: Status, val hook: WebHooksManager.HookInfo?) {
}

enum class Status {
    NO_INFO,
    NOT_FOUND,
    OK,
    WAITING_FOR_SERVER_RESPONSE,
    INCORRECT
}

private fun getHookStatus(hook: WebHooksManager.HookInfo?): WebHooksStatus {
    if (hook == null) {
        return WebHooksStatus(Status.NOT_FOUND, hook)
    }
    if (!hook.correct) {
        return WebHooksStatus(Status.INCORRECT, hook)
    }
    if (hook.lastUsed == null) {
        return WebHooksStatus(Status.WAITING_FOR_SERVER_RESPONSE, hook)
    }
    return WebHooksStatus(Status.OK, hook)
}

private fun findSuitableRoots(project: SProject, recursive: Boolean = false, archived: Boolean = false, collector: (VcsRootInstance) -> Boolean): Unit {
    for (bt in if (recursive) project.buildTypes else project.ownBuildTypes) {
        if (!archived && bt.project.isArchived) continue
        for (it in bt.vcsRootInstances) {
            if (it.vcsName == Constants.VCS_NAME_GIT && it.properties[Constants.VCS_PROPERTY_GIT_URL] != null) {
                if (!collector(it)) return;
            }
        }
    }
}
