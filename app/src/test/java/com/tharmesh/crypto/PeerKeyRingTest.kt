// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.crypto

import com.tharmesh.identity.Base64Url
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerKeyRingTest {

    private fun newP256Pair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        return gen.generateKeyPair()
    }

    private fun pubB64(pair: KeyPair): String = Base64Url.encode(pair.public.encoded)

    @Test
    fun bothSidesDeriveTheSameSymmetricKey_andCanDecryptEachOther() {
        val alicePair = newP256Pair()
        val bobPair = newP256Pair()

        val aliceRing = PeerKeyRing(
            localPrivateKey = alicePair.private,
            localUserId = "alice",
            resolvePublicKeyBase64 = { peer -> if (peer == "bob") pubB64(bobPair) else null }
        )
        val bobRing = PeerKeyRing(
            localPrivateKey = bobPair.private,
            localUserId = "bob",
            resolvePublicKeyBase64 = { peer -> if (peer == "alice") pubB64(alicePair) else null }
        )

        val aliceKey = aliceRing.keyFor("bob")
        val bobKey = bobRing.keyFor("alice")
        assertNotNull(aliceKey)
        assertNotNull(bobKey)
        // Exact byte equality of the derived keys is the ECDH contract.
        assertArrayEquals(aliceKey!!.encoded, bobKey!!.encoded)

        // Round-trip an envelope in both directions.
        val a2b = SealedEnvelope.seal("ping", aliceKey)
        assertEquals("ping", SealedEnvelope.unseal(a2b, bobKey))
        val b2a = SealedEnvelope.seal("pong", bobKey)
        assertEquals("pong", SealedEnvelope.unseal(b2a, aliceKey))
    }

    @Test
    fun differentPeerPairsDeriveDifferentKeys() {
        val alice = newP256Pair()
        val bob = newP256Pair()
        val carol = newP256Pair()
        val ring = PeerKeyRing(
            localPrivateKey = alice.private,
            localUserId = "alice",
            resolvePublicKeyBase64 = { peer ->
                when (peer) {
                    "bob" -> pubB64(bob)
                    "carol" -> pubB64(carol)
                    else -> null
                }
            }
        )
        val forBob = ring.keyFor("bob")
        val forCarol = ring.keyFor("carol")
        assertNotNull(forBob)
        assertNotNull(forCarol)
        assertFalse(forBob!!.encoded.contentEquals(forCarol!!.encoded))
    }

    @Test
    fun missingPublicKey_returnsNull_ratherThanThrowing() {
        val alice = newP256Pair()
        val ring = PeerKeyRing(
            localPrivateKey = alice.private,
            localUserId = "alice",
            resolvePublicKeyBase64 = { _ -> null }
        )
        assertNull(ring.keyFor("unknown-peer"))
    }

    @Test
    fun malformedPublicKey_returnsNull_andDoesNotPoisonCache() {
        val alice = newP256Pair()
        val bob = newP256Pair()
        var malformed = true
        val ring = PeerKeyRing(
            localPrivateKey = alice.private,
            localUserId = "alice",
            resolvePublicKeyBase64 = { peer ->
                if (peer != "bob") null
                else if (malformed) "!!! not base64 !!!"
                else pubB64(bob)
            }
        )
        // First call fails → null, no cache entry.
        assertNull(ring.keyFor("bob"))
        // Swap to a valid key and retry; since the cache is empty, derivation succeeds.
        malformed = false
        assertNotNull(ring.keyFor("bob"))
    }

    @Test
    fun invalidateForcesReDerivation() {
        val alice = newP256Pair()
        val bob = newP256Pair()
        val bob2 = newP256Pair()
        var usePair = bob
        val ring = PeerKeyRing(
            localPrivateKey = alice.private,
            localUserId = "alice",
            resolvePublicKeyBase64 = { peer -> if (peer == "bob") pubB64(usePair) else null }
        )
        val first = ring.keyFor("bob")!!
        usePair = bob2
        // Without invalidate: cached → same key regardless of resolver changes.
        val cached = ring.keyFor("bob")!!
        assertArrayEquals(first.encoded, cached.encoded)
        // After invalidate: resolver is consulted again and the new peer key yields a fresh AES key.
        ring.invalidate("bob")
        val refreshed = ring.keyFor("bob")!!
        assertNotEquals(
            "Rotated peer key must produce a different derived AES key",
            first.encoded.toList(),
            refreshed.encoded.toList()
        )
    }

    @Test
    fun infoBytesAreSymmetric_andBindBothUserIds() {
        // Order independence is what makes both sides derive the same key regardless
        // of who initiates a send.
        val a = PeerKeyRing.infoBytes("alice", "bob")
        val b = PeerKeyRing.infoBytes("bob", "alice")
        assertArrayEquals(a, b)
        // Different pair ⇒ different info ⇒ different derived key.
        val c = PeerKeyRing.infoBytes("alice", "carol")
        assertFalse(a.contentEquals(c))
    }

    @Test
    fun hkdfSha256_rfc5869TestVector1() {
        // RFC 5869 Test Case 1: IKM = 22 bytes of 0x0b, salt = 13 bytes 0x00..0x0c,
        // info = 10 bytes 0xf0..0xf9, L = 42, expected OKM as below.
        val ikm = ByteArray(22) { 0x0b.toByte() }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val expected = hexToBytes(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"
        )
        val okm = PeerKeyRing.hkdfSha256(ikm, salt, info, 42)
        assertArrayEquals(expected, okm)
    }

    // Local helpers — assertArrayEquals on ByteArray is Kotlin-ambiguous.
    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue("array length mismatch", expected.size == actual.size)
        for (i in expected.indices) {
            assertEquals("byte $i", expected[i], actual[i])
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
