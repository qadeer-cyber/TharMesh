package com.tharmesh.dtn

import com.tharmesh.crypto.SealedEnvelope
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.identity.PeerTrustStore
import com.tharmesh.transport.loopback.LoopbackTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Additional Phase 3 negatives on the real [MeshEngine] path (loopback simulation).
 */
class MeshEnginePhase3NegativeTest {

    private class MemoryTrust : PeerTrustStore {
        data class Row(val key: String, val verified: Boolean)
        val rows: ConcurrentHashMap<String, Row> = ConcurrentHashMap()

        override fun verdict(userId: String, presentedPubKeyBase64: String): PeerTrustStore.Verdict {
            val existing = rows[userId]
            if (existing == null) {
                rows[userId] = Row(presentedPubKeyBase64, false)
                return PeerTrustStore.Verdict.FirstSeen
            }
            if (existing.key == presentedPubKeyBase64) return PeerTrustStore.Verdict.Match
            return PeerTrustStore.Verdict.Mismatch(
                CryptoIdentity.fingerprintOf(existing.key),
                CryptoIdentity.fingerprintOf(presentedPubKeyBase64)
            )
        }

        override fun storedKey(userId: String): String? = rows[userId]?.key
        override fun trustState(userId: String): PeerTrustStore.TrustState {
            val row = rows[userId] ?: return PeerTrustStore.TrustState.Unknown
            return if (row.verified) PeerTrustStore.TrustState.Verified else PeerTrustStore.TrustState.TofuOnly
        }

        override fun markVerified(userId: String, scannedPubKeyBase64: String): PeerTrustStore.VerifyResult {
            rows[userId] = Row(scannedPubKeyBase64, true)
            return PeerTrustStore.VerifyResult.Verified
        }
    }

    private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun keySubstitution_verifiedPeerDifferentKey_rejectedIdentityUnchanged() {
        val hub = LoopbackTransport.Hub()
        val aliceId = CryptoIdentity.generate()
        val malloryId = CryptoIdentity.generate()
        val bobTrust = MemoryTrust()
        bobTrust.markVerified("alice", aliceId.publicKeyBase64)
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        val bob = MeshEngine(
            "bob", LoopbackTransport(hub),
            identity = CryptoIdentity.generate(),
            peerTrustStore = bobTrust,
            requireVerifiedIdentity = true
        )
        bob.setEventListener { bobEvents.add(it) }
        bob.start()
        val cipher = SealedEnvelope.seal("substitute", aesKey())
        val ttlUntil = System.currentTimeMillis() + 60_000L
        val canonical = CryptoIdentity.canonicalBundleBytes(
            bundleId = "key-sub",
            srcId = "alice",
            destId = "bob",
            payloadCiphertext = cipher,
            ttlUntil = ttlUntil
        )
        val impostor = MeshBundle(
            bundleId = "key-sub",
            srcId = "alice",
            destId = "bob",
            payloadCiphertext = cipher,
            ttlUntil = ttlUntil,
            hopsLeft = 3,
            signature = malloryId.sign(canonical),
            status = "PENDING",
            srcPubKey = malloryId.publicKeyBase64
        )
        val frame = "BUNDLE|mallory|" + BundleCodec.encode(impostor)
        val inj = LoopbackTransport(hub)
        inj.start("injector")
        inj.send("bob", frame.toByteArray(Charsets.UTF_8), 1L)
        assertTrue(bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty())
        assertEquals(aliceId.publicKeyBase64, bobTrust.storedKey("alice"))
    }

