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
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthToken
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientFactory
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.util.PropertiesUtil
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.SessionUser
import jetbrains.buildServer.web.util.WebUtil
import org.eclipse.egit.github.core.client.RequestException
import org.intellij.lang.annotations.MagicConstant
import org.jetbrains.teamcity.github.*
import org.jetbrains.teamcity.github.action.HookAddOperationResult
import org.jetbrains.teamcity.github.action.HookDeleteOperationResult
import org.springframework.http.MediaType
import org.springframework.web.servlet.ModelAndView
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.UnknownHostException
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class WebHooksController(descriptor: PluginDescriptor,
                         val myWebControllerManager: WebControllerManager,
                         val myOAuthConnectionsManager: OAuthConnectionsManager,
                         val myOAuthTokensStorage: OAuthTokensStorage,
                         val myWebHooksManager: WebHooksManager,
                         val myTokensHelper: TokensHelper,
                         val myProjectManager: ProjectManager,
                         server: SBuildServer) : BaseController(server) {


    private val myResultJspPath = descriptor.getPluginResourcesPath("hook-created.jsp")


    fun register(): Unit {
        myWebControllerManager.registerController(PATH, this)
    }

    companion object {
        val PATH = "/oauth/github/webhooks.html"

        private val LOG = Logger.getInstance(WebHooksController::class.java.name)

        class MyRequestException private constructor(val element: JsonElement) : Exception() {
            constructor(message: String, @MagicConstant(flagsFromClass = HttpServletResponse::class) code: Int) : this(error_json(message, code)) {
            }
        }

        fun getRepositoryInfo(info: GitHubRepositoryInfo?, manager: WebHooksManager): JsonObject {
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


        fun gh_json(result: String, message: String, info: GitHubRepositoryInfo, webHooksManager: WebHooksManager): JsonObject {
            val obj = WebHooksController.getRepositoryInfo(info, webHooksManager)
            obj.addProperty("result", result)
            obj.addProperty("message", message)
            return obj
        }
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        var action = request.getParameter("action")
        val popup = PropertiesUtil.getBoolean(request.getParameter("popup"))
        val element: JsonElement
        try {
            if (action in listOf("add", "check", "delete", "ping", "install")) {
                element = doHandleAction(request, action, popup)
            } else if ("continue" == action) {
                element = doHandleAction(request, action, popup)
                action = request.getParameter("original_action") ?: "add"
            } else if ("check-all" == action) {
                element = doHandleCheckAllAction(request, popup)
            } else if ("get-info" == action) {
                element = doHandleGetInfoAction(request)
            } else {
                LOG.warn("Unknown action '$action'")
                response.status = HttpServletResponse.SC_NOT_FOUND
                return simpleView("Unknown action '$action'")
            }
        } catch(e: MyRequestException) {
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

    @Throws(MyRequestException::class)
    private fun doHandleAction(request: HttpServletRequest, action: String, popup: Boolean): JsonElement {
        val user = SessionUser.getUser(request) ?: return error_json("Not authenticated", HttpServletResponse.SC_UNAUTHORIZED)

        val inId = request.getParameter("id")?.trim() ?: return error_json("Required parameter 'Repository Url' is missing", HttpServletResponse.SC_BAD_REQUEST)
        val inProjectId = request.getParameter("projectId")

        if (inId.isNullOrBlank()) return error_json("Required parameter 'Repository Url' is empty", HttpServletResponse.SC_BAD_REQUEST)

        var connection: OAuthConnectionDescriptor? = getConnection(request, inProjectId)

        val (project, info) = getRepositoryInfo(inProjectId, inId)

        LOG.info("Trying to create web hook for repository with id '$inId', repository info is '$info', user is '${user.describe(false)}', connection is ${connection?.id ?: "not specified in request"}")

        if (connection != null && !Util.isConnectionToServer(connection, info.server)) {
            return error_json("OAuth connection '${connection.connectionDisplayName}' server doesn't match repository server '${info.server}'", HttpServletResponse.SC_BAD_REQUEST)
        }

        val connections: List<OAuthConnectionDescriptor>
        if (connection != null) {
            connections = listOf(connection)
        } else {
            connections = getConnections(info.server, project)
            if (connections.isEmpty()) {
                return gh_json("NoOAuthConnections", getNoOAuthConnectionMessage(info, project, request), info)
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
                val params = linkedMapOf("action" to "continue", "original_action" to action, "popup" to popup, "id" to inId, "connectionId" to connection.id, "connectionProjectId" to connection.project.externalId)
                if (inProjectId != null) {
                    params.put("projectId", inProjectId)
                }
                return redirect_json(url(request.contextPath + "/oauth/github/accessToken.html",
                                         "action" to "obtainToken",
                                         "connectionId" to connection.id,
                                         "projectId" to connection.project.externalId,
                                         "scope" to "public_repo,repo,repo:status,write:repo_hook,admin:repo_hook",
                                         "callbackUrl" to url(request.contextPath + PATH, params))
                )
            }

            for ((key, value) in tokens) {
                val ghc: GitHubClientEx = GitHubClientFactory.createGitHubClient(key.parameters[GitHubConstants.GITHUB_URL_PARAM]!!)
                for (token in value) {
                    LOG.info("Trying with token: ${token.oauthLogin}, connector is ${key.id}")
                    ghc.setOAuth2Token(token.accessToken)
                    val element: JsonElement?

                    try {
                        @Suppress("NAME_SHADOWING")
                        val action =
                                if (action == "continue") {
                                    request.getParameter("original_action") ?: "add"
                                } else action
                        if ("add" == action) {
                            element = doAddWebHook(ghc, info, user, key)
                        } else if ("check" == action) {
                            element = doCheckWebHook(ghc, info)
                        } else if ("ping" == action) {
                            element = doTestWebHook(ghc, info)
                        } else if ("delete" == action) {
                            element = doDeleteWebHook(ghc, info)
                        } else if ("install" == action) {
                            element = doInstallWebHook(ghc, info, user, key)
                        } else {
                            element = null
                        }
                        if (element != null) return element
                    } catch(e: GitHubAccessException) {
                        element = getErrorResult(e, connection, info, token)
                        if (element != null) return element
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

    private fun getNoOAuthConnectionMessage(info: GitHubRepositoryInfo, project: SProject, request: HttpServletRequest): String {
        val createOAuthConnectionUrl = getUrlToCreateOAuthConnection(request, project, info.server)
        return "No OAuth connection found for GitHub server '${info.server}' in project '${project.fullName}' and it parents, <a href=\"$createOAuthConnectionUrl\">configure it first</a>"
    }

    private fun getUrlToCreateOAuthConnection(request: HttpServletRequest, project: SProject, server: String): String {
        val params = LinkedHashMap<String, Any>()
        params["projectId"] = project.externalId
        params["tab"] = "oauthConnections"
        val currentPageUrl = request.getParameter("currentPageUrl")
        if (currentPageUrl != null) {
            params["afterAddUrl"] = currentPageUrl
        };
        val hash: String
        if (Util.isSameUrl(server, "github.com")) {
            hash = "addDialog=GitHub"
        } else {
            hash = "addDialog=GHE&gitHubUrl=${WebUtil.encode(server)}"
        }
        val createOAuthConnectionUrl = url(request.contextPath + "/admin/editProject.html", params) + "#$hash";
        return createOAuthConnectionUrl
    }

    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doAddWebHook(ghc: GitHubClientEx, info: GitHubRepositoryInfo, user: SUser, connection: OAuthConnectionDescriptor): JsonElement? {
        val result = myWebHooksManager.doInstallWebHook(info, ghc, user, connection)
        when (result.first) {
            HookAddOperationResult.AlreadyExists -> {
                return gh_json(result.first.name, "Hook for repository '${info.toString()}' already exists, updated info", info)
            }
            HookAddOperationResult.Created -> {
                return gh_json(result.first.name, "Created hook for repository '${info.toString()}'", info)
            }
        }
    }

    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doInstallWebHook(ghc: GitHubClientEx, info: GitHubRepositoryInfo, user: SUser, connection: OAuthConnectionDescriptor): JsonElement? {
        val result = myWebHooksManager.doInstallWebHook(info, ghc, user, connection)
        val url = "${info.getRepositoryUrl()}/settings/hooks/${result.second.id}"
        when (result.first) {
            HookAddOperationResult.AlreadyExists -> {
                return gh_json(result.first.name, "Hook for repository <a href=\"${info.getRepositoryUrl()}\">${info.toString()}</a> <a href=\"$url\">already exists</a>, updated info", info)
            }
            HookAddOperationResult.Created -> {
                return gh_json(result.first.name, "Created <a href=\"$url\">hook</a> for repository <a href=\"${info.getRepositoryUrl()}\">${info.toString()}</a>", info)
            }
        }
    }


    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doCheckWebHook(ghc: GitHubClientEx, info: GitHubRepositoryInfo): JsonElement? {
        myWebHooksManager.doGetAllWebHooks(info, ghc)
        val hook = myWebHooksManager.getHook(info)
        if (hook != null) {
            return gh_json("Ok", "Updated hook info for repository '${info.toString()}'", info)
        } else {
            return gh_json("Ok", "No hook found for repository '${info.toString()}'", info)
        }
    }

    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doTestWebHook(ghc: GitHubClientEx, info: GitHubRepositoryInfo): JsonElement? {
        myWebHooksManager.doGetAllWebHooks(info, ghc)
        val hook = myWebHooksManager.getHook(info)
        @Suppress("IfNullToElvis")
        if (hook == null) {
            return gh_json("NotFound", "No hook found for repository '${info.toString()}'", info)
        }
        // Ensure test message was sent
        myWebHooksManager.doTestWebHook(info, ghc, hook)
        return gh_json("Ok", "Asked server '${info.server}' to resend 'ping' event for repository '${info.getRepositoryId()}'", info)
    }

    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doDeleteWebHook(ghc: GitHubClientEx, info: GitHubRepositoryInfo): JsonElement? {
        val result = myWebHooksManager.doDeleteWebHook(info, ghc)
        when (result) {
            HookDeleteOperationResult.NeverExisted -> {
                return gh_json(result.name, "Hook for repository '${info.toString()}' never existed", info)
            }
            HookDeleteOperationResult.Removed -> {
                return gh_json(result.name, "Removed hook for repository '${info.toString()}'", info)
            }
        }
    }

    private fun getErrorResult(e: GitHubAccessException, connection: OAuthConnectionDescriptor, info: GitHubRepositoryInfo, token: OAuthToken): JsonElement? {
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
            GitHubAccessException.Type.InternalServerError -> {
                LOG.warn("GitHub server ${info.server} returned 500")
                val contact =
                        if (Util.isSameUrl(info.server, "github.com")) "Check https://status.github.com/"
                        else "Contact your system administrator."
                return gh_json(e.type.name, "Error on GitHub side. $contact Try again later.", info)
            }
        }
        return null
    }

    @Throws(MyRequestException::class)
    private fun doHandleCheckAllAction(request: HttpServletRequest, popup: Boolean): JsonElement {
        val user = SessionUser.getUser(request) ?: return error_json("Not authenticated", HttpServletResponse.SC_UNAUTHORIZED)

        val inProjectId = request.getParameter("projectId")
        if (inProjectId.isNullOrEmpty()) {
            return error_json("Required parameter 'projectId' is missing", HttpServletResponse.SC_BAD_REQUEST)
        }
        val project = myProjectManager.findProjectByExternalId(inProjectId) ?: return error_json("There no project with external id $inProjectId", HttpServletResponse.SC_NOT_FOUND)

        val recursive = PropertiesUtil.getBoolean(request.getParameter("recursive"))

        // If connection info specified, only webhooks from that server would be checked
        val connection: OAuthConnectionDescriptor? = getConnection(request, inProjectId)

        if (popup && connection == null) {
            return error_json("Popup==true requires connection parameters", HttpServletResponse.SC_BAD_REQUEST)
        }
        // TODO: Support popup && connection

        val allGitVcsRoots = HashSet<SVcsRoot>()
        Util.findSuitableRoots(project, recursive = recursive) {
            allGitVcsRoots.add(it)
            true
        }

        val mapServerToInfos = allGitVcsRoots
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
                    val message = getNoOAuthConnectionMessage(info, project, request)
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
                val elements = ArrayList<JsonElement>()
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
                            if (element != null) elements.add(element)
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

    @Throws(MyRequestException::class)
    private fun getConnection(request: HttpServletRequest, inProjectId: String?): OAuthConnectionDescriptor? {
        val inConnectionId = request.getParameter("connectionId")
        val inConnectionProjectId = request.getParameter("connectionProjectId") ?: inProjectId
        if (inConnectionId.isNullOrBlank() || inConnectionId.isNullOrBlank()) {
            return null
        }
        val connectionOwnerProject = myProjectManager.findProjectByExternalId(inConnectionProjectId)
        @Suppress("IfNullToElvis")
        if (connectionOwnerProject == null) {
            throw MyRequestException("There no project with external id $inConnectionProjectId", HttpServletResponse.SC_NOT_FOUND)
        }
        val connection = myOAuthConnectionsManager.findConnectionById(connectionOwnerProject, inConnectionId)
        @Suppress("IfNullToElvis")
        if (connection == null) {
            throw MyRequestException("There no connection with id '$inConnectionId' found in project ${connectionOwnerProject.fullName}", HttpServletResponse.SC_NOT_FOUND)
        }
        return connection
    }

    @Throws(MyRequestException::class)
    fun getRepositoryInfo(inProjectId: String?, inId: String): Pair<SProject, GitHubRepositoryInfo> {
        if (inProjectId.isNullOrEmpty()) {
            throw MyRequestException("Required parameter 'projectId' is missing", HttpServletResponse.SC_BAD_REQUEST)
        }
        val project = myProjectManager.findProjectByExternalId(inProjectId) ?: throw MyRequestException("There no project with external id $inProjectId", HttpServletResponse.SC_NOT_FOUND)
        val info = Util.Companion.getGitHubInfo(inId) ?: throw MyRequestException("Malformed GitHub repository url", HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        return project to info
    }


    fun gh_json(result: String, message: String, info: GitHubRepositoryInfo): JsonObject {
        return gh_json(result, message, info, myWebHooksManager)
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
    for ((key, value) in params) {
        sb.append(key).append('=').append(WebUtil.encode(value.toString())).append('&')
    }
    return sb.toString()
}