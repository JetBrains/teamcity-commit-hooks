package org.jetbrains.teamcity.impl

import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.log.Loggers
import jetbrains.buildServer.serverSide.SecurityContextEx
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.users.UserModel
import jetbrains.buildServer.users.UserModelEx
import jetbrains.buildServer.web.util.SessionUser
import jetbrains.spring.web.UrlMapping
import org.jetbrains.teamcity.impl.fakes.FakeHttpRequestsFactory
import org.jetbrains.teamcity.impl.fakes.FakeHttpServletResponse
import java.util.Collections.emptyMap

class RestApiFacade(private val myUrlMapping: UrlMapping,
                    private val myFakeHttpRequestsFactory: FakeHttpRequestsFactory,
                    userModel: UserModelEx,
                    private val mySecurityContext: SecurityContextEx) {
    private val myUserModel: UserModel

    private val myRestController: BaseController? by lazy {
        val handler = myUrlMapping.handlerMap["/app/rest/**"]
        if (handler == null) {
            Loggers.SERVER.error("Unable to initialize internal Rest Api Facade: no controllers are found for the '/app/rest/**' path. UI will not refresh.")
            return@lazy null
        }

        if (handler !is BaseController) {
            Loggers.SERVER.error("Unable to initialize internal Rest Api Facade: unexpected handler was found for the '/app/rest/**' path: $handler. UI will not refresh.")
            return@lazy null
        }

        return@lazy handler as BaseController?
    }

    init {
        myUserModel = userModel
    }

    /**
     * Execute the request under a super user.
     */
    @Throws(InternalRestApiCallException::class)
    fun getJson(path: String, query: String): String? {
        return getJson(myUserModel.superUser, path, query, emptyMap())
    }

    /**
     * Execute the request under the specified user.
     */
    @Throws(InternalRestApiCallException::class)
    fun getJson(user: SUser, path: String, query: String, requestAttrs: Map<String, Any>): String? {
        return get(user, "application/json", path, query, requestAttrs)
    }

    /**
     * Execute the request under the specified user.
     */
    @Throws(InternalRestApiCallException::class)
    fun get(user: SUser, contentType: String, path: String, query: String, requestAttrs: Map<String, Any>): String? {
        return request("GET", user, contentType, path, query, requestAttrs)
    }

    /**
     * Execute the request under the specified user.
     */
    @Throws(InternalRestApiCallException::class)
    fun request(method: String, user: SUser, contentType: String, path: String, query: String, requestAttrs: Map<String, Any>): String? {
        try {
            return mySecurityContext.runAs<String>(user) {
                val controller = myRestController ?: return@runAs null

                val request = myFakeHttpRequestsFactory.get(path, query)
                request.setHeader("Accept", contentType)
                request.method = method

                val response = FakeHttpServletResponse()
                request.setAttribute("INTERNAL_REQUEST", true)
                SessionUser.setUser(request, user)

                requestAttrs.forEach(request::setAttribute)

                try {
                    controller.handleRequestInternal(request, response)
                } catch (e: Exception) {
                    throw InternalRestApiCallException(400, e)
                }

                if (response.status >= 400) {
                    Loggers.SERVER.warn("Unexpected response while executing internal Rest API request:" + path + "?" + query + ", response: " + response.returnedContent)
                    throw InternalRestApiCallException(response.status, response.returnedContent)
                }

                response.returnedContent
            }
        } catch (e: InternalRestApiCallException) {
            throw e
        } catch (throwable: Throwable) {
            throw InternalRestApiCallException(400, throwable)
        }
    }

    class InternalRestApiCallException : Exception {
        val statusCode: Int

        constructor(statusCode: Int, message: String) : super(message) {
            this.statusCode = statusCode
        }

        constructor(statusCode: Int, message: String, cause: Throwable) : super(message, cause) {
            this.statusCode = statusCode
        }

        constructor(statusCode: Int, throwable: Throwable) : super(throwable) {
            this.statusCode = statusCode
        }
    }
}

