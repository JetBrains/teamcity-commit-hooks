

package org.jetbrains.teamcity.github.controllers

import org.apache.commons.codec.binary.Hex
import org.jetbrains.teamcity.github.Util
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HMacUtil {
    companion object {
        private val LOG = Util.getLogger(HMacUtil::class.java)
        const val HMAC_Algorithm = "HmacSHA1"

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