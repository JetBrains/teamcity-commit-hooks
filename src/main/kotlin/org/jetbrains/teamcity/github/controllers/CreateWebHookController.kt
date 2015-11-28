package org.jetbrains.teamcity.github.controllers

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.controllers.SimpleView
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthToken
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientFactory
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.vcs.SVcsRoot
import jetbrains.buildServer.vcs.VcsManager
import jetbrains.buildServer.vcs.VcsRootInstance
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.SessionUser
import org.eclipse.egit.github.core.client.RequestException
import org.intellij.lang.annotations.MagicConstant
import org.jetbrains.teamcity.github.Util
import org.jetbrains.teamcity.github.VcsRootGitHubInfo
import org.jetbrains.teamcity.github.WebHooksManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView
import java.io.IOException
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
    lateinit var WebHooksManager: WebHooksManager

    private val myResultJspPath = descriptor.getPluginResourcesPath("hook-created.jsp")


    public fun register(): Unit {
        WebControllerManager.registerController(PATH, this)
    }

    companion object {
        public val PATH = "/oauth/github/add-webhook.html"

        private val LOG = Logger.getInstance(CreateWebHookController::class.java.name)
    }


    // TODO: Return JSON?
    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val user = SessionUser.getUser(request) ?: return errorView("Not authenticated", HttpServletResponse.SC_UNAUTHORIZED, response)

        // Vcs Root Info
        val id: Long
        val vcsRoot: SVcsRoot
        val vcsRootInstance: VcsRootInstance?

        val inType = request.getParameter("type")?.toLowerCase()
        val inId = request.getParameter("id")

        if (inType == null) {
            return errorView("Required parameter 'type' is missing", HttpServletResponse.SC_BAD_REQUEST, response)
        }
        if (inId == null) {
            return errorView("Required parameter 'id' is missing", HttpServletResponse.SC_BAD_REQUEST, response)
        }
        try {
            id = inId.toLong()
        } catch(e: NumberFormatException) {
            return errorView("Incorrect format of parameter 'id', integer number expected", HttpServletResponse.SC_BAD_REQUEST, response)
        }
        if ("root" == inType) {
            vcsRootInstance = null
            vcsRoot = VcsManager.findRootById(id) ?: return errorView("VcsRoot with id '$id' not found", HttpServletResponse.SC_NOT_FOUND, response)
        } else if ("instance" == inType) {
            vcsRootInstance = VcsManager.findRootInstanceById(id) ?: return errorView("VcsRootInstance with id '$id' not found", HttpServletResponse.SC_NOT_FOUND, response)
            vcsRoot = vcsRootInstance.parent
        } else {
            return errorView("Parameter 'type' have unknown value", HttpServletResponse.SC_BAD_REQUEST, response)
        }

        val info: VcsRootGitHubInfo = Util.getGitHubInfo(vcsRootInstance ?: vcsRoot) ?: return simpleView("Not an GitHub VCS")

        // TODO: check info.server to be github.com or known GitHub Enterprise server

        val inConnectionId = request.getParameter("connectionId")
        var connection: OAuthConnectionDescriptor?
        if (inConnectionId != null) {
            connection = OAuthConnectionsManager.findConnectionById(vcsRoot.project, inConnectionId)
            if (connection == null) {
                return errorView("There no connection with id '$inConnectionId' found in project ${vcsRoot.project.fullName}", HttpServletResponse.SC_NOT_FOUND, response);
            }
        } else {
            connection = null
        }

        LOG.info("Trying to create web hook for vcs (id:$id), github info is $info, user is ${user.describe(false)}, connection is ${connection?.id ?: "not specified"}")

        var isSuccess: Boolean = false

        val connections: List<OAuthConnectionDescriptor>
        if (connection != null) {
            connections = listOf(connection)
        } else {
            connections = Util.findConnections(OAuthConnectionsManager, info, vcsRoot.project)
            if (connections.isEmpty()) {
                return simpleView("No OAuth connectors found, setup them first") //TODO: Add link, good UI.
            }
        }

        attempts@
        for (i in 0..2) {
            val tokens = findExistingTokens(request, info, connections)
            if (tokens.isEmpty()) {
                // obtain access token
                if (connection == null) {
                    connection = connections.first() //FIXME
                }
                LOG.info("No token found will try to obtain one using connection ${connection.id}")

                val returnPath = request.contextPath + PATH + "?type=$inType&id=$id&connectionId=${connection.id}"
                val scope = "public_repo,repo,repo:status,write:repo_hook"

                val clazz = Class.forName("jetbrains.buildServer.serverSide.oauth.github.AccessTokenRequestBean")
                val method = clazz.declaredMethods.first { it.name == "create" }
                method.invoke(null, request, connection, returnPath)
                return ModelAndView(RedirectView(request.contextPath + "/oauth/github/accessToken.html?action=obtainToken&connectionId=${connection.id}&scope=$scope"))

                // TODO: Get rid ob reflection above (requires dependency on oauth-integration-web.jar)
                //AccessTokenRequestBean.create(request, connection, returnPath)
                //return ModelAndView(RedirectView(request.contextPath + GitHubAccessTokenController.PATH + "?action=obtainToken&connectionId=" + connectionId + "&scope=" + "public_repo,repo,repo:status,write:repo_hook"))
            }

            for (entry in tokens) {
                val ghc = GitHubClientFactory.createGitHubClient(entry.key.parameters[GitHubConstants.GITHUB_URL_PARAM]!!)
                for (token in entry.value) {
                    LOG.info("Trying with token: ${token.oauthLogin}, connector is ${entry.key.id}")
                    ghc.setOAuth2Token(token.token)

                    try {
                        WebHooksManager.doRegisterWebHook(info, ghc)
                        isSuccess = true
                        break@attempts
                    } catch(e: RequestException) {
                        if (e.status == 401) {
                            // token expired?
                            OAuthTokensStorage.removeToken(inConnectionId, token.token)
                        }
                    } catch(e: IOException) {
                    }
                }
            }
        }

        val modelAndView = ModelAndView(myResultJspPath)
        modelAndView.model.put("info", info)
        modelAndView.model.put("isSuccessful", isSuccess)
        return modelAndView
    }

    private fun isSuitableToken(info: VcsRootGitHubInfo, it: OAuthToken): Boolean {
        return (it.oauthLogin == info.owner && it.scope.contains("write:repo_hook")) || (it.scope.contains("write:repo_hook") && it.scope.contains("admin:org_hook"))
    }


    private fun findExistingTokens(request: HttpServletRequest, info: VcsRootGitHubInfo, descriptors: List<OAuthConnectionDescriptor>): Map<OAuthConnectionDescriptor, List<OAuthToken>> {
        val user = SessionUser.getUser(request)!!

        LOG.info("Found connectors: ${descriptors.map { it.id }} for server ${info.server}")
        return descriptors.map {
            it to OAuthTokensStorage.getUserTokens(it.id, user).filter { isSuitableToken(info, it) }
        }.filter { it.second.isNotEmpty() }.toMap()
    }


    @Throws()
    protected fun errorView(message: String, @MagicConstant(flagsFromClass = HttpServletResponse::class) code: Int, response: HttpServletResponse): ModelAndView {
        response.status = code
        return SimpleView.createTextView(message)
    }
}