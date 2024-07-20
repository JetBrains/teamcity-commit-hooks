package org.jetbrains.teamcity.github.util

import com.google.gson.annotations.SerializedName

data class GitHubRepository(@SerializedName("clone_url") val cloneUrl: String?, @SerializedName("ssh_url")  val sshUrl: String?, @SerializedName("git_url") val gitUrl: String?)

