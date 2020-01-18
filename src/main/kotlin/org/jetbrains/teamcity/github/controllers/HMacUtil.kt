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

package org.jetbrains.teamcity.github.controllers

import org.apache.commons.codec.binary.Hex
import org.jetbrains.teamcity.github.Util
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HMacUtil {
    companion object {
        private val LOG = Util.getLogger(HMacUtil::class.java)
        val HMAC_Algorithm = "HmacSHA1"

        fun checkHMac(message: ByteArray, key: ByteArray, expected: String): Boolean {
            val hmac = calculateHMac(message, key)
            if (hmac != expected) {
                LOG.warn("Digest mismatch. From header: '$expected' Content digest-hmac: $hmac")
            }
            return hmac == expected
        }

        fun calculateHMac(message: ByteArray, key: ByteArray): String {
            val mac = Mac.getInstance(HMAC_Algorithm)
            mac.init(SecretKeySpec(key, HMAC_Algorithm))
            mac.update(message)
            val digest = mac.doFinal()
            return "sha1=" + String(Hex.encodeHex(digest))
        }
    }
}
