package org.jetbrains.teamcity.github.controllers

import org.testng.Assert
import org.testng.annotations.Test
import java.nio.charset.Charset

class HMacUtilTest {
    @Test
    fun testSimpleSignature() {
        doHMacTest(byteArrayOf('x'.toByte()), byteArrayOf('x'.toByte()), "sha1=8b6ff74fa7182a90ac20616816f7b8814a429f7c")
        doHMacTest(byteArrayOf('a'.toByte()), byteArrayOf('b'.toByte()), "sha1=6657855686823986c874362731139752014cb60b")
        doHMacTest(byteArrayOf('c'.toByte()), byteArrayOf('d'.toByte()), "sha1=02c036866544771126771380f2184d40148c4d3c")
    }

    private fun doHMacTest(key: ByteArray, message: ByteArray, expected: String) {
        val signature = HMacUtil.calculateHMac(message, key)
        println("Signature for key '${key.toString(Charset.defaultCharset())}', message '${message.toString(Charset.defaultCharset())}' is $signature")
        Assert.assertEquals(signature, expected);
    }
}