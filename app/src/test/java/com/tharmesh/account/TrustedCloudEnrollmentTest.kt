// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class TrustedCloudEnrollmentTest {

    @Test
    fun deviceApproveMessage_matchesPostgresSeparator() {
        val msg = TrustedCloudEnrollment.deviceApproveMessage(
            "f1000000-0000-4000-8000-0000000000a1",
            "devPub",
            "nonce123",
            1_700_000_000L
        )
        val expected = listOf(
            "TM-DEVICE-APPROVE-v1",
            "f1000000-0000-4000-8000-0000000000a1",
            "devPub",
            "nonce123",
            "1700000000"
        ).joinToString("\u001F").toByteArray(StandardCharsets.UTF_8)
        assertArrayEquals(expected, msg)
    }

    @Test
    fun enrollmentBegin_postsRpcBody() {
        val device = CryptoIdentity.generate()
        val nonce = ByteArray(8)
        val req = DeviceEnrollment.createRequest(
            "acct",
            "mesh",
            SoftwareDeviceSigner(device),
            "dev-1",
            nonce,
            expiresAtEpochSec = 1_700_000_000L
        )
        val http = RecordingHttp()
        TrustedCloudEnrollment.enrollmentBegin(
            http,
            "https://example.supabase.co",
            "anon",
            "userjwt",
            req,
            req.expiresAtEpochSec
        )
        assertTrue(http.lastPath!!.endsWith("/functions/v1/device-enrollment"))
        assertTrue(http.lastBody!!.contains("operation"))
        assertTrue(http.lastBody!!.contains("begin"))
    }

    private class RecordingHttp : HttpTransport {
        var lastPath: String? = null
        var lastBody: String? = null
        override fun post(url: String, headers: Map<String, String>, body: String) =
            HttpTransport.Response(204, "").also {
                lastPath = url
                lastBody = body
            }

    }
}
