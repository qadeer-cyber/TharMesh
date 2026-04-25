package com.tharmesh.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Low-level unit tests for [CryptoIdentity] and [Base64Url]. These exercise the
 * pure-JVM crypto stack without any Android framework dependency, so they run
 * under the standard `testDebugUnitTest` task with `returnDefaultValues = true`.
 */
class CryptoIdentityTest {

    @Test
    fun generate_producesVerifiableSignature() {
        val id = CryptoIdentity.generate()
        val data = "hello world".toByteArray()
        val sig = id.sign(data)
        assertTrue(
            "signed data must verify under the signer's public key",
            CryptoIdentity.verify(id.publicKeyBase64, data, sig)
        )
    }

    @Test
    fun verify_rejectsTamperedData() {
        val id = CryptoIdentity.generate()
        val data = "payload-v1".toByteArray()
        val sig = id.sign(data)
        val tampered = "payload-v2".toByteArray()
        assertFalse(
            "signature must NOT verify against tampered data",
            CryptoIdentity.verify(id.publicKeyBase64, tampered, sig)
        )
    }

    @Test
    fun verify_rejectsOtherPartysKey() {
        val alice = CryptoIdentity.generate()
        val bob = CryptoIdentity.generate()
        val data = "hi".toByteArray()
        val sig = alice.sign(data)
        assertFalse(
            "Bob's public key must NOT verify Alice's signature",
            CryptoIdentity.verify(bob.publicKeyBase64, data, sig)
        )
    }

    @Test
    fun roundTrip_fromBase64ReconstructsUsableKeypair() {
        val original = CryptoIdentity.generate()
        val priv = original.privateKeyBase64
        val pub = original.publicKeyBase64
        val restored = CryptoIdentity.fromBase64(priv, pub)
        assertNotNull("fromBase64 must succeed on valid inputs", restored)
        val data = "persist me".toByteArray()
        val sig = restored!!.sign(data)
        assertTrue(
            "restored identity must produce signatures that verify under its pubkey",
            CryptoIdentity.verify(pub, data, sig)
        )
    }

    @Test
    fun fromBase64_returnsNullOnGarbage() {
        assertEquals(null, CryptoIdentity.fromBase64("not base64 !@#", "still garbage"))
    }

    @Test
    fun verify_returnsFalseOnEmptyInputs() {
        assertFalse(CryptoIdentity.verify("", "x".toByteArray(), "y"))
        val id = CryptoIdentity.generate()
        assertFalse(CryptoIdentity.verify(id.publicKeyBase64, "x".toByteArray(), ""))
    }

    @Test
    fun fingerprintOf_stableAcrossCalls() {
        val id = CryptoIdentity.generate()
        val a = CryptoIdentity.fingerprintOf(id.publicKeyBase64)
        val b = CryptoIdentity.fingerprintOf(id.publicKeyBase64)
        assertEquals(a, b)
        assertEquals(16, a.length)
    }

    @Test
    fun fingerprintOf_differentKeysProduceDifferentFingerprints() {
        val a = CryptoIdentity.generate()
        val b = CryptoIdentity.generate()
        assertNotEquals(
            CryptoIdentity.fingerprintOf(a.publicKeyBase64),
            CryptoIdentity.fingerprintOf(b.publicKeyBase64)
        )
    }

    @Test
    fun canonicalBundleBytes_fieldOrderIsStable() {
        val a = CryptoIdentity.canonicalBundleBytes("b1", "src", "dst", "p", 42L)
        val b = CryptoIdentity.canonicalBundleBytes("b1", "src", "dst", "p", 42L)
        assertTrue("canonical blob must be deterministic", a.contentEquals(b))
    }

    @Test
    fun canonicalBundleBytes_anyFieldChangeAltersOutput() {
        val base = CryptoIdentity.canonicalBundleBytes("b1", "src", "dst", "p", 42L)
        val altBundleId = CryptoIdentity.canonicalBundleBytes("b2", "src", "dst", "p", 42L)
        val altTtl = CryptoIdentity.canonicalBundleBytes("b1", "src", "dst", "p", 43L)
        assertFalse(base.contentEquals(altBundleId))
        assertFalse(base.contentEquals(altTtl))
    }

    @Test
    fun base64Url_roundTripBinaryData() {
        val bytes = ByteArray(256) { it.toByte() }
        val enc = Base64Url.encode(bytes)
        val dec = Base64Url.decode(enc)
        assertNotNull(dec)
        assertTrue("Base64 round-trip must be identity", bytes.contentEquals(dec))
    }

    @Test
    fun base64Url_handlesEmptyInput() {
        assertEquals("", Base64Url.encode(ByteArray(0)))
        assertEquals(0, Base64Url.decode("")?.size)
    }

    @Test
    fun base64Url_rejectsInvalidLength() {
        // 3 chars is not a multiple of 4 after whitespace strip.
        assertEquals(null, Base64Url.decode("AAA"))
    }

    @Test
    fun base64Url_toleratesWhitespace() {
        val enc = Base64Url.encode("hello".toByteArray())
        val withSpaces = enc.map { "$it " }.joinToString("")
        val dec = Base64Url.decode(withSpaces)
        assertNotNull(dec)
        assertEquals("hello", String(dec!!, Charsets.UTF_8))
    }
}
