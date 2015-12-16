package org.jetbrains.teamcity.github.controllers

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonWriter
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientFactory
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
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
import org.springframework.web.servlet.view.RedirectView
import java.io.IOException
import java.io.OutputStreamWriter
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

public class CreateWebHookController(private val descriptor: PluginDescriptor, server: SBuildServer) : BaseController(server) {

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

    private val myResultJspPath = descriptor.getPluginResourcesPath("hook-created.jsp")


    public fun register(): Unit {
        WebControllerManager.registerController(PATH, this)
    }

    companion object {
        public val PATH = "/oauth/github/add-webhook.html"

        private val LOG = Logger.getInstance(CreateWebHookController::class.java.name)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val action = request.getParameter("action")
        if ("add" == action || action == null) {
            val element = doHandleAddAction(request, response, "add");
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            val writer = JsonWriter(OutputStreamWriter(response.outputStream))
            Gson().toJson(element, writer)
            writer.flush()
            return null
        }
        if ("add-popup" == action) {
            val element = doHandleAddAction(request, response, "add-popup");
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                val url = obj.getAsJsonPrimitive("redirect")?.asString
                if (url != null) {
                    return ModelAndView(RedirectView(url));
                }
            }
            val mav = ModelAndView(myResultJspPath)
            mav.model.put("json", Gson().toJson(element))
            return mav
        }
        if ("continue" == action) {
            val element = doHandleAddAction(request, response, "continue");
            val mav = ModelAndView(myResultJspPath)
            mav.model.put("json", Gson().toJson(element))
            return mav
        }
        return null
    }

    private fun doHandleAddAction(request: HttpServletRequest, response: HttpServletResponse, action: String): JsonElement {
        val user = SessionUser.getUser(request) ?: return error_json("Not authenticated", HttpServletResponse.SC_UNAUTHORIZED)

        // Vcs Root Info
        val id: Long
        val vcsRoot: SVcsRoot
        val vcsRootInstance: VcsRootInstance?

        val inType = request.getParameter("type")?.toLowerCase()
        val inId = request.getParameter("id")

        if (inType == null) {
            return error_json("Required parameter 'type' is missing", HttpServletResponse.SC_BAD_REQUEST)
        }
        if (inId == null) {
            return error_json("Required parameter 'id' is missing", HttpServletResponse.SC_BAD_REQUEST)
        }
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

        val info: VcsRootGitHubInfo = Util.getGitHubInfo(vcsRootInstance ?: vcsRoot) ?: return error_json("Not an GitHub VCS", 500)

        // TODO: check info.server to be github.com or known GitHub Enterprise server

        val inConnectionId = request.getParameter("connectionId")
        var connection: OAuthConnectionDescriptor?
        if (inConnectionId != null) {
            connection = OAuthConnectionsManager.getAvailableConnections(vcsRoot.project).firstOrNull{ it.id == inConnectionId }
            if (connection == null) {
                return error_json("There no connection with id '$inConnectionId' found in project ${vcsRoot.project.fullName}", HttpServletResponse.SC_NOT_FOUND);
            }
        } else {
            connection = null
        }

        LOG.info("Trying to create web hook for vcs (id:$id), github info is $info, user is ${user.describe(false)}, connection is ${connection?.id ?: "not specified"}")

        val connections: List<OAuthConnectionDescriptor>
        if (connection != null) {
            connections = listOf(connection)
        } else {
            connections = Util.findConnections(OAuthConnectionsManager, vcsRoot.project, info.server)
            if (connections.isEmpty()) {
                return error_json("No OAuth connectors found, setup them first", 500) //TODO: Add link, good UI.
            }
        }

        attempts@
        for (i in 0..2) {
            val tokens = TokensHelper.getExistingTokens(connections, user)
            if (tokens.isEmpty()) {
                // obtain access token
                if (connection == null) {
                    connection = connections.first() //FIXME
                }
                LOG.info("No token found will try to obtain one using connection ${connection.id}")

                return redirect_json(url(request.contextPath + "/oauth/github/accessToken.html",
                        "action" to "obtainToken",
                        "connectionId" to connection.id,
                        "projectId" to connection.project.projectId,
                        "scope" to "write:repo_hook",
                        "callbackUrl" to url(request.contextPath + PATH, "action" to "continue", "type" to inType, "id" to id, "connectionId" to connection.id
                        ))
                )
            }

            for (entry in tokens) {
                val ghc: GitHubClientEx = GitHubClientFactory.createGitHubClient(entry.key.parameters[GitHubConstants.GITHUB_URL_PARAM]!!)
                for (token in entry.value) {
                    LOG.info("Trying with token: ${token.oauthLogin}, connector is ${entry.key.id}")
                    ghc.setOAuth2Token(token.token)

                    try {
                        val result = manager.doRegisterWebHook(info, ghc)
                        val repoId = info.getRepositoryId().generateId()
                        when (result) {
                            WebHooksManager.HookAddOperationResult.InvalidCredentials -> {
                                LOG.warn("Removing incorrect (outdated) token (user:${token.oauthLogin}, scope:${token.scope})")
                                OAuthTokensStorage.removeToken(entry.key.id, token.token)
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

        return gh_json("", "", info)
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
        return url(url, params.toMap({ it -> it.first }, { it -> it.second.toString() }))
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