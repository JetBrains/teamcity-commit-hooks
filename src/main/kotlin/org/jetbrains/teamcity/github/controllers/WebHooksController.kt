package org.jetbrains.teamcity.github.controllers

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonWriter
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientFactory
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.util.PropertiesUtil
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.vcs.VcsManager
import jetbrains.buildServer.vcs.VcsRootInstance
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.SessionUser
import jetbrains.buildServer.web.util.WebUtil
import org.eclipse.egit.github.core.client.RequestException
import org.intellij.lang.annotations.MagicConstant
import org.jetbrains.teamcity.github.TokensHelper
import org.jetbrains.teamcity.github.Util
import org.jetbrains.teamcity.github.VcsRootGitHubInfo
import org.jetbrains.teamcity.github.WebHooksManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.web.servlet.ModelAndView
import java.io.IOException
import java.io.OutputStreamWriter
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

public class WebHooksController(private val descriptor: PluginDescriptor, server: SBuildServer) : BaseController(server) {

    @Autowired
    lateinit var VcsManager: VcsManager

    @Autowired
    lateinit var WebControllerManager: WebControllerManager

    @Autowired
    lateinit var OAuthConnectionsManager: OAuthConnectionsManager
    @Autowired
    lateinit var OAuthTokensStorage: OAuthTokensStorage

    @Autowired
    lateinit var manager: WebHooksManager

    @Autowired
    lateinit var TokensHelper: TokensHelper

    @Autowired
    lateinit var ProjectManager: ProjectManager

    private val myResultJspPath = descriptor.getPluginResourcesPath("hook-created.jsp")


    public fun register(): Unit {
        WebControllerManager.registerController(PATH, this)
    }

    companion object {
        public val PATH = "/oauth/github/webhooks.html"

        private val LOG = Logger.getInstance(WebHooksController::class.java.name)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val action = request.getParameter("action")
        val popup = PropertiesUtil.getBoolean(request.getParameter("popup"))
        val element: JsonElement
        var direct: Boolean = false
        if (!popup) {
            direct = true
        }
        if ("add" == action || action == null) {
            element = doHandleAddAction(request, response, action, popup);
        } else if ("check" == action) {
            element = doHandleCheckAction(request, response, action, popup);
        } else if ("delete" == action) {
            element = doHandleDeleteAction(request, response, action, popup);
        } else if ("continue" == action) {
            element = doHandleAddAction(request, response, action, popup);
        } else {
            LOG.warn("Unknown action '$action'")
            return null
        }
        if (element is JsonObject) {
            element.addProperty("action", action)
        }
        if (direct) {
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            val writer = JsonWriter(OutputStreamWriter(response.outputStream))
            Gson().toJson(element, writer)
            writer.flush()
            return null
        } else if (popup) {
            if (element is JsonObject) {
                val url = element.getAsJsonPrimitive("redirect")?.asString
                if (url != null) {
                    return redirectTo(url, response)
                }
            }
        }
        return callbackPage(element)
    }

    private fun callbackPage(element: JsonElement): ModelAndView {
        val mav = ModelAndView(myResultJspPath)
        mav.model.put("json", Gson().toJson(element))
        return mav
    }

