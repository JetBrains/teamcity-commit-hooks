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
        const val PATH = "/app/hooks/check"
    }

    fun register() {
        if (!jetbrains.buildServer.DevelopmentMode.isEnabled) return
        setSupportedMethods(WebContentGenerator.METHOD_POST, WebContentGenerator.METHOD_GET)
        WebControllerManager.registerController(PATH, this)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        WebhookPeriodicalChecker.doCheck()
        return null
    }

}