    @Test
    fun tamperedCiphertext_validFrameSignature_invalid_gcmNotDeliveredNotAcked() {
        val hub = LoopbackTransport.Hub()
        val aliceId = CryptoIdentity.generate()
        val bobId = CryptoIdentity.generate()
        val bobTrust = MemoryTrust()
        bobTrust.markVerified("alice", aliceId.publicKeyBase64)
        val aliceEvents = CopyOnWriteArrayList<MeshEvent>()
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        val alice = MeshEngine(
            "alice", LoopbackTransport(hub),
            identity = aliceId, peerTrustStore = MemoryTrust(), requireVerifiedIdentity = true
        )
        val bob = MeshEngine(
            "bob", LoopbackTransport(hub),
            identity = bobId, peerTrustStore = bobTrust,             requireVerifiedIdentity = true
        )
        val key = aesKey()
        bob.setDeliveryGate { b ->
            val plain = SealedEnvelope.unseal(b.payloadCiphertext, key)
            plain != null
        }
        alice.setEventListener { aliceEvents.add(it) }
        bob.setEventListener { bobEvents.add(it) }
        alice.start(); bob.start()
        val bundle = alice.prepareOutbound(
            "bob",
            SealedEnvelope.seal("plain-secret", key),
            60_000L,
            3,
            bundleIdHint = "tamper-1"
        )
        val wire = bundle.payloadCiphertext.toCharArray()
        if (wire.size > 10) wire[10] = if (wire[10] == 'a') 'b' else 'a'
        val tampered = bundle.copy(payloadCiphertext = String(wire))
        val inj = LoopbackTransport(hub)
        inj.start("injector")
        inj.send(
            "bob",
            ("BUNDLE|mallory|" + BundleCodec.encode(tampered)).toByteArray(Charsets.UTF_8),
            2L
        )
        assertTrue(bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty())
        assertTrue(aliceEvents.filterIsInstance<MeshEvent.BundleAcked>().isEmpty())
        assertNull(SealedEnvelope.unseal(tampered.payloadCiphertext, key))
    }

    @Test
    fun maliciousRelay_resignsAlteredBundle_rejected() {
        val hub = LoopbackTransport.Hub()
        val aliceId = CryptoIdentity.generate()
        val relayId = CryptoIdentity.generate()
        val bobTrust = MemoryTrust()
        bobTrust.markVerified("alice", aliceId.publicKeyBase64)
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        val alice = MeshEngine(
            "alice", LoopbackTransport(hub), identity = aliceId, peerTrustStore = MemoryTrust()
        )
        val relay = MeshEngine(
            "relay", LoopbackTransport(hub), identity = relayId, peerTrustStore = MemoryTrust()
        )
        val bob = MeshEngine(
            "bob", LoopbackTransport(hub),
            identity = CryptoIdentity.generate(),
            peerTrustStore = bobTrust,
            requireVerifiedIdentity = true
        )
        bob.setEventListener { bobEvents.add(it) }
        alice.start(); relay.start(); bob.start()
        val honest = alice.prepareOutbound("bob", "cipher-x", 60_000L, 3, bundleIdHint = "mal-relay")
        val altered = honest.copy(
            payloadCiphertext = "cipher-evil",
            srcPubKey = relayId.publicKeyBase64,
            srcId = "alice"
        )
        relay.stop()
        val inj = LoopbackTransport(hub)
        inj.start("injector")
        inj.send(
            "bob",
            ("BUNDLE|relay|" + BundleCodec.encode(altered)).toByteArray(Charsets.UTF_8),
            3L
        )
        assertTrue(bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty())
    }

    @Test
    fun sealedPayload_atDestination_neverPlaintext_withoutDecrypt() {
        val hub = LoopbackTransport.Hub()
        val aliceId = CryptoIdentity.generate()
        val bobTrust = MemoryTrust()
        bobTrust.markVerified("alice", aliceId.publicKeyBase64)
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        val alice = MeshEngine(
            "alice", LoopbackTransport(hub), identity = aliceId, peerTrustStore = MemoryTrust()
        )
        val bob = MeshEngine(
            "bob", LoopbackTransport(hub),
            identity = CryptoIdentity.generate(),
            peerTrustStore = bobTrust,
            requireVerifiedIdentity = true
        )
        bob.setEventListener { bobEvents.add(it) }
        alice.start(); bob.start()
        val secret = SealedEnvelope.seal("not-visible-on-wire", aesKey())
        alice.queueText("bob", secret, 60_000L, 3, bundleIdHint = "relay-cipher")
        val delivered = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(1, delivered.size)
        assertFalse(delivered[0].bundle.payloadCiphertext.contains("not-visible-on-wire"))
        assertTrue(delivered[0].bundle.payloadCiphertext.startsWith(SealedEnvelope.PREFIX))
    }
}