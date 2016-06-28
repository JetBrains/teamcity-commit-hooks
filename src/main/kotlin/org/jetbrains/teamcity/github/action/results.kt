package org.jetbrains.teamcity.github.action

enum class HooksGetOperationResult {
    Ok
}

enum class HookTestOperationResult {
    NotFound,
    Ok
}

enum class HookAddOperationResult {
    AlreadyExists,
    Created,
}

enum class HookDeleteOperationResult {
    Removed,
    NeverExisted,
}