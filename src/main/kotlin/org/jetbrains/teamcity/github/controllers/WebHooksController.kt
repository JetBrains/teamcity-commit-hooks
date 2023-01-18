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

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonWriter
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.controllers.SimpleView
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthToken
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientFactory
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.util.PropertiesUtil
import jetbrains.buildServer.util.StringUtil
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


    private val myTokenGrantedPath = descriptor.getPluginResourcesPath("tokenGranted.jsp")


    fun register() {
        myWebControllerManager.registerController(PATH, this)
    }

    companion object {
        const val PATH = "/webhooks/github/webhooks.html"

        private val LOG = Util.getLogger(WebHooksController::class.java)

        private open class MyRequestException private constructor(val element: JsonElement) : Exception() {
            constructor(message: String, @MagicConstant(flagsFromClass = HttpServletResponse::class) code: Int) : this(error_json(message, code))
        }

        private class NotFoundException(message: String) : MyRequestException(message, HttpServletResponse.SC_NOT_FOUND)
        private class AccessDeniedException(message: String) : MyRequestException(message, HttpServletResponse.SC_FORBIDDEN)

        fun getRepositoryInfo(info: GitHubRepositoryInfo?, manager: WebHooksManager): JsonObject {
            val element = JsonObject()
            val hook = info?.let { manager.getHook(it) }
            val status = getHookStatus(hook)
            val actions = status.getActions()
            if (info == null) {
                element.addProperty("error", "not a GitHub repository URL")
            }
            element.addProperty("repository", info?.id)
            element.add("info", Gson().toJsonTree(info))
            element.add("hook", Gson().toJsonTree(hook))
            element.add("status", Gson().toJsonTree(status.status))
            element.add("actions", Gson().toJsonTree(actions))
            return element
        }


        fun gh_json(result: String, message: String, info: GitHubRepositoryInfo, webHooksManager: WebHooksManager): JsonObject {
            val obj = getRepositoryInfo(info, webHooksManager)
            obj.addProperty("result", result)
            obj.addProperty("message", message)
            return obj
        }
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val action = request.getParameter("action")
        val popup = PropertiesUtil.getBoolean(request.getParameter("popup"))
        var element: JsonElement
        try {
            when (action) {
                in listOf("add", "check", "delete", "ping", "install") -> {
                    element = doHandleAction(request, action, popup)
                }
                "tokenGranted" -> {
                    return ModelAndView(myTokenGrantedPath)
                }
                "check-all" -> {
                    element = doHandleCheckAllAction(request, popup)
                }
                "get-info" -> {
                    element = doHandleGetInfoAction(request)
                }
                else -> {
                    LOG.warn("Unknown action '$action'")
                    response.status = HttpServletResponse.SC_NOT_FOUND
                    return simpleView("Unknown action '$action'")
                }
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
        return SimpleView.createTextView("Unrecognized request: " + WebUtil.getRequestDump(request) + ", action result: " + element.toString())
    }

    @Throws(MyRequestException::class)
    private fun doHandleAction(request: HttpServletRequest, action: String, popup: Boolean): JsonElement {
        val user = SessionUser.getUser(request) ?: return error_json("Not authenticated", HttpServletResponse.SC_UNAUTHORIZED)

        val inId = request.getParameter("id")?.trim()
        val inProjectId = request.getParameter("projectId")?.trim()

        if (inId == null || inId.isBlank()) return error_json("Required parameter 'id (Repository URL) is not specified", HttpServletResponse.SC_BAD_REQUEST)
        if (inProjectId == null || inProjectId.isBlank()) return error_json("Required parameter 'projectId' is missing", HttpServletResponse.SC_BAD_REQUEST)

        val info = Util.getGitHubInfo(inId) ?: return error_json("Malformed GitHub repository URL: $inId", HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        val project = getProject(inProjectId)

        if (!user.isPermissionGrantedForProject(project.projectId, Permission.EDIT_PROJECT)) {
            return error_json("User has no permission to edit project '${project.fullName}'", HttpServletResponse.SC_FORBIDDEN)
        }

        var connection: OAuthConnectionDescriptor? = getConnection(request, project)

        LOG.info("Trying to create a webhook for the GitHub repository with id '$inId', repository info is '${info.id}', user is '${user.describe(false)}', connection is ${connection?.id ?: "not specified in request"}")

        if (connection != null && !Util.isConnectionToServer(connection, info.server)) {
            return error_json("OAuth connection '${connection.connectionDisplayName}' server doesn't match repository server '${info.server}'", HttpServletResponse.SC_BAD_REQUEST)
        }

        val connections: List<OAuthConnectionDescriptor>
        if (connection != null) {
            connections = listOf(connection)
        } else {
            connections = getConnections(info.server, project)
            if (connections.isEmpty()) {
                return gh_json("NoOAuthConnections", getNoOAuthConnectionMessage(info, project, request), info, false)
            }
            // Let's use connection from most nested project. (connections sorted in reverse project hierarchy order)
            connection = connections.first()
        }

        var postponedResult: JsonElement? = null

        attempts@
        for (i in 0..2) {
            val tokens = myTokensHelper.getExistingTokens(project, connections, user)
            if (tokens.isEmpty()) {
                // obtain access token
                LOG.info("Could not find a valid GitHub token for user ${user.username}, will try to obtain one using connection ${connection.id}")

                if (action == "continue") {
                    // Already from "/oauth/github/accessToken.html", cannot do anything else.
                    postponedResult = gh_json("NoTokens", "Could not obtain token from GitHub using connection ${connection.connectionDisplayName}.\nPlease ensure connection is configured properly.", info)
                    continue@attempts
                }
                val params = linkedMapOf("action" to "tokenGranted", "popup" to popup,
                                         "id" to inId, "connectionId" to connection.id,
                                         "connectionProjectId" to connection.project.externalId,
                                         "projectId" to inProjectId)
                return redirect_json(url(request.contextPath + "/oauth/github/accessToken.html",
                                         "action" to "obtainToken",
                                         "connectionId" to connection.id,
                                         "projectId" to connection.project.externalId,
                                         "scope" to "public_repo,repo,repo:status,write:repo_hook",
                                         "callbackUrl" to url(request.contextPath + PATH, params))
                )
            }

            for ((key, value) in tokens) {
                val ghc: GitHubClientEx = GitHubClientFactory.createGitHubClient(key.parameters[GitHubConstants.GITHUB_URL_PARAM]!!)
                for (token in value) {
                    LOG.info("Trying with token: ${token.oauthLogin}, connector is ${key.id}")
                    ghc.setOAuth2Token(token.accessToken)
                    var element: JsonElement?

                    try {
                        @Suppress("NAME_SHADOWING")
                        val action =
                                if (action == "continue") {
                                    request.getParameter("original_action") ?: "add"
                                } else action
                        element = when (action) {
                            "add" -> doAddWebHook(ghc, info, user, key)
                            "check" -> doCheckWebHook(ghc, info)
                            "ping" -> doTestWebHook(ghc, info)
                            "delete" ->  doDeleteWebHook(ghc, info)
                            "install" -> doInstallWebHook(ghc, info, user, key)
                            else -> null
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
                        postponedResult = gh_json("Error", "Unknown host \'${e.message}\'. Probably there's a connection problem between TeamCity server and GitHub server", info)
                    } catch(e: IOException) {
                        postponedResult = error_json("Exception: ${e.message}", HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                        LOG.warnAndDebugDetails("IOException instead of response from GitHub server", e)
                    }
                }
            }
        }

        return postponedResult ?: gh_json("", "", info)
    }

    private fun getNoOAuthConnectionMessage(info: GitHubRepositoryInfo, project: SProject, request: HttpServletRequest): String {
        val createOAuthConnectionUrl = getUrlToCreateOAuthConnection(request, project, info.server)
        return "No OAuth connection found for the GitHub server '${info.server}' in the project '${StringUtil.formatTextForWeb(project.fullName)}'. Please <a href=\"$createOAuthConnectionUrl\">configure</a> OAuth connection."
    }

    private fun getUrlToCreateOAuthConnection(request: HttpServletRequest, project: SProject, server: String): String {
        val params = LinkedHashMap<String, Any>()
        params["projectId"] = project.externalId
        params["tab"] = "oauthConnections"
        val currentPageUrl = request.getParameter("currentPageUrl")
        if (currentPageUrl != null) {
            params["afterAddUrl"] = currentPageUrl
        }
        val hash = if (Util.isSameUrl(server, "github.com")) {
            "addDialog=GitHub"
        } else {
            "addDialog=GHE&gitHubUrl=${WebUtil.encode(server)}"
        }
        return url(request.contextPath + "/admin/editProject.html", params) + "#$hash"
    }

    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doAddWebHook(ghc: GitHubClientEx, info: GitHubRepositoryInfo, user: SUser, connection: OAuthConnectionDescriptor): JsonElement {
        val result = myWebHooksManager.doInstallWebHook(info, ghc, user, connection)
        return when (result.first) {
            HookAddOperationResult.AlreadyExists -> {
                gh_json(result.first.name, "Webhook for the GitHub repository '${info.id}' is already installed", info)
            }
            HookAddOperationResult.Created -> {
                gh_json(result.first.name, "Successfully installed webhook for the GitHub repository '${info.id}'", info)
            }
        }
    }

    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doInstallWebHook(ghc: GitHubClientEx, info: GitHubRepositoryInfo, user: SUser, connection: OAuthConnectionDescriptor): JsonElement {
        val result = myWebHooksManager.doInstallWebHook(info, ghc, user, connection)
        val url = result.second.getUIUrl()
        return when (result.first) {
            HookAddOperationResult.AlreadyExists -> {
                gh_json(result.first.name, "Webhook for the GitHub repository <a href=\"${info.getRepositoryUrl()}\">${info.id}</a> is already <a href=\"$url\" target=\"_blank\">installed</a>", info, false)
            }
            HookAddOperationResult.Created -> {
                gh_json(result.first.name, "Successfully installed <a href=\"$url\" target=\"_blank\">webhook</a> for the GitHub repository <a href=\"${info.getRepositoryUrl()}\">${info.id}</a>", info, false)
            }
        }
    }


    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doCheckWebHook(ghc: GitHubClientEx, info: GitHubRepositoryInfo): JsonElement {
        myWebHooksManager.doGetAllWebHooks(info, ghc)
        val hook = myWebHooksManager.getHook(info)
        return if (hook != null) {
            gh_json("Ok", "Updated webhook for the GitHub repository '${info.id}'", info)
        } else {
            gh_json("Ok", "No webhooks found for the GitHub repository '${info.id}'", info)
        }
    }

    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doTestWebHook(ghc: GitHubClientEx, info: GitHubRepositoryInfo): JsonElement {
        myWebHooksManager.doGetAllWebHooks(info, ghc)
        val hook = myWebHooksManager.getHook(info)
        @Suppress("IfNullToElvis")
        if (hook == null) {
            return gh_json("NotFound", "No webhooks found for the GitHub repository '${info.id}'", info)
        }
        // Ensure test message was sent
        myWebHooksManager.doTestWebHook(info, ghc, hook)
        return gh_json("Ok", "Asked server '${info.server}' to resend 'ping' event for repository '${info.getRepositoryId()}'", info)
    }

    @Throws(GitHubAccessException::class, RequestException::class, IOException::class)
    private fun doDeleteWebHook(ghc: GitHubClientEx, info: GitHubRepositoryInfo): JsonElement {
        return when (val result = myWebHooksManager.doDeleteWebHook(info, ghc)) {
            HookDeleteOperationResult.NeverExisted -> {
                gh_json(result.name, "Webhook for the GitHub repository '${info.id}' does not exist", info)
            }
            HookDeleteOperationResult.Removed -> {
                gh_json(result.name, "Successfully removed webhook for the GitHub repository '${info.id}'", info)
            }
        }
    }

    private fun getErrorResult(e: GitHubAccessException, connection: OAuthConnectionDescriptor, info: GitHubRepositoryInfo, token: OAuthToken): JsonElement? {
        when (e.type) {
            GitHubAccessException.Type.InvalidCredentials -> {
                LOG.warn("Removing incorrect (outdated) token (user:${token.oauthLogin}, scope:${token.scope})")
                myOAuthTokensStorage.removeToken(connection.tokenStorageId, token)
            }
            GitHubAccessException.Type.TokenScopeMismatch -> {
                LOG.warn("Token (user:${token.oauthLogin}, scope:${token.scope}) have not enough scope")
                // TODO: Update token scope
                myTokensHelper.markTokenIncorrect(token)
                return gh_json(e.type.name, e.message ?: "Token scope does not cover hooks management", info)
            }
            GitHubAccessException.Type.UserHaveNoAccess -> {
                return gh_json(e.type.name, "You don't have access to '${info.id}'", info)
            }
            GitHubAccessException.Type.NoAccess -> {
                return gh_json(e.type.name, "No access to repository '${info.id}'", info)
            }
            GitHubAccessException.Type.Moved -> {
                return gh_json(e.type.name, "Repository '${StringUtil.formatTextForWeb(info.id)}' was moved to <a href='//${e.message}'>${e.message}</a>", info, false)
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
        if (inProjectId == null || inProjectId.isBlank()) {
            return error_json("Required parameter 'projectId' is missing", HttpServletResponse.SC_BAD_REQUEST)
        }
        val project = getProject(inProjectId)

        if (!user.isPermissionGrantedForProject(project.projectId, Permission.EDIT_PROJECT)) {
            return error_json("User has no permission to edit project '${project.fullName}'", HttpServletResponse.SC_FORBIDDEN)
        }

        val recursive = PropertiesUtil.getBoolean(request.getParameter("recursive"))

        // If connection info specified, only webhooks from that server would be checked
        val connection: OAuthConnectionDescriptor? = getConnection(request, project)

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
                .mapNotNull { Util.getGitHubInfo(it) }
                .toHashSet()
                // Filter by connection (if any specified)
                .filter { connection == null || Util.isConnectionToServer(connection, it.server) }
                .groupBy { it.server }

        val toCheck = mapServerToInfos
                .mapValues {
                    it.value.filter { repoInfo -> Status.OK != getHookStatus(myWebHooksManager.getHook(repoInfo)).status }
                }.filterValues { it.isNotEmpty() }

        // For each repository return either check result or redirect request to show in UI.
        // Redirect would be in case of no connections of no tokens for server/repo/user.
        val arr = JsonArray()
        for ((server, infos) in toCheck) {
            val connections = if (connection != null) listOf(connection) else getConnections(server, project)
            if (connections.isEmpty()) {
                for (info in infos) {
                    val message = getNoOAuthConnectionMessage(info, project, request)
                    val obj = gh_json("NoOAuthConnections", message, info, false)
                    obj.addProperty("error", message)
                    obj.addProperty("user_action_required", true)
                    arr.add(obj)
                }
                continue
            }
            val connectionToTokensMap = myTokensHelper.getExistingTokens(project, connections, user)
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
                            elements.add(doCheckWebHook(ghc, info))
                        } catch(e: GitHubAccessException) {
                            val element = getErrorResult(e, connection, info, token)
                            if (element != null) elements.add(element)
                        } catch(e: RequestException) {
                            LOG.warnAndDebugDetails("Unexpected response from GitHub server", e)
                        } catch(e: UnknownHostException) {
                            // It seems host is (temporarily?) unavailable
                            elements.add(error_json("CannotAccessGitHub", HttpServletResponse.SC_SERVICE_UNAVAILABLE))
                        } catch(e: IOException) {
                            LOG.warnAndDebugDetails("IOException instead of response from GitHub server", e)
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
        val inProjectId = request.getParameter("projectId")
        if (inProjectId.isNullOrBlank()) return error_json("Required parameter 'projectId' is missing", HttpServletResponse.SC_BAD_REQUEST)
        val project = getProject(inProjectId)
        val user = SessionUser.getUser(request)
        val array = JsonArray()
        for (inRepository in inRepositories) {
            val info = Util.getGitHubInfo(inRepository)
            val json = getRepositoryInfo(info, myWebHooksManager)
            if (!user.isPermissionGrantedForProject(project.projectId, Permission.EDIT_PROJECT)) {
                json.add("actions", JsonArray())
            }
            array.add(json)
        }
        val result = JsonObject()
        result.add("result", array)
        return result
    }

    @Throws(MyRequestException::class)
    private fun getConnection(request: HttpServletRequest, project: SProject): OAuthConnectionDescriptor? {
        val inConnectionId = request.getParameter("connectionId").nullIfBlank() ?: return null
        val inConnectionProjectId = request.getParameter("connectionProjectId").nullIfBlank()
        val connectionOwnerProject = inConnectionProjectId?.let { getProject(it) } ?: project

        val connection = myOAuthConnectionsManager.findConnectionById(connectionOwnerProject, inConnectionId)
        @Suppress("IfNullToElvis")
        if (connection == null) {
            throw NotFoundException("There's no connection with id '$inConnectionId' found in the project ${connectionOwnerProject.fullName} and it parents")
        }
        return connection
    }

    private fun getProject(externalId: String?): SProject =
            myProjectManager.findProjectByExternalId(externalId) ?:
            throw NotFoundException("There's no project with external id $externalId")

    fun gh_json(result: String, message: String, info: GitHubRepositoryInfo, escape: Boolean = true): JsonObject {
        val message1 = if (escape) StringUtil.formatTextForWeb(message) else message
        return gh_json(result, message1, info, myWebHooksManager)
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
    return url(url, params.associateBy({ it.first }, { it.second.toString() }))
}

private fun url(url: String, params: Map<String, Any>): String {
    val sb = StringBuilder()
    sb.append(url)
    if (params.isNotEmpty()) sb.append('?')
    for ((key, value) in params) {
        sb.append(key).append('=').append(WebUtil.encode(value.toString())).append('&')
    }
    return sb.toString()
}