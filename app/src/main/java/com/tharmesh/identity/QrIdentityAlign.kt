// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.identity

/**
 * Decides which public key a QR may show.
 *
 * The mesh signing key in UserPrefs is the key bundles are signed with.
 * [IdentityStore] used to mint a second key. This keeps that older key
 * (so it is not deleted) and does not let it replace the signing key.
 * When UserPrefs has no key yet, the stored key is imported instead of
 * generating a new one.
 */
object QrIdentityAlign {

    data class KeyMaterial(val publicKeyBase64: String, val privateKeyBase64: String)

    data class Decision(
        /** Key the QR must display. Null when neither store has a usable key. */
        val present: KeyMaterial?,
        /** Copy into UserPrefs. Set only when UserPrefs has no key and the store does. */
        val importIntoMesh: KeyMaterial?,
        /** Previous IdentityStore key, retained when it differs from the mesh key. */
        val archiveStore: KeyMaterial?,
        val meshPrivateUnchanged: Boolean
    )

    fun decide(mesh: KeyMaterial?, store: KeyMaterial?): Decision {
        val meshKey = mesh?.takeIf { usable(it) }
        val storeKey = store?.takeIf { usable(it) }
        if (meshKey != null) {
            val diverge = storeKey != null && !sameKey(meshKey.publicKeyBase64, storeKey.publicKeyBase64)
            return Decision(
                present = meshKey,
                importIntoMesh = null,
                archiveStore = if (diverge) storeKey else null,
                meshPrivateUnchanged = true
            )
        }
        if (storeKey != null) {
            val canonical = canonicalize(storeKey)
            return Decision(
                present = canonical,
                importIntoMesh = canonical,
                archiveStore = null,
                meshPrivateUnchanged = true
            )
        }
        return Decision(null, null, null, meshPrivateUnchanged = true)
    }

    fun sameKey(a: String, b: String): Boolean {
        val left = Base64Url.decode(a) ?: return false
        val right = Base64Url.decode(b) ?: return false
        return left.contentEquals(right)
    }

    private fun usable(key: KeyMaterial): Boolean {
        return CryptoIdentity.fromBase64(key.privateKeyBase64, key.publicKeyBase64) != null
    }

    private fun canonicalize(key: KeyMaterial): KeyMaterial {
        val identity = CryptoIdentity.fromBase64(key.privateKeyBase64, key.publicKeyBase64)
            ?: return key
        return KeyMaterial(identity.publicKeyBase64, identity.privateKeyBase64)
    }
}