    private fun doHandleAddAction(request: HttpServletRequest, response: HttpServletResponse, action: String, popup: Boolean): JsonElement {
        val user = SessionUser.getUser(request) ?: return error_json("Not authenticated", HttpServletResponse.SC_UNAUTHORIZED)

        val inType = request.getParameter("type")?.toLowerCase()
        val inId = request.getParameter("id")
        val inProjectId = request.getParameter("projectId")

        if (inType == null) {
            return error_json("Required parameter 'type' is missing", HttpServletResponse.SC_BAD_REQUEST)
        }
        if (inId == null) {
            return error_json("Required parameter 'id' is missing", HttpServletResponse.SC_BAD_REQUEST)
        }

        val info: VcsRootGitHubInfo
        val project: SProject

        val inConnectionId = request.getParameter("connectionId")
        val inConnectionProjectId = request.getParameter("connectionProjectId") ?: inProjectId
        var connection: OAuthConnectionDescriptor?

        if (inConnectionId != null && inConnectionProjectId != null) {
            val connectionOwnerProject = ProjectManager.findProjectByExternalId(inConnectionProjectId)
            if (connectionOwnerProject == null) {
                return error_json("There no project with external id $inConnectionProjectId", HttpServletResponse.SC_NOT_FOUND)
            }
            connection = OAuthConnectionsManager.findConnectionById(connectionOwnerProject, inConnectionId)
            if (connection == null) {
                return error_json("There no connection with id '$inConnectionId' found in project ${connectionOwnerProject.fullName}", HttpServletResponse.SC_NOT_FOUND);
            }
        } else {
            connection = null
        }

        if ("repository" == inType) {
            if (inProjectId.isNullOrEmpty()) {
                return error_json("Required parameter 'projectId' is missing", HttpServletResponse.SC_BAD_REQUEST);
            }
            project = ProjectManager.findProjectByExternalId(inProjectId) ?: return error_json("There no project with external id $inProjectId", HttpServletResponse.SC_NOT_FOUND)
            info = Util.getGitHubInfo(inId) ?: return error_json("Not an GitHub VCS", HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            LOG.info("Trying to create web hook for repository '$inId', github info is $info, user is ${user.describe(false)}, connection is ${connection?.id ?: "not specified"}")

        } else {
            // Vcs Root Info
            val id: Long
            val vcsRoot: SVcsRoot
            val vcsRootInstance: VcsRootInstance?
            try {
                id = inId.toLong()
            } catch(e: NumberFormatException) {
                return error_json("Incorrect format of parameter 'id', integer number expected", HttpServletResponse.SC_BAD_REQUEST)
            }
            if ("root" == inType) {
                vcsRootInstance = null
                vcsRoot = VcsManager.findRootById(id) ?: return error_json("VcsRoot with id '$id' not found", HttpServletResponse.SC_NOT_FOUND)
            } else if ("instance" == inType) {
                vcsRootInstance = VcsManager.findRootInstanceById(id) ?: return error_json("VcsRootInstance with id '$id' not found", HttpServletResponse.SC_NOT_FOUND)
                vcsRoot = vcsRootInstance.parent
            } else {
                return error_json("Parameter 'type' have unknown value", HttpServletResponse.SC_BAD_REQUEST)
            }
            info = Util.getGitHubInfo(vcsRootInstance ?: vcsRoot) ?: return error_json("Not an GitHub VCS", HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            project = vcsRoot.project
            LOG.info("Trying to create web hook for vcs (id:$id), github info is $info, user is ${user.describe(false)}, connection is ${connection?.id ?: "not specified"}")
        }

        // TODO: check info.server to be github.com or known GitHub Enterprise server

        val connections: List<OAuthConnectionDescriptor>
        if (connection != null) {
            connections = listOf(connection)
        } else {
            connections = Util.findConnections(OAuthConnectionsManager, project, info.server)
            if (connections.isEmpty()) {
                return error_json("No OAuth connectors found, setup them first", 500) //TODO: Add link, good UI.
            }
        }

        var postponedResult: JsonElement? = null

        attempts@
        for (i in 0..2) {
            val tokens = TokensHelper.getExistingTokens(connections, user)
            if (tokens.isEmpty()) {
                // obtain access token
                if (connection == null) {
                    connection = connections.first() //FIXME
                }
                LOG.info("No token found will try to obtain one using connection ${connection.id}")

                if (action == "continue") {
                    // Already from "/oauth/github/accessToken.html", cannot do anything else.
                    postponedResult = error_json("Cannot find token in connection ${connection.connectionDisplayName}.\nEnsure connection configured correctly", HttpServletResponse.SC_NOT_FOUND)
                    continue@attempts
                }
                val params = linkedMapOf("action" to "continue", "type" to inType, "id" to inId, "connectionId" to connection.id, "connectionProjectId" to connection.project.externalId)
                if (inProjectId != null) {
                    params.put("projectId", inProjectId)
                }
                return redirect_json(url(request.contextPath + "/oauth/github/accessToken.html",
                        "action" to "obtainToken",
                        "connectionId" to connection.id,
                        "projectId" to connection.project.projectId,
                        "scope" to "write:repo_hook",
                        "callbackUrl" to url(request.contextPath + PATH, params))
                )
            }

            for (entry in tokens) {
                val ghc: GitHubClientEx = GitHubClientFactory.createGitHubClient(entry.key.parameters[GitHubConstants.GITHUB_URL_PARAM]!!)
                for (token in entry.value) {
                    LOG.info("Trying with token: ${token.oauthLogin}, connector is ${entry.key.id}")
                    ghc.setOAuth2Token(token.accessToken)

                    try {
                        val result = manager.doRegisterWebHook(info, ghc)
                        val repoId = info.toString()
                        when (result) {
                            WebHooksManager.HookAddOperationResult.InvalidCredentials -> {
                                LOG.warn("Removing incorrect (outdated) token (user:${token.oauthLogin}, scope:${token.scope})")
                                OAuthTokensStorage.removeToken(entry.key.id, token.accessToken)
                            }
                            WebHooksManager.HookAddOperationResult.TokenScopeMismatch -> {
                                LOG.warn("Token (user:${token.oauthLogin}, scope:${token.scope}) have not enough scope")
                                // TODO: Update token scope
                                TokensHelper.markTokenIncorrect(token)
                                return gh_json(result.name, "Token scope does not cover hooks management", info)
                            }
                            WebHooksManager.HookAddOperationResult.AlreadyExists -> {
                                return gh_json(result.name, "Hook for repository '$repoId' already exits, updated info", info)
                            }
                            WebHooksManager.HookAddOperationResult.Created -> {
                                return gh_json(result.name, "Created hook for repository '$repoId'", info)
                            }
                            WebHooksManager.HookAddOperationResult.NoAccess -> {
                                return gh_json(result.name, "No access to repository '$repoId'", info)
                            }
                            WebHooksManager.HookAddOperationResult.UserHaveNoAccess -> {
                                return gh_json(result.name, "You don't have access to '$repoId'", info);
                            }
                        }
                    } catch(e: RequestException) {
                        LOG.warnAndDebugDetails("Unexpected response from github server", e);
                    } catch(e: IOException) {
                        LOG.warnAndDebugDetails("IOException instead of response from github server", e);
                    }
                }
            }
        }

        return postponedResult ?: gh_json("", "", info)
    }


    private fun doHandleCheckAction(request: HttpServletRequest, response: HttpServletResponse, action: String, popup: Boolean): JsonElement {
        TODO("Implement")
    }

    private fun doHandleDeleteAction(request: HttpServletRequest, response: HttpServletResponse, action: String, popup: Boolean): JsonElement {
        TODO("Implement")
    }

    protected fun error_json(message: String, @MagicConstant(flagsFromClass = HttpServletResponse::class) code: Int): JsonElement {
        val obj = JsonObject()
        obj.addProperty("error", message)
        obj.addProperty("code", code)
        return obj
    }

    protected fun redirect_json(url: String): JsonElement {
        val obj = JsonObject()
        obj.addProperty("redirect", url)
        return obj
    }

    private fun url(url: String, vararg params: Pair<String, Any>): String {
        return url(url, params.associateBy({ it -> it.first }, { it -> it.second.toString() }))
    }

    private fun url(url: String, params: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append(url)
        if (!params.isEmpty()) sb.append('?')
        for (e in params.entries) {
            sb.append(e.key).append('=').append(WebUtil.encode(e.value)).append('&')
        }
        return sb.toString()
    }

    protected fun gh_json(result: String, message: String, info: VcsRootGitHubInfo): JsonElement {
        val obj = JsonObject()
        obj.addProperty("result", result)
        obj.addProperty("message", message)
        obj.add("info", Gson().toJsonTree(info))
        return obj
    }
}