package org.jetbrains.teamcity.github

import org.assertj.core.api.BDDAssertions.then
import org.testng.annotations.Test
import java.util.*

class AuthDataStorageTest {

    @Test
    fun testAuthDataSerialization() {
        doAuthDataSerializationTest(AuthDataStorage.AuthData(1000, "secret", "public"))
        doAuthDataSerializationTest(AuthDataStorage.AuthData(1000, UUID.randomUUID().toString(), UUID.randomUUID().toString()))
    }

    private fun doAuthDataSerializationTest(first: AuthDataStorage.AuthData) {
        val second = AuthDataStorage.AuthData.fromJson(AuthDataStorage.AuthData.toJson(first))
        then(second).isNotNull()
        second!!
        then(second.userId).isEqualTo(first.userId)
        then(second.secret).isEqualTo(first.secret)
        then(second.public).isEqualTo(first.public)
        then(AuthDataStorage.AuthData.toJson(second)).isEqualTo(AuthDataStorage.AuthData.toJson(first))
        then(second.hashCode()).isEqualTo(first.hashCode())
        then(second).isEqualTo(first)
    }

}