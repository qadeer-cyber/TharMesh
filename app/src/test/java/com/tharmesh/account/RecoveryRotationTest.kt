// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// UNIT — local crypto only.

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryRotationTest {

    @Test
    fun rotate_producesNewPhraseAndOpensWithIt() {
        val mesh = CryptoIdentity.generate()
        val first = RecoveryRotation.rotate(mesh.privateKeyBase64, mesh.publicKeyBase64)
        val second = RecoveryRotation.rotate(mesh.privateKeyBase64, mesh.publicKeyBase64)
        assertNotEquals(first.phrase, second.phrase)
        val opened = IdentityRecovery.open(first.sealed, first.phrase)
        assertEquals(mesh.privateKeyBase64, opened)
        assertEquals(IdentityRecovery.KDF_SCRYPT, first.sealed.kdf)
        assertTrue(first.sealed.iterations >= IdentityRecovery.SCRYPT_N)
    }
}
