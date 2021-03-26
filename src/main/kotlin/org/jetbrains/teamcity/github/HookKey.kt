package org.jetbrains.teamcity.github

import java.util.*

data class HookKey(val server: String, val owner: String, val name: String, val id: Long) {
    companion object {
        fun fromString(serialized: String): HookKey {
            val split = serialized.split('/')
            if (split.size < 4) throw IllegalArgumentException("Not an proper key: \"$serialized\"")
            val id = split[split.lastIndex].toLong()
            val name = split[split.lastIndex - 1]
            val owner = split[split.lastIndex - 2]
            val server = split.dropLast(3).joinToString("/")
            return HookKey(server, owner, name, id)
        }

        fun fromHookUrl(hookUrl: String): HookKey {
            val split = ArrayDeque(hookUrl.split('/'))
            assert(split.size >= 8)
            val id = split.pollLast().toLong()
            split.pollLast() // "hooks"
            val name = split.pollLast()
            val owner = split.pollLast()
            split.pollLast() // "repos"
            val serverOfV3 = split.pollLast()
            val server = if (serverOfV3 == "api.github.com")  "github.com" else {
                split.pollLast()
                split.pollLast()
            }
            return HookKey(server, owner, name, id)
        }
    }

    override fun toString(): String {
        return "$server/$owner/$name/$id"
    }

    fun toMapKey(): RepoKey {
        return RepoKey(server.trimEnd('/'), owner, name)
    }
}