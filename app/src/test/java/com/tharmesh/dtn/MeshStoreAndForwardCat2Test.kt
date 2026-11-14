package com.tharmesh.dtn

import com.tharmesh.crypto.SealedEnvelope
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.identity.PeerTrustStore
import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import com.tharmesh.transport.loopback.LoopbackTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator

/**
 * Category 2 — real [MeshEngine], [BundleStore], [BundleCodec] (loopback transport).
 */
class MeshStoreAndForwardCat2Test {

    private class MemoryStore : BundleStore {
        val rows: MutableMap<String, MeshBundle> = ConcurrentHashMap()
        override fun upsert(bundle: MeshBundle) {
            rows[bundle.bundleId] = bundle
        }

        override fun find(bundleId: String): MeshBundle? = rows[bundleId]

        override fun loadActive(nowMs: Long): List<MeshBundle> =
            rows.values.filter { it.ttlUntil >= nowMs }

        override fun updateStatus(bundleId: String, status: String) {
            rows[bundleId]?.let { rows[bundleId] = it.copy(status = status) }
        }

        override fun delete(bundleId: String) {
            rows.remove(bundleId)
        }

        override fun deleteExpired(nowMs: Long): Int {
            val gone = rows.filterValues { it.ttlUntil < nowMs }.keys
            gone.forEach { rows.remove(it) }
            return gone.size
        }
    }

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
            return PeerTrustStore.Verdict.Mismatch("", "")
        }

        override fun storedKey(userId: String): String? = rows[userId]?.key
        override fun trustState(userId: String): PeerTrustStore.TrustState =
            if (rows[userId]?.verified == true) PeerTrustStore.TrustState.Verified
            else PeerTrustStore.TrustState.Unknown

        override fun markVerified(userId: String, scannedPubKeyBase64: String): PeerTrustStore.VerifyResult {
            rows[userId] = Row(scannedPubKeyBase64, true)
            return PeerTrustStore.VerifyResult.Verified
        }
    }

    private class BridgedTransport : Transport {
        val sends: MutableList<Triple<String, ByteArray, Long>> = CopyOnWriteArrayList()
        private var listener: ((TransportEvent) -> Unit)? = null
        private var localPeerId: String = ""
        private val neighbors: MutableMap<String, BridgedTransport> = HashMap()

        override fun setListener(listener: (TransportEvent) -> Unit) {
            this.listener = listener
        }

        override fun start(localPeerId: String) {
            this.localPeerId = localPeerId
        }

        override fun stop() {}

        override fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean {
            sends.add(Triple(peerId, payload, sendId))
            val neighbor = neighbors[peerId] ?: run {
                listener?.invoke(TransportEvent.Error(peerId, sendId, "not a neighbor"))
                return false
            }
            neighbor.listener?.invoke(TransportEvent.PayloadReceived(localPeerId, payload))
            listener?.invoke(TransportEvent.PayloadSent(peerId, sendId, payload.size))
            return true
        }

        fun link(other: BridgedTransport) {
            neighbors[other.localPeerId] = other
            other.neighbors[this.localPeerId] = this
            listener?.invoke(TransportEvent.PeerConnected(other.localPeerId))
            other.listener?.invoke(TransportEvent.PeerConnected(this.localPeerId))
        }
    }

    private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun duplicateRetransmit_doesNotDoubleDeliver() {
        val hub = LoopbackTransport.Hub()
        val store = MemoryStore()
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        val alice = MeshEngine("alice", LoopbackTransport(hub), bundleStore = store)
        val bob = MeshEngine("bob", LoopbackTransport(hub), bundleStore = MemoryStore())
        bob.setEventListener { bobEvents.add(it) }
        alice.start()
        bob.start()
        val bundle = alice.queueText("bob", "msg", 60_000L, 3, bundleIdHint = "dup-cat2-1")
        alice.retryBundle(bundle.bundleId)
        assertEquals(1, bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().size)
        assertNotNull(store.find(bundle.bundleId))
    }

    @Test
    fun ack_pendingToDelivered_onlyWithValidSignature() {
        val hub = LoopbackTransport.Hub()
        val aliceId = CryptoIdentity.generate()
        val bobId = CryptoIdentity.generate()
        val aliceTrust = MemoryTrust()
        val bobTrust = MemoryTrust()
        aliceTrust.markVerified("bob", bobId.publicKeyBase64)
        bobTrust.markVerified("alice", aliceId.publicKeyBase64)
        val aliceEvents = CopyOnWriteArrayList<MeshEvent>()
        val alice = MeshEngine(
            "alice", LoopbackTransport(hub), identity = aliceId, peerTrustStore = aliceTrust,
            requireVerifiedIdentity = true
        )
        val bob = MeshEngine(
            "bob", LoopbackTransport(hub), identity = bobId, peerTrustStore = bobTrust,
            requireVerifiedIdentity = true
        )
        alice.setEventListener { aliceEvents.add(it) }
        alice.start()
        val wire = SealedEnvelope.seal("body", aesKey())
        val bundle = alice.queueText("bob", wire, 60_000L, 3, bundleIdHint = "ack-cat2-1")
        val mallory = LoopbackTransport(hub)
        mallory.start("mallory")
        mallory.send("alice", ("ACK|mallory|" + bundle.bundleId + "|forged").toByteArray(), 1L)
        assertTrue(aliceEvents.filterIsInstance<MeshEvent.BundleAcked>().isEmpty())
        bob.start()
        if (aliceEvents.none { it is MeshEvent.BundleAcked }) {
            alice.retryBundle(bundle.bundleId)
        }
        assertEquals(1, aliceEvents.filterIsInstance<MeshEvent.BundleAcked>().size)
    }

    @Test
    fun receiverUnavailableThenReturns_deliversOnRetry() {
        val hub = LoopbackTransport.Hub()
        val alice = MeshEngine("alice", LoopbackTransport(hub))
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        alice.start()
        alice.queueText("bob", "later", 60_000L, 4, bundleIdHint = "retry-cat2-1")
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        bob.setEventListener { bobEvents.add(it) }
        bob.start()
        alice.retryBundle("retry-cat2-1")
        assertEquals(1, bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().size)
    }

    @Test
    fun processDeath_restoresPendingQueueFromBundleStore() {
        val store = MemoryStore()
        val now = System.currentTimeMillis()
        store.upsert(
            MeshBundle(
                bundleId = "death-cat2-1",
                srcId = "alice",
                destId = "bob",
                payloadCiphertext = "TME1:x",
                ttlUntil = now + 60_000L,
                hopsLeft = 3,
                signature = "sig",
                status = "PENDING"
            )
        )
        val hub = LoopbackTransport.Hub()
        val engine = MeshEngine("alice", LoopbackTransport(hub), bundleStore = store)
        engine.start()
        assertTrue(engine.retryBundle("death-cat2-1"))
    }

    @Test
    fun relayThreeHop_BstoresCiphertextOnly_noDecrypt() {
        val tA = BridgedTransport()
        val tB = BridgedTransport()
        val tC = BridgedTransport()
        val aliceId = CryptoIdentity.generate()
        val carolId = CryptoIdentity.generate()
        val aliceTrust = MemoryTrust()
        val carolTrust = MemoryTrust()
        aliceTrust.markVerified("carol", carolId.publicKeyBase64)
        carolTrust.markVerified("alice", aliceId.publicKeyBase64)
        val bDelivered = CopyOnWriteArrayList<MeshEvent>()
        val carolEvents = CopyOnWriteArrayList<MeshEvent>()
        val bRelays = CopyOnWriteArrayList<String>()
        val alice = MeshEngine(
            "alice", tA, identity = aliceId, peerTrustStore = aliceTrust, requireVerifiedIdentity = true
        )
        val relay = MeshEngine("bob", tB, onRelaySent = { _, id, _ -> bRelays.add(id) })
        val carol = MeshEngine(
            "carol", tC, identity = carolId, peerTrustStore = carolTrust, requireVerifiedIdentity = true
        )
        relay.setEventListener { bDelivered.add(it) }
        carol.setEventListener { carolEvents.add(it) }
        alice.start()
        relay.start()
        carol.start()
        tA.link(tB)
        tB.link(tC)
        val wire = SealedEnvelope.seal("relay-secret", aesKey())
        val bundle = alice.queueText("carol", wire, 60_000L, 4, bundleIdHint = "hop-cat2-1")
        assertTrue(bDelivered.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty())
        assertEquals(1, bRelays.size)
        val frame = tB.sends.firstOrNull { it.first == "carol" && String(it.second).contains("hop-cat2-1") }
        assertNotNull(frame)
        assertFalse(String(frame!!.second).contains("relay-secret"))
        val delivered = carolEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(1, delivered.size)
        assertEquals(bundle.bundleId, delivered[0].bundle.bundleId)
        assertFalse(delivered[0].bundle.payloadCiphertext.contains("relay-secret"))
        assertEquals("carol", delivered[0].bundle.destId)
    }
}
