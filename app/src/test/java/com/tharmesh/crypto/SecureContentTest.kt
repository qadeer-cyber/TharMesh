// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.crypto

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the private-content gate: no plaintext fallback, no silent downgrade.
 */
class SecureContentTest {

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun secureMode_refusesPlaintextWhenPeerIsNotVerified() {
        val key = newKey()
        assertNull(SecureContent.sealForTransport("secret", key, peerVerified = false, secureMode = true))
    }

    @Test
    fun secureMode_refusesPlaintextWhenNoKey() {
        assertNull(SecureContent.sealForTransport("secret", null, peerVerified = true, secureMode = true))
    }

    @Test
    fun secureMode_sealsOnlyForVerifiedKey() {
        val key = newKey()
        val wire = SecureContent.sealForTransport("secret", key, peerVerified = true, secureMode = true)
        assertNotNull(wire)
        assertTrue(wire!!.startsWith(SealedEnvelope.PREFIX))
        assertFalse(wire.contains("secret"))
        assertEquals("secret", SecureContent.openForDisplay(wire, key, peerVerified = true, secureMode = true))
    }

    @Test
    fun secureMode_doesNotDisplayPlaintextOrUndecryptableBytes() {
        val key = newKey()
        val other = newKey()
        val wire = SealedEnvelope.seal("secret", key)
        assertNull(
            "plaintext body must not be shown in secure mode",
            SecureContent.openForDisplay("hello in the clear", key, peerVerified = true, secureMode = true)
        )
        assertNull(
            "failed decrypt must not fall back to the raw envelope",
            SecureContent.openForDisplay(wire, other, peerVerified = true, secureMode = true)
        )
        assertNull(
            "unverified sender must not be displayed",
            SecureContent.openForDisplay(wire, key, peerVerified = false, secureMode = true)
        )
        assertNull(
            SecureContent.openForDisplay(wire, null, peerVerified = true, secureMode = true)
        )
    }

    @Test
    fun tamperedEnvelope_isDroppedRatherThanShown() {
        val key = newKey()
        val wire = SealedEnvelope.seal("secret", key)
        val flipped = wire.dropLast(1) + if (wire.last() == 'A') 'B' else 'A'
        assertNull(SecureContent.openForDisplay(flipped, key, peerVerified = true, secureMode = true))
    }

    @Test
    fun legacyMode_stillRoundTripsPlaintext_forEnginesWithoutAKeyRing() {
        assertEquals(
            "legacy",
            SecureContent.sealForTransport("legacy", null, peerVerified = false, secureMode = false)
        )
        assertEquals(
            "legacy",
            SecureContent.openForDisplay("legacy", null, peerVerified = false, secureMode = false)
        )
    }
}
