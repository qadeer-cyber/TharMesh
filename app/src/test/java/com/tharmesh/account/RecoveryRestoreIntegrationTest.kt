// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** Category 2: recovery restore / rotate paths (local crypto + HTTP mocks). */
class RecoveryRestoreIntegrationTest {

    private class FakeHttp(var code: Int = 200, var body: String = "0") : HttpTransport {
        var posts = 0
        override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response {
            posts++
            return HttpTransport.Response(code, this.body)
        }
    }

  private fun mesh() = CryptoIdentity.generate()

    @Test fun correctPhrase_opensSealedBlob() {
        val phrase = IdentityRecovery.generatePhrase()
        val sealed = IdentityRecovery.seal("account-private", phrase, SecureRandom())
        val opened = IdentityRecovery.open(sealed, phrase)
        assertNotNull(opened)
    }

    @Test fun wrongPhrase_failsClosed() {
        val phrase = IdentityRecovery.generatePhrase()
        val sealed = IdentityRecovery.seal("account-private", phrase, SecureRandom())
        assertNull(IdentityRecovery.open(sealed, IdentityRecovery.generatePhrase()))
    }

    @Test fun damagedCiphertext_failsClosed() {
        val phrase = IdentityRecovery.generatePhrase()
        val sealed = IdentityRecovery.seal("account-private", phrase, SecureRandom())
        assertNull(IdentityRecovery.open(sealed.copy(ciphertextB64 = "YQ=="), phrase))
    }

    @Test fun missingBlob_noSealedOnRecord() {
        val record = InstallRecord(recoverySealed = null)
        val phrase = IdentityRecovery.generatePhrase()
        val opened = record.recoverySealed?.let { IdentityRecovery.open(it, phrase) }
        assertNull(opened)
    }

    @Test fun staleGeneration_uploadConflict() {
        val http = FakeHttp(409, """{"error":"stale"}""")
        val sealed = IdentityRecovery.seal("key", IdentityRecovery.generatePhrase(), SecureRandom())
        val result = TrustedRecoveryRotate.upload(http, "https://x", "anon", "tok", sealed, 3, "grant", "req-1")
        assertTrue(result is AuthCall.Failed)
        assertEquals("generation_conflict", (result as AuthCall.Failed).reason)
    }

    @Test fun competingRotations_differentPhrasesBothOpen() {
        val mesh = mesh()
        val material = MeshMaterial.from(mesh, "user")
        val payload = RecoveryPayload.encode(
            material,
            AccountMaterial(material.publicKeyBase64, material.privateKeyBase64, 1, IdentityOrigin.ORIGINAL)
        )
        val a = IdentityRecovery.seal(payload, IdentityRecovery.generatePhrase(), SecureRandom())
        val b = IdentityRecovery.seal(payload, IdentityRecovery.generatePhrase(), SecureRandom())
        assertNotEquals(a.saltB64, b.saltB64)
    }

    @Test fun expiredAuthorization_freshAuthRequired() {
        val http = FakeHttp(403, "{}")
        val sealed = IdentityRecovery.seal("k", IdentityRecovery.generatePhrase(), SecureRandom())
        val result = TrustedRecoveryRotate.upload(http, "https://x", "anon", "tok", sealed, 0, "g", "req-2")
        assertEquals("fresh_auth_required", (result as AuthCall.Failed).reason)
    }

    @Test fun offlineLimitation_unreachablePreservesMesh() {
        val mesh = mesh()
        val record = InstallRecord(mesh = MeshMaterial.from(mesh, "user"), localAuth = LocalAuth.OFFLINE_AUTHORIZED)
        val after = IdentityLifecycle.noteServerUnreachable(record)
        assertEquals(CloudCredential.UNREACHABLE, after.cloud)
        assertSame(record.mesh?.publicKeyBase64, after.mesh?.publicKeyBase64)
    }

    @Test fun processDeathStaging_prefsSurviveUntilClear() {
        val sealed = IdentityRecovery.seal("priv", IdentityRecovery.generatePhrase(), SecureRandom())
        val stagedJson = org.json.JSONObject()
            .put("ciphertext_b64", sealed.ciphertextB64)
            .put("request_id", "req-death-1")
            .toString()
        assertTrue(stagedJson.contains("req-death-1"))
    }

    @Test fun commitLostResponse_idempotentRequestId() {
        val http = FakeHttp(200, """{"ok":true,"generation":2}""")
        val sealed = IdentityRecovery.seal("k", IdentityRecovery.generatePhrase(), SecureRandom())
        val req = "req-idempotent-77"
        val first = TrustedRecoveryRotate.upload(http, "https://x", "anon", "tok", sealed, 1, "g", req)
        val second = TrustedRecoveryRotate.upload(http, "https://x", "anon", "tok", sealed, 1, "g", req)
        assertTrue(first is AuthCall.RecoveryUploaded)
        assertTrue(second is AuthCall.RecoveryUploaded)
        assertEquals(2, http.posts)
    }

    @Test fun restoreOntoNewPhone_freshInstallState() {
        val mesh = mesh()
        val material = MeshMaterial.from(mesh, "user")
        val phrase = IdentityRecovery.generatePhrase()
        val sealed = IdentityRecovery.seal(
            RecoveryPayload.encode(
                material,
                AccountMaterial(material.publicKeyBase64, material.privateKeyBase64, 1, IdentityOrigin.ORIGINAL)
            ),
            phrase,
            SecureRandom()
        )
        val fresh = InstallRecord()
        val result = IdentityLifecycle.restore(fresh, phrase, sealed)
        assertTrue(result is IdentityLifecycle.RestoreResult.Restored)
        val restored = (result as IdentityLifecycle.RestoreResult.Restored).record
        assertEquals(mesh.publicKeyBase64, restored.mesh?.publicKeyBase64)
        assertEquals(IdentityOrigin.RESTORED, restored.account?.origin)
    }
}
