package org.jetbrains.teamcity.github.controllers

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonWriter
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.auth.AccessDeniedException
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthToken
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientFactory
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.util.PropertiesUtil
import jetbrains.buildServer.util.StringUtil
import jetbrains.buildServer.vcs.VcsRootInstance
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.SessionUser
import jetbrains.buildServer.web.util.WebUtil
import org.intellij.lang.annotations.MagicConstant
import org.jetbrains.teamcity.github.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.web.servlet.ModelAndView
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.UnknownHostException
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

public class WebHooksController(private val descriptor: PluginDescriptor, server: SBuildServer) : BaseController(server) {

    @Autowired
    lateinit var myWebControllerManager: WebControllerManager

    @Autowired
    lateinit var myOAuthConnectionsManager: OAuthConnectionsManager

    @Autowired
    lateinit var myOAuthTokensStorage: OAuthTokensStorage

    @Autowired
    lateinit var myWebHooksManager: WebHooksManager

    @Autowired
    lateinit var myTokensHelper: TokensHelper

    @Autowired
    lateinit var myProjectManager: ProjectManager

    private val myResultJspPath = descriptor.getPluginResourcesPath("hook-created.jsp")


    public fun register(): Unit {
        myWebControllerManager.registerController(PATH, this)
    }

    companion object {
        public val PATH = "/oauth/github/webhooks.html"

        private val LOG = Logger.getInstance(WebHooksController::class.java.name)

        class RequestException private constructor(val element: JsonElement) : Exception() {
            constructor(message: String, @MagicConstant(flagsFromClass = HttpServletResponse::class) code: Int) : this(error_json(message, code)) {
            }
        }

        public fun getRepositoryInfo(info: VcsRootGitHubInfo?, manager: WebHooksManager): JsonObject {
            val element = JsonObject()
            val hook = info?.let { manager.getHook(it) }
            val status = getHookStatus(hook)
            val actions = status.getActions()
            if (info == null) {
                element.addProperty("error", "not an github repository url")
            }
            element.addProperty("repository", info.toString())
            element.add("info", Gson().toJsonTree(info))
            element.add("hook", Gson().toJsonTree(hook))
            element.add("status", Gson().toJsonTree(status.status))
            element.add("actions", Gson().toJsonTree(actions))
            return element
        }
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        var action = request.getParameter("action")
        val popup = PropertiesUtil.getBoolean(request.getParameter("popup"))
        val element: JsonElement
        try {
            if (action in listOf("add", "check", "delete", "ping", "install", null)) {
                element = doHandleAction(request, action, popup)
            } else if ("continue-add" == action) {
                element = doHandleAction(request, action, popup)
                action = request.getParameter("original_action") ?: "add"
            } else if ("continue-install" == action) {
                element = doHandleAction(request, action, popup)
                action = request.getParameter("original_action") ?: "install"
            } else if ("check-all" == action) {
                element = doHandleCheckAllAction(request, popup)
            } else if ("get-info" == action) {
                element = doHandleGetInfoAction(request)
            } else if ("set-cci" == action) {
                element = doSetCCIAction(request)
            } else {
                LOG.warn("Unknown action '$action'")
                return null
            }
        } catch(e: RequestException) {
            element = e.element
        }
        if (element is JsonObject) {
            element.addProperty("action", action)
        }
        if (!popup) {
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            val writer = JsonWriter(OutputStreamWriter(response.outputStream))
            Gson().toJson(element, writer)
            writer.flush()
            return null
        } else {
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

    @Throws(RequestException::class)
    private fun doHandleAction(request: HttpServletRequest, action: String, popup: Boolean): JsonElement {
        val user = SessionUser.getUser(request) ?: return error_json("Not authenticated", HttpServletResponse.SC_UNAUTHORIZED)

        val inType = request.getParameter("type")?.toLowerCase() ?: return error_json("Required parameter 'type' is missing", HttpServletResponse.SC_BAD_REQUEST)
        val inId = request.getParameter("id") ?: return error_json("Required parameter 'id' is missing", HttpServletResponse.SC_BAD_REQUEST)
        val inProjectId = request.getParameter("projectId")

        if (StringUtil.isEmptyOrSpaces(inType)) return error_json("Required parameter 'type' is empty", HttpServletResponse.SC_BAD_REQUEST)
        if (StringUtil.isEmptyOrSpaces(inId)) return error_json("Required parameter 'id' is empty", HttpServletResponse.SC_BAD_REQUEST)

        var connection: OAuthConnectionDescriptor? = getConnection(request, inProjectId)

        val info: VcsRootGitHubInfo
        val project: SProject

        if ("repository" == inType) {
            val pair = getRepositoryInfo(inProjectId, inId)
            project = pair.first
            info = pair.second
            LOG.info("Trying to create web hook for repository with id '$inId', repository info is '$info', user is '${user.describe(false)}', connection is ${connection?.id ?: "not specified in request"}")
        } else {
            return error_json("Parameter 'type' have unknown value", HttpServletResponse.SC_BAD_REQUEST)
        }

        if (connection != null && !Util.isConnectionToServer(connection, info.server)) {
            return error_json("OAuth connection '${connection.connectionDisplayName}' server doesn't match repository server '${info.server}'", HttpServletResponse.SC_BAD_REQUEST)
        }

        val connections: List<OAuthConnectionDescriptor>
        if (connection != null) {
            connections = listOf(connection)
        } else {
            connections = getConnections(info.server, project)
            if (connections.isEmpty()) {
                return gh_json("NoOAuthConnections", "No OAuth connection found for GitHub server '${info.server}' in project '${project.fullName}' and it parents, configure it first", info)
            }
            // Let's use connection from most nested project. (connections sorted in reverse project hierarchy order)
            connection = connections.first()
        }

        var postponedResult: JsonElement? = null

        attempts@
        for (i in 0..2) {
            val tokens = myTokensHelper.getExistingTokens(connections, user)
            if (tokens.isEmpty()) {
                // obtain access token
                LOG.info("No token found will try to obtain one using connection ${connection.id}")

                if (action == "continue") {
                    // Already from "/oauth/github/accessToken.html", cannot do anything else.
                    postponedResult = gh_json("NoTokens", "Cannot find token in connection ${connection.connectionDisplayName}.\nEnsure connection configured correctly", info)
                    continue@attempts
                }
                val params = linkedMapOf("action" to "continue", "original_action" to action, "popup" to popup, "type" to inType, "id" to inId, "connectionId" to connection.id, "connectionProjectId" to connection.project.externalId)
                if (inProjectId != null) {
                    params.put("projectId", inProjectId)
                }
                return redirect_json(url(request.contextPath + "/oauth/github/accessToken.html",
                        "action" to "obtainToken",
                        "connectionId" to connection.id,
                        "projectId" to connection.project.externalId,
                        "scope" to "admin:repo_hook",
                        "callbackUrl" to url(request.contextPath + PATH, params))
                )
            }

            for (entry in tokens) {
                val ghc: GitHubClientEx = GitHubClientFactory.createGitHubClient(entry.key.parameters[GitHubConstants.GITHUB_URL_PARAM]!!)
                for (token in entry.value) {
                    LOG.info("Trying with token: ${token.oauthLogin}, connector is ${entry.key.id}")
                    ghc.setOAuth2Token(token.accessToken)
                    val element: JsonElement?

                    try {
                        @Suppress("NAME_SHADOWING")
                        val action =
                                if (action == "continue") {
                                    request.getParameter("original_action") ?: "add"
                                } else action
                        if ("add" == action) {
                            element = doAddWebHook(ghc, info)
                        } else if ("check" == action) {
                            element = doCheckWebHook(ghc, info)
                        } else if ("ping" == action) {
                            element = doPingWebHook(ghc, info)
                        } else if ("delete" == action) {
                            element = doDeleteWebHook(ghc, info)
                        } else if ("install" == action) {
                            element = doInstallWebHook(ghc, info)
                        } else {
                            element = null
                        }
                        if (element != null) return element
                    } catch(e: GitHubAccessException) {
                        element = getErrorResult(e, connection, info, token)
                        if (element != null) return element;
                    } catch(e: RequestException) {
                        postponedResult = error_json("Exception: ${e.message}", HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                        LOG.warnAndDebugDetails("Unexpected response from github server", e)
                    } catch(e: UnknownHostException) {
                        // It seems host is (temporarily?) unavailable
                        postponedResult = gh_json("Error", "Unknown host \'${e.message}\'. Probably there's connection problem between TeamCity server and GitHub server", info)
                    } catch(e: IOException) {
                        postponedResult = error_json("Exception: ${e.message}", HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                        LOG.warnAndDebugDetails("IOException instead of response from github server", e)
                    }
                }
            }
        }

        return postponedResult ?: gh_json("", "", info)
    }

    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doAddWebHook(ghc: GitHubClientEx, info: VcsRootGitHubInfo): JsonElement? {
        val result = myWebHooksManager.doRegisterWebHook(info, ghc)
        when (result) {
            WebHooksManager.HookAddOperationResult.AlreadyExists -> {
                return gh_json(result.name, "Hook for repository '${info.toString()}' already exists, updated info", info)
            }
            WebHooksManager.HookAddOperationResult.Created -> {
                return gh_json(result.name, "Created hook for repository '${info.toString()}'", info)
            }
        }
    }

    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doInstallWebHook(ghc: GitHubClientEx, info: VcsRootGitHubInfo): JsonElement? {
        val result = myWebHooksManager.doRegisterWebHook(info, ghc)
        when (result) {
            WebHooksManager.HookAddOperationResult.AlreadyExists -> {
                return gh_json(result.name, "Hook for repository '${info.toString()}' already exists, updated info", info)
            }
            WebHooksManager.HookAddOperationResult.Created -> {
                return gh_json(result.name, "Created hook for repository '${info.toString()}'", info)
            }
        }
    }


    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doCheckWebHook(ghc: GitHubClientEx, info: VcsRootGitHubInfo): JsonElement? {
        val result = myWebHooksManager.doGetAllWebHooks(info, ghc)
        when (result) {
            WebHooksManager.HooksGetOperationResult.Ok -> {
                val hook = myWebHooksManager.getHook(info)
                if (hook != null) {
                    return gh_json(result.name, "Updated hook info for repository '${info.toString()}'", info)
                } else {
                    return gh_json(result.name, "No hook found for repository '${info.toString()}'", info)
                }
            }
        }
    }

    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doPingWebHook(ghc: GitHubClientEx, info: VcsRootGitHubInfo): JsonElement? {
        val result = myWebHooksManager.doGetAllWebHooks(info, ghc)
        when (result) {
            WebHooksManager.HooksGetOperationResult.Ok -> {
                val hook = myWebHooksManager.getHook(info)
                // Ensure test message was sent
                myWebHooksManager.TestWebHook.doRun(info, ghc)
                if (hook != null) {
                    return gh_json(result.name, "Asked server '${info.server}' to resend 'ping' event for repository '${info.getRepositoryId()}'", info)
                } else {
                    return gh_json(result.name, "No hook found for repository '${info.toString()}'", info)
                }
            }
        }
    }

    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doDeleteWebHook(ghc: GitHubClientEx, info: VcsRootGitHubInfo): JsonElement? {
        val result = myWebHooksManager.doUnRegisterWebHook(info, ghc)
        when (result) {
            WebHooksManager.HookDeleteOperationResult.NeverExisted -> {
                return gh_json(result.name, "Hook for repository '${info.toString()}' never existed", info)
            }
            WebHooksManager.HookDeleteOperationResult.Removed -> {
                return gh_json(result.name, "Removed hook for repository '${info.toString()}'", info)
            }
        }
    }

    private fun getErrorResult(e: GitHubAccessException, connection: OAuthConnectionDescriptor, info: VcsRootGitHubInfo, token: OAuthToken): JsonElement? {
        when (e.type) {
            GitHubAccessException.Type.InvalidCredentials -> {
                LOG.warn("Removing incorrect (outdated) token (user:${token.oauthLogin}, scope:${token.scope})")
                myOAuthTokensStorage.removeToken(connection.id, token.accessToken)
            }
            GitHubAccessException.Type.TokenScopeMismatch -> {
                LOG.warn("Token (user:${token.oauthLogin}, scope:${token.scope}) have not enough scope")
                // TODO: Update token scope
                myTokensHelper.markTokenIncorrect(token)
                return gh_json(e.type.name, e.message ?: "Token scope does not cover hooks management", info)
            }
            GitHubAccessException.Type.UserHaveNoAccess -> {
                return gh_json(e.type.name, "You don't have access to '${info.toString()}'", info)
            }
            GitHubAccessException.Type.NoAccess -> {
                return gh_json(e.type.name, "No access to repository '${info.toString()}'", info)
            }
        }
        return null
    }

    private fun doHandleCheckAllAction(request: HttpServletRequest, popup: Boolean): JsonElement {
        val user = SessionUser.getUser(request) ?: return error_json("Not authenticated", HttpServletResponse.SC_UNAUTHORIZED)

        val inProjectId = request.getParameter("projectId")
        if (inProjectId.isNullOrEmpty()) {
            return error_json("Required parameter 'projectId' is missing", HttpServletResponse.SC_BAD_REQUEST)
        }
        val project = myProjectManager.findProjectByExternalId(inProjectId) ?: return error_json("There no project with external id $inProjectId", HttpServletResponse.SC_NOT_FOUND)

        val recursive = PropertiesUtil.getBoolean(request.getParameter("recursive"))

        // If connection info specified, only webhooks from that server would be checked
        var connection: OAuthConnectionDescriptor? = getConnection(request, inProjectId)

        if (popup && connection == null) {
            return error_json("Popup==true requires connection parameters", HttpServletResponse.SC_BAD_REQUEST)
        }
        // TODO: Support popup && connection

        val allGitVcsInstances = HashSet<VcsRootInstance>()
        Util.findSuitableRoots(project, recursive = recursive) {
            allGitVcsInstances.add(it)
            true
        }

        val mapServerToInfos = allGitVcsInstances
                .mapNotNull { Util.Companion.getGitHubInfo(it) }
                .toHashSet()
                // Filter by connection (if any specified)
                .filter { connection == null || Util.isConnectionToServer(connection, it.server) }
                .groupBy { it.server }

        val toCheck = mapServerToInfos
                .mapValues {
                    it.value.filter { Status.OK != getHookStatus(myWebHooksManager.getHook(it)).status }
                }.filterValues { it.isNotEmpty() }

        // For each repository return either check result or redirect request to show in UI.
        // Redirect would be in case of no connections of no tokens for server/repo/user.
        val arr = JsonArray()
        for ((server, infos) in toCheck) {
            val connections = if (connection != null) listOf(connection) else getConnections(server, project)
            if (connections.isEmpty()) {
                for (info in infos) {
                    val message = "No OAuth connection found for GitHub server '${info.server}' in project '${project.fullName}' and it parents, configure it first"
                    val obj = gh_json("NoOAuthConnections", message, info)
                    obj.addProperty("error", message)
                    obj.addProperty("user_action_required", true)
                    arr.add(obj)
                }
                continue
            }
            val connectionToTokensMap = myTokensHelper.getExistingTokens(connections, user)
            if (connectionToTokensMap.isEmpty()) {
                for (info in infos) {
                    val message = "No tokens to access server $server"
                    val obj = gh_json("NoTokens", message, info)
                    obj.addProperty("error", message)
                    obj.addProperty("user_action_required", true)
                    arr.add(obj)
                }
                continue
            }
            for (info in infos) {
                var elements = ArrayList<JsonElement>()
                @Suppress("NAME_SHADOWING")
                for ((connection, tokens) in connectionToTokensMap) {
                    val ghc: GitHubClientEx = GitHubClientFactory.createGitHubClient(connection.parameters[GitHubConstants.GITHUB_URL_PARAM]!!)
                    for (token in tokens) {
                        LOG.info("Trying with token: ${token.oauthLogin}, connector is ${connection.id}")
                        ghc.setOAuth2Token(token.accessToken)
                        try {
                            val element = doCheckWebHook(ghc, info)
                            if (element != null) {
                                elements.add(element)
                            }
                        } catch(e: GitHubAccessException) {
                            val element = getErrorResult(e, connection, info, token)
                            if (element != null) elements.add(element);
                        } catch(e: RequestException) {
                            LOG.warnAndDebugDetails("Unexpected response from github server", e)
                        } catch(e: UnknownHostException) {
                            // It seems host is (temporarily?) unavailable
                            elements.add(error_json("CannotAccessGitHub", HttpServletResponse.SC_SERVICE_UNAVAILABLE))
                        } catch(e: IOException) {
                            LOG.warnAndDebugDetails("IOException instead of response from github server", e)
                        }
                    }
                }
                // TODO: Choose better (non-error) element from 'elements'
                arr.add(elements.firstOrNull() ?: getRepositoryInfo(info, myWebHooksManager))
            }
        }
        val o = JsonObject()
        o.add("data", arr)
        return o
    }

    private fun getConnections(server: String, project: SProject): List<OAuthConnectionDescriptor> {
        return Util.findConnections(myOAuthConnectionsManager, project, server)
    }

    private fun doHandleGetInfoAction(request: HttpServletRequest): JsonElement {
        val inRepositories = request.getParameterValues("repository") ?: return error_json("Missing required parameter 'repository'", HttpServletResponse.SC_BAD_REQUEST)
        val array = JsonArray()
        for (inRepository in inRepositories) {
            val info = Util.getGitHubInfo(inRepository)
            array.add(getRepositoryInfo(info, myWebHooksManager))
        }
        val result = JsonObject()
        result.add("result", array)
        return result
    }

    private fun doSetCCIAction(request: HttpServletRequest): JsonElement {
        SessionUser.getUser(request) ?: return error_json("Not authenticated", HttpServletResponse.SC_UNAUTHORIZED)
        val inVcsRootId = request.getParameter("vcsRootId") ?: return error_json("Missing required parameter 'vcsRootId'", HttpServletResponse.SC_BAD_REQUEST)
        val vcsRootId: Long
        try {
            vcsRootId = inVcsRootId.toLong()
        } catch(e: NumberFormatException) {
            return error_json("Parameter 'vcsRootId' should be long number, got: $inVcsRootId", HttpServletResponse.SC_BAD_REQUEST)
        }
        val vcsRoot = myServer.vcsManager.findRootById(vcsRootId) ?: return error_json("VCS Root specified by parameter 'vcsRootId'($vcsRootId) not found", HttpServletResponse.SC_NOT_FOUND)
        try {
            vcsRoot.modificationCheckInterval = 3600;
        } catch(e: AccessDeniedException) {
            return error_json("Access Denied:" + (e.message ?: "") , HttpServletResponse.SC_FORBIDDEN)
        }
        val result = JsonObject()
        result.addProperty("result", "Ok")
        result.addProperty("vcsRootId", vcsRootId)
        result.addProperty("message", "VCS Root ${vcsRoot.vcsDisplayName} Changes Checking interval set to 1 hour.")
        return result
    }

    @Throws(RequestException::class)
    private fun getConnection(request: HttpServletRequest, inProjectId: String?): OAuthConnectionDescriptor? {
        val inConnectionId = request.getParameter("connectionId")
        val inConnectionProjectId = request.getParameter("connectionProjectId") ?: inProjectId
        if (inConnectionId == null || inConnectionProjectId == null) {
            return null
        }
        val connectionOwnerProject = myProjectManager.findProjectByExternalId(inConnectionProjectId)
        @Suppress("IfNullToElvis")
        if (connectionOwnerProject == null) {
            throw RequestException("There no project with external id $inConnectionProjectId", HttpServletResponse.SC_NOT_FOUND)
        }
        val connection = myOAuthConnectionsManager.findConnectionById(connectionOwnerProject, inConnectionId)
        @Suppress("IfNullToElvis")
        if (connection == null) {
            throw RequestException("There no connection with id '$inConnectionId' found in project ${connectionOwnerProject.fullName}", HttpServletResponse.SC_NOT_FOUND)
        }
        return connection
    }

    @Throws(RequestException::class)
    fun getRepositoryInfo(inProjectId: String?, inId: String): Pair<SProject, VcsRootGitHubInfo> {
        if (inProjectId.isNullOrEmpty()) {
            throw RequestException("Required parameter 'projectId' is missing", HttpServletResponse.SC_BAD_REQUEST)
        }
        var project = myProjectManager.findProjectByExternalId(inProjectId) ?: throw RequestException("There no project with external id $inProjectId", HttpServletResponse.SC_NOT_FOUND)
        var info = Util.Companion.getGitHubInfo(inId) ?: throw RequestException("Malformed GitHub repository url", HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        return project to info
    }


    fun gh_json(result: String, message: String, info: VcsRootGitHubInfo): JsonObject {
        val obj = WebHooksController.getRepositoryInfo(info, myWebHooksManager)
        obj.addProperty("result", result)
        obj.addProperty("message", message)
        return obj
    }
}

fun error_json(message: String, @MagicConstant(flagsFromClass = HttpServletResponse::class) code: Int): JsonElement {
    val obj = JsonObject()
    obj.addProperty("error", message)
    obj.addProperty("code", code)
    return obj
}

fun redirect_json(url: String): JsonElement {
    val obj = JsonObject()
    obj.addProperty("redirect", url)
    return obj
}

private fun url(url: String, vararg params: Pair<String, Any>): String {
    return url(url, params.associateBy({ it -> it.first }, { it -> it.second.toString() }))
}

private fun url(url: String, params: Map<String, Any>): String {
    val sb = StringBuilder()
    sb.append(url)
    if (!params.isEmpty()) sb.append('?')
    for (e in params.entries) {
        sb.append(e.key).append('=').append(WebUtil.encode(e.value.toString())).append('&')
    }
    return sb.toString()
}