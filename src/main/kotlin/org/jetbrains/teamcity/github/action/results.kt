

package org.jetbrains.teamcity.github.action

enum class HookAddOperationResult {
    AlreadyExists,
    Created,
}

enum class HookDeleteOperationResult {
    Removed,
    NeverExisted,
}