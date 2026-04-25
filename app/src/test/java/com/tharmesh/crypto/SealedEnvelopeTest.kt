// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.crypto

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedEnvelopeTest {

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripRecoversExactPlaintext() {
        val key = newKey()
        val plain = "hello mesh, 🌐 / newline\n / unicode: привет"
        val wire = SealedEnvelope.seal(plain, key)
        assertTrue("Wire form must declare version", wire.startsWith(SealedEnvelope.PREFIX))
        assertEquals(plain, SealedEnvelope.unseal(wire, key))
    }

    @Test
    fun ivIsFreshPerSeal_sameKeyAndPlaintextYieldDifferentWire() {
        val key = newKey()
        val a = SealedEnvelope.seal("same", key)
        val b = SealedEnvelope.seal("same", key)
        assertNotEquals("IV must be random: duplicate wire strings indicate IV reuse", a, b)
        assertEquals("same", SealedEnvelope.unseal(a, key))
        assertEquals("same", SealedEnvelope.unseal(b, key))
    }

    @Test
    fun wrongKeyYieldsNull_neverThrows() {
        val a = newKey()
        val b = newKey()
        val wire = SealedEnvelope.seal("secret", a)
        // GCM auth-tag mismatch with wrong key must produce null, never plaintext.
        assertNull(SealedEnvelope.unseal(wire, b))
    }

    @Test
    fun tamperedCiphertextYieldsNull() {
        val key = newKey()
        val wire = SealedEnvelope.seal("payload", key)
        // Flip one base64 char in the ciphertext section. Decryption must fail on
        // the GCM auth tag rather than silently returning garbage.
        val flipped = wire.dropLast(1) + if (wire.last() == 'A') 'B' else 'A'
        assertNull(SealedEnvelope.unseal(flipped, key))
    }

    @Test
    fun missingPrefix_isNotSealed_andUnsealReturnsNull() {
        val key = newKey()
        val plaintextLooking = "legacy plaintext body"
        assertFalse(SealedEnvelope.looksSealed(plaintextLooking))
        assertNull(SealedEnvelope.unseal(plaintextLooking, key))
    }

    @Test
    fun malformedEnvelope_returnsNull_ratherThanThrowing() {
        val key = newKey()
        assertNull("empty tail", SealedEnvelope.unseal("TME1:", key))
        assertNull("missing separator", SealedEnvelope.unseal("TME1:abcdef", key))
        assertNull("empty iv", SealedEnvelope.unseal("TME1::ct", key))
        assertNull("non-base64 iv", SealedEnvelope.unseal("TME1:!!!:!!!", key))
    }

    @Test
    fun looksSealed_isPureStringTest() {
        assertTrue(SealedEnvelope.looksSealed("TME1:anything"))
        assertFalse(SealedEnvelope.looksSealed("TME1")) // no colon
        assertFalse(SealedEnvelope.looksSealed("tme1:"))
        assertFalse(SealedEnvelope.looksSealed(""))
    }
}
