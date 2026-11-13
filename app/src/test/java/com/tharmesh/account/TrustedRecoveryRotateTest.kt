// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedRecoveryRotateTest {

    private class FakeHttp : HttpTransport {
        var lastPath: String? = null
        var lastBody: String? = null
        var code = 200
        var body = "0"
        override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
            lastPath = url
            lastBody = body
            return HttpTransport.Response(code, this.body)
        }
    }

    @Test
    fun upload_targets_recovery_rotate_edge() {
        val http = FakeHttp()
        http.code = 200
        http.body = """{"ok":true,"generation":1}"""
        val sealed = IdentityRecovery.seal("key", IdentityRecovery.generatePhrase(), java.security.SecureRandom())
        val result = TrustedRecoveryRotate.upload(
            http,
            "https://example.supabase.co",
            "anon",
            "access",
            sealed,
            0,
            "grant-test-id"
        )
        assertTrue(result is AuthCall.Ok)
        assertTrue(http.lastPath!!.endsWith("/functions/v1/recovery-rotate"))
        assertTrue(http.lastBody!!.contains("\"expected_generation\":0"))
    }

    @Test
    fun fetchGeneration_parses_rpc() {
        val http = FakeHttp()
        http.body = "2"
        val gen = TrustedRecoveryRotate.fetchGeneration(
            http,
            "https://example.supabase.co",
            "anon",
            "tok"
        )
        assertEquals(2, gen)
        assertTrue(http.lastPath!!.contains("tharmesh_recovery_generation"))
    }
}
