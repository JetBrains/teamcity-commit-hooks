package org.jetbrains.teamcity.github

import jetbrains.buildServer.util.StringUtil
import org.eclipse.egit.github.core.RepositoryHook
import org.jetbrains.teamcity.github.controllers.Status


val RepositoryHook.callbackUrl: String?
    get() = this.config["url"]


val Int.s: String get() = if (this > 1) "s" else ""
fun Int.pluralize(text: String): String = StringUtil.pluralize(text, this)


fun RepositoryHook.getStatus(): Status {
    if (this.lastResponse != null) {
        if (this.lastResponse.code in 200..299) {
            if (!this.isActive) return Status.DISABLED
            return Status.OK
        } else {
            return Status.PAYLOAD_DELIVERY_FAILED
        }
    } else {
        if (!this.isActive) return Status.DISABLED
        return Status.WAITING_FOR_SERVER_RESPONSE
    }
}
