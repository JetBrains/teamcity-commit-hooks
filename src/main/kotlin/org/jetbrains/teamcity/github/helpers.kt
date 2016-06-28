package org.jetbrains.teamcity.github

import org.eclipse.egit.github.core.RepositoryHook


val RepositoryHook.callbackUrl: String?
    get() = this.config["url"]