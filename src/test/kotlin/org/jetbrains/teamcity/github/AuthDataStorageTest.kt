package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.oauth.OAuthToken
import org.assertj.core.api.BDDAssertions.then
import org.testng.annotations.Test
import java.util.*

class AuthDataStorageTest {

    @Test
    fun testAuthDataSerialization() {
        doAuthDataSerializationTest(AuthDataStorage.AuthData(1000, "secret", "public", GitHubRepositoryInfo("server", "owner", "repo")))
        doAuthDataSerializationTest(AuthDataStorage.AuthData(1000, UUID.randomUUID().toString(), UUID.randomUUID().toString(), GitHubRepositoryInfo("server", "owner", "repo")))

        val token = OAuthToken("__TOKEN__", "__SCOPE__", "__LOGIN__", 9000, 1000)
        val connInfo = AuthDataStorage.ConnectionInfo("CONN_ID", "CONN_PID")
        doAuthDataSerializationTest(AuthDataStorage.AuthData(1000, "secret", "public", GitHubRepositoryInfo("server", "owner", "repo"), connInfo, token))
        doAuthDataSerializationTest(AuthDataStorage.AuthData(1000, UUID.randomUUID().toString(), UUID.randomUUID().toString(), GitHubRepositoryInfo("server", "owner", "repo"), connInfo, token))
    }

    private fun doAuthDataSerializationTest(first: AuthDataStorage.AuthData) {
        val second = AuthDataStorage.AuthData.fromJson(first.toJson())
        then(second).isNotNull()
        second!!
        then(second.userId).isEqualTo(first.userId)
        then(second.secret).isEqualTo(first.secret)
        then(second.public).isEqualTo(first.public)
        then(second.repository).isEqualTo(first.repository)
        then(second.token).isEqualTo(first.token)
        then(second.connection).isEqualTo(first.connection)
        then(second.toJson()).isEqualTo(first.toJson())
        then(second.hashCode()).isEqualTo(first.hashCode())
        then(second).isEqualTo(first)
    }

}