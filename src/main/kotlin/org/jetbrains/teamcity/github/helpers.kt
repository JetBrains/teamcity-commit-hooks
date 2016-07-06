package org.jetbrains.teamcity.github

import jetbrains.buildServer.util.StringUtil
import org.eclipse.egit.github.core.RepositoryHook


val RepositoryHook.callbackUrl: String?
    get() = this.config["url"]


val Int.s: String get() = if (this > 1) "s" else ""
fun Int.pluralize(text: String): String = StringUtil.pluralize(text, this)