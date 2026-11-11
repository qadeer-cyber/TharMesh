// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.crypto

import javax.crypto.SecretKey

/**
 * Gate for private message bodies.
 *
 * Secure mode (production, whenever a [PeerKeyRing] is wired) refuses two
 * failure modes that used to put private text on the mesh:
 *
 *  1. First contact with no verified peer key used to send the body as
 *     plaintext. [sealForTransport] returns null instead, and the caller
 *     keeps the row local until an out-of-band verified key exists.
 *  2. A sealed payload that did not decrypt (missing key, wrong key, tamper,
 *     or a plaintext body wearing no envelope) used to be stored and shown
 *     as the message. [openForDisplay] returns null so the caller drops it.
 *
 * [secureMode] false preserves the historical plaintext path for unit tests
 * and engines that were constructed without a key ring. Production always
 * passes true.
 */
object SecureContent {

    /**
     * Returns the on-wire body, or null when secure mode cannot seal to a
     * verified peer. Null means "do not transmit".
     */
    fun sealForTransport(
        framedBody: String,
        key: SecretKey?,
        peerVerified: Boolean,
        secureMode: Boolean
    ): String? {
        if (!secureMode) return framedBody
        if (!peerVerified || key == null) return null
        return SealedEnvelope.seal(framedBody, key)
    }

    /**
     * Returns the body safe to show the user, or null when the payload must
     * be dropped. Never returns the raw wire string in secure mode.
     */
    fun openForDisplay(
        raw: String,
        key: SecretKey?,
        peerVerified: Boolean,
        secureMode: Boolean
    ): String? {
        if (!secureMode) return raw
        if (!peerVerified || key == null) return null
        if (!SealedEnvelope.looksSealed(raw)) return null
        return SealedEnvelope.unseal(raw, key)
    }
}
