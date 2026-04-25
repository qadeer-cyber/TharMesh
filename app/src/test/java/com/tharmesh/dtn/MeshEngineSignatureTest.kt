package com.tharmesh.dtn

import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.identity.PeerTrustStore
import com.tharmesh.transport.loopback.LoopbackTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 4.6: signature + TOFU enforcement on the [MeshEngine] receive path.
 *
 * Verification matrix:
 *  - Signed bundle round-trip via LoopbackTransport → delivered.
 *  - Tampered payload pushed through [MeshEngine.handleFrameForTest] → rejected.
 *  - Same srcId with a rotated keypair after first sight → rejected (TOFU).
 *  - Unsigned bundle under the default strict-mode receiver → rejected.
 *  - Unsigned bundle under `allowLegacyUnsigned=true` receiver → accepted.
 */
class MeshEngineSignatureTest {

    /**
     * Minimal in-memory [PeerTrustStore]. First-writer-wins semantics match the
     * Room-backed implementation.
     */
    private class FakePeerTrustStore : PeerTrustStore {
        val keys: ConcurrentHashMap<String, String> = ConcurrentHashMap()
        override fun verdict(userId: String, presentedPubKeyBase64: String): PeerTrustStore.Verdict {
            val existing = keys.putIfAbsent(userId, presentedPubKeyBase64)
            return when {
                existing == null -> PeerTrustStore.Verdict.FirstSeen
                existing == presentedPubKeyBase64 -> PeerTrustStore.Verdict.Match
                else -> PeerTrustStore.Verdict.Mismatch(
                    storedFingerprint = CryptoIdentity.fingerprintOf(existing),
                    presentedFingerprint = CryptoIdentity.fingerprintOf(presentedPubKeyBase64)
                )
            }
        }
        override fun storedKey(userId: String): String? = keys[userId]
    }

    @Test
    fun signedBundle_roundTrip_deliversToReceiver() {
        val hub = LoopbackTransport.Hub()
        val aliceId = CryptoIdentity.generate()
        val bobEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val alice = MeshEngine(
            localUserId = "alice",
            transport = LoopbackTransport(hub),
            identity = aliceId
        )
        val bob = MeshEngine(
            localUserId = "bob",
            transport = LoopbackTransport(hub),
            peerTrustStore = FakePeerTrustStore()
        )
        bob.setEventListener { bobEvents.add(it) }
        alice.start(); bob.start()

        alice.queueText("bob", "hello signed", 60_000L, 3, bundleIdHint = "sig-1")

        val delivered = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(1, delivered.size)
        assertEquals("sig-1", delivered[0].bundle.bundleId)
    }

    @Test
    fun tamperedPayload_rejectedOnReceive() {
        val hub = LoopbackTransport.Hub()
        val aliceId = CryptoIdentity.generate()
        val bobEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val alice = MeshEngine("alice", LoopbackTransport(hub), identity = aliceId)
        val bob = MeshEngine(
            localUserId = "bob",
            transport = LoopbackTransport(hub),
            peerTrustStore = FakePeerTrustStore()
        )
        bob.setEventListener { bobEvents.add(it) }
        alice.start(); bob.start()

        // Originate a legit signed bundle, then hand-craft a sibling bundle that
        // reuses the same signature + pubkey but with a DIFFERENT payload. The
        // ECDSA verify step must reject it.
        val original = alice.queueText("bob", "original", 60_000L, 3, bundleIdHint = "tamper-1")
        bobEvents.clear()

        val tampered = original.copy(
            bundleId = "tamper-2", // distinct bundleId so the dedup cache doesn't short-circuit
            payloadCiphertext = "MALICIOUS"
        )
        // Inject the tampered frame via a rogue LoopbackTransport registered as
        // "mallory". The frame's envelope claims to be from alice (frame.fromPeerId
        // is used only for ACK routing; what matters for the signature check is
        // the embedded bundle.srcId + bundle.srcPubKey).
        val mallory = LoopbackTransport(hub)
        mallory.start("mallory")
        val rogueFrame = "BUNDLE|alice|" + BundleCodec.encode(tampered)
        mallory.send("bob", rogueFrame.toByteArray(Charsets.UTF_8), sendId = 0L)

        val deliveredAfter = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertTrue(
            "tampered bundle MUST NOT be delivered",
            deliveredAfter.none { it.bundle.bundleId == "tamper-2" }
        )
    }

