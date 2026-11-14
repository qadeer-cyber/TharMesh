// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity
import java.security.SecureRandom

/**
 * Builds a new recovery seal for the current mesh private key. Upload replaces
 * the server row; copies of the old ciphertext anywhere else stay decryptable
 * with the old secret.
 */
object RecoveryRotation {

    data class Outcome(
        val phrase: String,
        val sealed: IdentityRecovery.SealedIdentity,
        val meshPublicKey: String
    )

    fun rotate(meshPrivateKeyBase64: String, meshPublicKeyBase64: String, random: SecureRandom = SecureRandom()): Outcome {
        val phrase = IdentityRecovery.generatePhrase(random)
        val sealed = IdentityRecovery.seal(meshPrivateKeyBase64, phrase, random)
        val opened = IdentityRecovery.open(sealed, phrase)
        if (opened != meshPrivateKeyBase64) {
            throw IllegalStateException("recovery_seal_mismatch")
        }
        return Outcome(phrase, sealed, meshPublicKeyBase64)
    }

    fun requiresActiveMesh(identity: CryptoIdentity?): Boolean = identity != null
}
