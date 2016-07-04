package org.jetbrains.teamcity.github.controllers

import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.jetbrains.teamcity.github.WebhookPeriodicalChecker
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.support.WebContentGenerator
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class ForceWebhooksCheckController(private val WebControllerManager: WebControllerManager,
                                   private val WebhookPeriodicalChecker: WebhookPeriodicalChecker) : BaseController() {
    companion object {
        val PATH = "/app/hooks/check"
    }

    fun register(): Unit {
        setSupportedMethods(WebContentGenerator.METHOD_POST, WebContentGenerator.METHOD_GET)
        WebControllerManager.registerController(PATH, this)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        WebhookPeriodicalChecker.doCheck()
        return null
    }

}