    @Test
    fun tofuMismatch_rejectsBundleFromRotatedKey() {
        val hub = LoopbackTransport.Hub()
        val aliceOriginal = CryptoIdentity.generate()
        val aliceRotated = CryptoIdentity.generate()
        val bobEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()

        val aliceFirst = MeshEngine("alice", LoopbackTransport(hub), identity = aliceOriginal)
        val bob = MeshEngine(
            localUserId = "bob",
            transport = LoopbackTransport(hub),
            peerTrustStore = FakePeerTrustStore()
        )
        bob.setEventListener { bobEvents.add(it) }
        aliceFirst.start(); bob.start()

        // First bundle: pins aliceOriginal's key in Bob's TOFU store.
        aliceFirst.queueText("bob", "first", 60_000L, 3, bundleIdHint = "tofu-1")
        assertTrue(bobEvents.any { it is MeshEvent.BundleDelivered && it.bundle.bundleId == "tofu-1" })
        bobEvents.clear()

        // Now "alice" (same userId) rotates to a new keypair — simulates an
        // attacker or a legitimate re-install the receiver does NOT trust yet.
        val aliceImposter = MeshEngine("alice", LoopbackTransport(hub), identity = aliceRotated)
        aliceImposter.start()
        aliceImposter.queueText("bob", "rotated", 60_000L, 3, bundleIdHint = "tofu-2")

        val delivered = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertTrue(
            "post-rotation bundle MUST be rejected under TOFU",
            delivered.none { it.bundle.bundleId == "tofu-2" }
        )
    }

    @Test
    fun unsignedBundle_rejectedByDefault() {
        val hub = LoopbackTransport.Hub()
        val aliceEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val bobEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        // Alice has NO identity — she emits unsigned bundles.
        val alice = MeshEngine("alice", LoopbackTransport(hub))
        alice.setEventListener { aliceEvents.add(it) }
        // Bob HAS an identity → strict mode: unsigned bundles must be rejected.
        val bob = MeshEngine(
            localUserId = "bob",
            transport = LoopbackTransport(hub),
            identity = CryptoIdentity.generate(),
            peerTrustStore = FakePeerTrustStore()
        )
        bob.setEventListener { bobEvents.add(it) }
        alice.start(); bob.start()

        alice.queueText("bob", "unsigned", 60_000L, 3, bundleIdHint = "unsigned-1")

        assertTrue(
            "unsigned bundle MUST NOT be delivered under default policy",
            bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty()
        )
    }

    @Test
    fun unsignedBundle_acceptedWhenLegacyFlagOn() {
        val hub = LoopbackTransport.Hub()
        val bobEvents: MutableList<MeshEvent> = CopyOnWriteArrayList()
        val alice = MeshEngine("alice", LoopbackTransport(hub))
        // Bob has an identity but explicitly opts back into legacy-unsigned mode.
        val bob = MeshEngine(
            localUserId = "bob",
            transport = LoopbackTransport(hub),
            identity = CryptoIdentity.generate(),
            peerTrustStore = FakePeerTrustStore(),
            allowLegacyUnsigned = true
        )
        bob.setEventListener { bobEvents.add(it) }
        alice.start(); bob.start()

        alice.queueText("bob", "legacy", 60_000L, 3, bundleIdHint = "legacy-1")

        val delivered = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().firstOrNull()
        assertNotNull("legacy-flag on → unsigned bundle must be accepted", delivered)
        assertEquals("legacy-1", delivered!!.bundle.bundleId)
    }

    @Test
    fun queueText_producesNonEmptySignatureAndPubKey() {
        val hub = LoopbackTransport.Hub()
        val alice = MeshEngine(
            localUserId = "alice",
            transport = LoopbackTransport(hub),
            identity = CryptoIdentity.generate()
        )
        alice.start()
        val b = alice.queueText("bob", "x", 60_000L, 3, bundleIdHint = "shape-1")
        assertFalse("signature must be populated on originated bundles", b.signature.isEmpty())
        assertFalse("srcPubKey must be populated on originated bundles", b.srcPubKey.isEmpty())
    }
}
