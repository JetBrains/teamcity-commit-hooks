package org.jetbrains.teamcity.github

import org.assertj.core.api.BDDAssertions.then
import org.testng.annotations.Test
import java.util.*

class AuthDataStorageTest {

    @Test
    fun testAuthDataSerialization() {
        val connInfo = AuthDataStorage.ConnectionInfo("CONN_ID", "CONN_PID")
        doAuthDataSerializationTest(AuthDataStorage.AuthData(1000, "public", "secret", GitHubRepositoryInfo("server", "owner", "repo"), connInfo))
        doAuthDataSerializationTest(AuthDataStorage.AuthData(1000, UUID.randomUUID().toString(), UUID.randomUUID().toString(), GitHubRepositoryInfo("server", "owner", "repo"), connInfo))
    }

    private fun doAuthDataSerializationTest(first: AuthDataStorage.AuthData) {
        val second = AuthDataStorage.AuthData.fromJson(first.toJson())
        then(second).isNotNull()
        second!!
        then(second.userId).isEqualTo(first.userId)
        then(second.secret).isEqualTo(first.secret)
        then(second.public).isEqualTo(first.public)
        then(second.repository).isEqualTo(first.repository)
        then(second.connection).isEqualTo(first.connection)
        then(second.toJson()).isEqualTo(first.toJson())
        then(second.hashCode()).isEqualTo(first.hashCode())
        then(second).isEqualTo(first)
    }

}