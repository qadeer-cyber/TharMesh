// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.dtn

import com.tharmesh.crypto.SealedEnvelope
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.identity.PeerTrustStore
import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
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
 * Authenticated delivery, ACK integrity, replay, and relay confidentiality.
 * Loopback only — this is not evidence from a BLE or Wi-Fi device.
 */
class MeshEngineSecurityTest {

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
            val existing = rows[userId]
            if (existing != null && existing.verified && existing.key != scannedPubKeyBase64) {
                return PeerTrustStore.VerifyResult.Mismatch(
                    CryptoIdentity.fingerprintOf(existing.key),
                    CryptoIdentity.fingerprintOf(scannedPubKeyBase64)
                )
            }
            rows[userId] = Row(scannedPubKeyBase64, true)
            return PeerTrustStore.VerifyResult.Verified
        }
    }

    private class MemoryStore : BundleStore {
        val rows: MutableMap<String, MeshBundle> = HashMap()
        override fun upsert(bundle: MeshBundle) { rows[bundle.bundleId] = bundle }
        override fun loadActive(nowMs: Long): List<MeshBundle> =
            rows.values.filter { it.ttlUntil >= nowMs }
        override fun updateStatus(bundleId: String, status: String) {
            val row = rows[bundleId] ?: return
            rows[bundleId] = row.copy(status = status)
        }
        override fun delete(bundleId: String) { rows.remove(bundleId) }
        override fun deleteExpired(nowMs: Long): Int {
            val gone = rows.filterValues { it.ttlUntil < nowMs }.keys
            gone.forEach { rows.remove(it) }
            return gone.size
        }
        override fun find(bundleId: String): MeshBundle? = rows[bundleId]
    }

    private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun unverifiedSender_isNotDelivered() {
        val hub = LoopbackTransport.Hub()
        val aliceId = CryptoIdentity.generate()
        val bobTrust = MemoryTrust()
        val bobEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
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
        val wire = SealedEnvelope.seal("secret-phrase", aesKey())
        alice.queueText("bob", wire, 60_000L, 3, bundleIdHint = "unverified-1")
        assertTrue(bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty())
    }

    @Test
    fun plaintextToVerifiedPeer_isNotDelivered() {
        val hub = LoopbackTransport.Hub()
        val aliceId = CryptoIdentity.generate()
        val bobTrust = MemoryTrust()
        bobTrust.markVerified("alice", aliceId.publicKeyBase64)
        val bobEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val alice = MeshEngine("alice", LoopbackTransport(hub), identity = aliceId)
        val bob = MeshEngine(
            "bob", LoopbackTransport(hub),
            identity = CryptoIdentity.generate(),
            peerTrustStore = bobTrust,
            requireVerifiedIdentity = true
        )
        bob.setEventListener { bobEvents.add(it) }
        alice.start(); bob.start()
        alice.queueText("bob", "hello in the clear", 60_000L, 3, bundleIdHint = "clear-1")
        assertTrue(bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty())
    }

    @Test
    fun verifiedSealedRoundTrip_acksOnce_andReplayDoesNotRedeliver() {
        val hub = LoopbackTransport.Hub()
        val aliceId = CryptoIdentity.generate()
        val bobId = CryptoIdentity.generate()
        val aliceTrust = MemoryTrust()
        val bobTrust = MemoryTrust()
        aliceTrust.markVerified("bob", bobId.publicKeyBase64)
        bobTrust.markVerified("alice", aliceId.publicKeyBase64)
        val aliceEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val bobEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val bobStore = MemoryStore()
        val alice = MeshEngine(
            "alice", LoopbackTransport(hub),
            identity = aliceId,
            peerTrustStore = aliceTrust,
            requireVerifiedIdentity = true
        )
        val bob = MeshEngine(
            "bob", LoopbackTransport(hub),
            identity = bobId,
            peerTrustStore = bobTrust,
            bundleStore = bobStore,
            requireVerifiedIdentity = true
        )
        alice.setEventListener { aliceEvents.add(it) }
        bob.setEventListener { bobEvents.add(it) }
        alice.start()
        val wire = SealedEnvelope.seal("secret-phrase", aesKey())
        val bundle = alice.queueText("bob", wire, 60_000L, 3, bundleIdHint = "sealed-1")
        // Bob is not up yet. A forged ACK must not flip Alice to delivered.
        val mallory = LoopbackTransport(hub)
        mallory.start("mallory")
        val forged = "ACK|mallory|" + bundle.bundleId
        mallory.send("alice", forged.toByteArray(Charsets.UTF_8), 0L)
        val forgedSig = "ACK|mallory|" + bundle.bundleId + "|not-a-signature"
        mallory.send("alice", forgedSig.toByteArray(Charsets.UTF_8), 0L)
        assertTrue(aliceEvents.filterIsInstance<MeshEvent.BundleAcked>().isEmpty())

        bob.start()
        // PeerConnected runs inventory sync, which can deliver before an explicit retry.
        if (bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty()) {
            assertTrue(alice.retryBundle(bundle.bundleId))
        }
        val delivered = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(1, delivered.size)
        assertEquals(wire, delivered[0].bundle.payloadCiphertext)
        assertFalse(delivered[0].bundle.payloadCiphertext.contains("secret-phrase"))
        val acked = aliceEvents.filterIsInstance<MeshEvent.BundleAcked>()
        assertEquals(1, acked.size)
        assertEquals("bob", acked[0].ackedByUserId)

        // Replay the same bundle id after Bob has it on disk and in the delivered set.
        val replay = "BUNDLE|mallory|" + BundleCodec.encode(bundle.copy(hopsLeft = 2))
        mallory.send("bob", replay.toByteArray(Charsets.UTF_8), 0L)
        assertEquals(1, bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().size)
        assertEquals("DELIVERED_FINAL", bobStore.find("sealed-1")?.status)
    }

    @Test
    fun badSignature_doesNotPinIdentity() {
        val hub = LoopbackTransport.Hub()
        val bobTrust = MemoryTrust()
        val bob = MeshEngine(
            "bob", LoopbackTransport(hub),
            identity = CryptoIdentity.generate(),
            peerTrustStore = bobTrust,
            requireVerifiedIdentity = true
        )
        bob.start()
        val attackerKey = CryptoIdentity.generate()
        val bogus = MeshBundle(
            bundleId = "bogus-1",
            srcId = "carol",
            destId = "bob",
            payloadCiphertext = "TME1:aa:bb",
            ttlUntil = System.currentTimeMillis() + 60_000L,
            hopsLeft = 3,
            signature = "not-a-real-signature",
            status = "PENDING",
            srcPubKey = attackerKey.publicKeyBase64
        )
        val mallory = LoopbackTransport(hub)
        mallory.start("mallory")
        mallory.send("bob", ("BUNDLE|mallory|" + BundleCodec.encode(bogus)).toByteArray(Charsets.UTF_8), 0L)
        assertNull(bobTrust.storedKey("carol"))
    }

    @Test
    fun productionFlag_doesNotTransmitPlaintext() {
        val hub = LoopbackTransport.Hub()
        val bobEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val alice = MeshEngine(
            "alice", LoopbackTransport(hub),
            identity = CryptoIdentity.generate(),
            refuseUnsealedLocalSend = true
        )
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        bob.setEventListener { bobEvents.add(it) }
        alice.start(); bob.start()
        alice.queueText("bob", "legacy plaintext", 60_000L, 3, bundleIdHint = "clear-out")
        assertTrue(bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty())
    }

    @Test
    fun deliveryGate_rejectsUndecryptableEnvelope_andDoesNotAck() {
        val hub = LoopbackTransport.Hub()
        val aliceId = CryptoIdentity.generate()
        val bobId = CryptoIdentity.generate()
        val aliceTrust = MemoryTrust()
        val bobTrust = MemoryTrust()
        aliceTrust.markVerified("bob", bobId.publicKeyBase64)
        bobTrust.markVerified("alice", aliceId.publicKeyBase64)
        val aliceEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val bobEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val alice = MeshEngine(
            "alice", LoopbackTransport(hub),
            identity = aliceId, peerTrustStore = aliceTrust, requireVerifiedIdentity = true
        )
        val bob = MeshEngine(
            "bob", LoopbackTransport(hub),
            identity = bobId, peerTrustStore = bobTrust, requireVerifiedIdentity = true
        )
        bob.setDeliveryGate { false }
        alice.setEventListener { aliceEvents.add(it) }
        bob.setEventListener { bobEvents.add(it) }
        alice.start(); bob.start()
        alice.queueText("bob", "TME1:not-a-real-envelope", 60_000L, 3, bundleIdHint = "bad-seal")
        assertTrue(bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty())
        assertTrue(aliceEvents.filterIsInstance<MeshEvent.BundleAcked>().isEmpty())
    }

    @Test
    fun threeHop_signedAckAndRead_rejectForgery() {
        val tA = Bridge()
        val tB = Bridge()
        val tC = Bridge()
        val aliceId = CryptoIdentity.generate()
        val carolId = CryptoIdentity.generate()
        val aliceTrust = MemoryTrust()
        val carolTrust = MemoryTrust()
        aliceTrust.markVerified("carol", carolId.publicKeyBase64)
        carolTrust.markVerified("alice", aliceId.publicKeyBase64)
        val aliceEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val carolEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val alice = MeshEngine(
            "alice", tA, identity = aliceId, peerTrustStore = aliceTrust, requireVerifiedIdentity = true
        )
        val relay = MeshEngine("bob", tB)
        val carol = MeshEngine(
            "carol", tC, identity = carolId, peerTrustStore = carolTrust, requireVerifiedIdentity = true
        )
        alice.setEventListener { aliceEvents.add(it) }
        carol.setEventListener { carolEvents.add(it) }
        alice.start(); relay.start(); carol.start()
        tA.link(tB)
        tB.link(tC)
        val wire = SealedEnvelope.seal("secret-phrase", aesKey())
        val bundle = alice.queueText("carol", wire, 60_000L, 4, bundleIdHint = "hop-ack")
        val delivered = carolEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(1, delivered.size)
        assertFalse(delivered[0].bundle.payloadCiphertext.contains("secret-phrase"))
        val acked = aliceEvents.filterIsInstance<MeshEvent.BundleAcked>()
        assertEquals(1, acked.size)
        assertEquals("carol", acked[0].ackedByUserId)

        // Unsigned ACK replayed by the relay must not create a second delivery.
        tB.injectFrom("bob", "ACK|bob|" + bundle.bundleId)
        assertEquals(1, aliceEvents.filterIsInstance<MeshEvent.BundleAcked>().size)

        carol.sendRead(bundle.bundleId, "alice")
        assertEquals(1, aliceEvents.filterIsInstance<MeshEvent.BundleRead>().size)
        tB.injectFrom("bob", "READ|carol|" + bundle.bundleId)
        assertEquals(1, aliceEvents.filterIsInstance<MeshEvent.BundleRead>().size)
    }

    /**
     * Test-only transport. Neighbors are explicit so A is not connected to C.
     */
    private class Bridge : Transport {
        private var listener: ((TransportEvent) -> Unit)? = null
        private var localPeerId: String = ""
        private val neighbors: MutableMap<String, Bridge> = HashMap()

        override fun setListener(listener: (TransportEvent) -> Unit) {
            this.listener = listener
        }

        override fun start(localPeerId: String) {
            this.localPeerId = localPeerId
        }

        override fun stop() {}

        override fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean {
            val neighbor = neighbors[peerId]
            if (neighbor == null) {
                listener?.invoke(TransportEvent.Error(peerId, sendId, "not a neighbor"))
                return false
            }
            neighbor.listener?.invoke(TransportEvent.PayloadReceived(localPeerId, payload))
            listener?.invoke(TransportEvent.PayloadSent(peerId, sendId, payload.size))
            return true
        }

        fun link(other: Bridge) {
            neighbors[other.localPeerId] = other
            other.neighbors[this.localPeerId] = this
            listener?.invoke(TransportEvent.PeerConnected(other.localPeerId))
            other.listener?.invoke(TransportEvent.PeerConnected(this.localPeerId))
        }

        fun injectFrom(fromPeerId: String, frame: String) {
            listener?.invoke(TransportEvent.PayloadReceived(fromPeerId, frame.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun corruptFrame_decodesToNull() {
        assertNull(BundleCodec.decode("{"))
        assertNull(BundleCodec.decode(""))
        assertNull(BundleCodec.decode("not json"))
    }
}
