// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// UNIT — JSON round-trip only.

package com.tharmesh.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RecoveryRotationStageTest {

    @Test
    fun sealedFromStaged_roundTrips() {
        val sealed = IdentityRecovery.seal("priv", IdentityRecovery.generatePhrase(), java.security.SecureRandom())
        val json = org.json.JSONObject()
            .put("kdf", sealed.kdf)
            .put("n", sealed.iterations)
            .put("r", sealed.r)
            .put("p", sealed.p)
            .put("salt_b64", sealed.saltB64)
            .put("iv_b64", sealed.ivB64)
            .put("ciphertext_b64", sealed.ciphertextB64)
            .toString()
        val staged = RecoveryRotationStage.Staged("phrase", json, "acct", 1L)
        val parsed = RecoveryRotationStage.sealedFromStaged(staged)
        assertNotNull(parsed)
        assertEquals(sealed.ciphertextB64, parsed!!.ciphertextB64)
    }
}
