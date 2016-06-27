package org.jetbrains.teamcity.github


class GitHubAccessException(val type: Type, message: String? = null) : Exception(message) {
    enum class Type {
        InvalidCredentials,
        TokenScopeMismatch, // If token is valid but does not have required scope
        NoAccess,
        UserHaveNoAccess,
    }
}

