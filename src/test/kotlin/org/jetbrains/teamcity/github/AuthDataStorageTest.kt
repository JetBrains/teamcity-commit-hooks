/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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