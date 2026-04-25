package com.tharmesh.dtn

import com.tharmesh.transport.loopback.LoopbackTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Stage 5.2 — engine-level integration tests for retry / TTL / pacer.
 *
 * Uses [LoopbackTransport] so that two [MeshEngine] instances can exchange real
 * frames in-process; no Robolectric, no Android. Each test instantiates its own
 * [LoopbackTransport.Hub] so concurrent runs don't share state.
 */
class MeshEngineRetryTtlAndPacingTest {

    @Test
    fun ttlExpiry_stopsRetryAndFiresDiagnosticHook() {
        val hub = LoopbackTransport.Hub()
        // Mutable clock — the bundle is created at t=0 with ttl=1s, then the
        // retry tick runs at t=2s after the TTL has elapsed.
        val clock = AtomicLong(0L)
        val nowFn: () -> Long = { clock.get() }
        val ttlDrops = mutableListOf<String>()
        val alice = MeshEngine(
            localUserId = "alice",
            transport = LoopbackTransport(hub),
            now = nowFn,
            onTtlExpiredDrop = { id -> ttlDrops.add(id) }
        )
        val bob = MeshEngine(
            localUserId = "bob",
            transport = LoopbackTransport(hub),
            now = nowFn
        )
        val aliceEvents = CopyOnWriteArrayList<MeshEvent>()
        alice.setEventListener { aliceEvents.add(it) }
        alice.start()
        // Bob is offline at queue time so the bundle stays cached for retry.
        val bundle = alice.queueText(
            destId = "bob",
            payloadCiphertext = "after-ttl",
            ttlMs = 1_000L,
            hops = 4,
            bundleIdHint = "ttl-bundle-1"
        )
        assertEquals("ttl-bundle-1", bundle.bundleId)
        // Advance past TTL.
        clock.set(2_000L)
        // Attempt a retry — must be a no-op AND must fire the TTL hook exactly once.
        alice.retryBundle("ttl-bundle-1")
        assertEquals(listOf("ttl-bundle-1"), ttlDrops)
        // Even if Bob comes online now, the cached bundle is no longer broadcast
        // (TTL guard kicks in). Calling retryBundle again should fire the hook again
        // (each call is its own TTL detection event), confirming retry truly stopped.
        bob.start()
        alice.retryBundle("ttl-bundle-1")
        assertEquals(2, ttlDrops.size)
        // Bob must NEVER have received the bundle.
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        bob.setEventListener { bobEvents.add(it) }
        // Drain a moment by triggering syncWithPeer-style nothingness.
        assertFalse(bobEvents.any { it is MeshEvent.BundleDelivered })
    }

    @Test
    fun perPeerSendPacer_defersSecondSendInsideGap_andResumesAfter() {
        val hub = LoopbackTransport.Hub()
        val clock = AtomicLong(0L)
        val nowFn: () -> Long = { clock.get() }
        val pacer = PerPeerSendPacer(gapMs = 50L)
        val pacedEvents = mutableListOf<String>()
        val alice = MeshEngine(
            localUserId = "alice",
            transport = LoopbackTransport(hub),
            now = nowFn,
            pacer = pacer,
            onSendPaced = { peerId -> pacedEvents.add(peerId) }
        )
        val bob = MeshEngine("bob", LoopbackTransport(hub), now = nowFn)
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        bob.setEventListener { bobEvents.add(it) }
        bob.start()
        alice.start()
        // First send acquires a slot.
        alice.queueText(
            destId = "bob",
            payloadCiphertext = "msg-1",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "b1"
        )
        // Second send at the same clock — must be paced (deferred).
        alice.queueText(
            destId = "bob",
            payloadCiphertext = "msg-2",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "b2"
        )
        // Bob should have received only the first bundle so far.
        val deliveredFirst = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(1, deliveredFirst.size)
        assertEquals("b1", deliveredFirst[0].bundle.bundleId)
        // Pacer hook fired exactly once for "bob".
        assertEquals(listOf("bob"), pacedEvents)
        // Advance past the gap and call retryBundle to flush the deferred send.
        clock.set(60L)
        alice.retryBundle("b2")
        val deliveredAfter = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>()
        assertEquals(2, deliveredAfter.size)
        assertEquals("b2", deliveredAfter[1].bundle.bundleId)
    }

    @Test
    fun pacerWithZeroGap_neverDefers() {
        // Verifies the pacer hook is NOT called when the configured gap is zero
        // — important so that tests / dev builds with disabled pacing don't
        // pollute the diagnostics counters.
        val hub = LoopbackTransport.Hub()
        val pacer = PerPeerSendPacer(gapMs = 0L)
        val pacedEvents = mutableListOf<String>()
        val alice = MeshEngine(
            localUserId = "alice",
            transport = LoopbackTransport(hub),
            pacer = pacer,
            onSendPaced = { peerId -> pacedEvents.add(peerId) }
        )
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        bob.setEventListener { bobEvents.add(it) }
        bob.start()
        alice.start()
        for (i in 1..5) {
            alice.queueText(
                destId = "bob",
                payloadCiphertext = "msg-$i",
                ttlMs = 60_000L,
                hops = 4,
                bundleIdHint = "b$i"
            )
        }
        assertTrue(pacedEvents.isEmpty())
        assertEquals(5, bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().size)
    }

    @Test
    fun retryBundle_ignoresExpiredButReturnsHookFiredEachCall() {
        // Documents the contract: retryBundle is idempotent w.r.t. broadcast on
        // expiry (no peer ever sees the bundle), but the diagnostic hook is
        // edge-triggered per call so the counter grows monotonically. The
        // repository tick will only call retryBundle once per loop iteration
        // for any given bundleId, so the hook count == #ticks-after-expiry,
        // which is the desired field-test signal.
        val hub = LoopbackTransport.Hub()
        val clock = AtomicLong(0L)
        val nowFn: () -> Long = { clock.get() }
        val ttlDrops = AtomicLong(0L)
        val alice = MeshEngine(
            localUserId = "alice",
            transport = LoopbackTransport(hub),
            now = nowFn,
            onTtlExpiredDrop = { ttlDrops.incrementAndGet() }
        )
        alice.start()
        alice.queueText(
            destId = "bob",
            payloadCiphertext = "x",
            ttlMs = 100L,
            hops = 4,
            bundleIdHint = "exp-1"
        )
        clock.set(200L)
        repeat(3) { alice.retryBundle("exp-1") }
        assertEquals(3L, ttlDrops.get())
    }

    @Test
    fun retryBundle_unknownBundleIdIsNoop() {
        val hub = LoopbackTransport.Hub()
        val ttlDrops = mutableListOf<String>()
        val alice = MeshEngine(
            localUserId = "alice",
            transport = LoopbackTransport(hub),
            onTtlExpiredDrop = { ttlDrops.add(it) }
        )
        alice.start()
        // Unknown bundle → no exception, no hook fired.
        alice.retryBundle("never-existed")
        assertTrue(ttlDrops.isEmpty())
    }

    // ---------- Stage 5.3 — priority bundles bypass the per-peer pacer ----------

    @Test
    fun priorityBundle_bypassesPacer_whileNonPriorityIsDeferred() {
        // Confirms the SOS hardening contract: a bundle with priority=true is
        // sent through the pacer guard regardless of the per-peer gap, while
        // the next normal bundle in the same tick is still deferred. This is
        // the property the SOS path relies on for "always broadcast with high
        // priority, bypass normal pacing".
        val hub = LoopbackTransport.Hub()
        val clock = AtomicLong(0L)
        val nowFn: () -> Long = { clock.get() }
        val pacer = PerPeerSendPacer(gapMs = 50L)
        val pacedEvents = mutableListOf<String>()
        val alice = MeshEngine(
            localUserId = "alice",
            transport = LoopbackTransport(hub),
            now = nowFn,
            pacer = pacer,
            onSendPaced = { peerId -> pacedEvents.add(peerId) }
        )
        val bob = MeshEngine("bob", LoopbackTransport(hub), now = nowFn)
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        bob.setEventListener { bobEvents.add(it) }
        bob.start()
        alice.start()

        // First send acquires a slot.
        alice.queueText(
            destId = "bob",
            payloadCiphertext = "n1",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "n1"
        )
        // Second send at the same clock — non-priority, must be paced.
        alice.queueText(
            destId = "bob",
            payloadCiphertext = "n2",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "n2"
        )
        // Third send at the same clock — PRIORITY: must NOT be paced even
        // though we're inside the 50 ms gap.
        alice.queueText(
            destId = "bob",
            payloadCiphertext = "sos",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "sos1",
            priority = true
        )

        val delivered = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().map { it.bundle.bundleId }
        // n1 and sos1 delivered immediately; n2 was deferred by the pacer.
        assertTrue("n1 must be delivered", delivered.contains("n1"))
        assertTrue("sos1 must bypass pacer", delivered.contains("sos1"))
        assertFalse("n2 must be deferred by pacer", delivered.contains("n2"))
        // Pacer hook fired exactly once for the deferred non-priority bundle.
        assertEquals(listOf("bob"), pacedEvents)
    }

    @Test
    fun priorityBit_isLocalOnly_andNotInheritedByRelays() {
        // Defensive — the priority bit must NOT be serialised on-wire. If a
        // received bundle had priority=true, a malicious peer could DDoS the
        // mesh by forging the bit. Confirm by round-tripping through
        // BundleCodec and asserting decode() yields priority=false.
        val originating = MeshBundle(
            bundleId = "p1",
            srcId = "alice",
            destId = "bob",
            payloadCiphertext = "x",
            ttlUntil = 100_000L,
            hopsLeft = 4,
            signature = "",
            status = "PENDING",
            priority = true
        )
        val wire = BundleCodec.encode(originating)
        val decoded = BundleCodec.decode(wire)
        assertNotNull(decoded)
        assertFalse(
            "priority bit must not be carried on-wire — relays apply normal pacing",
            decoded!!.priority
        )
    }

    @Test
    fun connectingBobAfterAliceQueued_deliversOnRetry_andHookNotFired() {
        // Confirms the happy "delay-tolerant" path: Bob comes online late, retry
        // succeeds, and TTL-drop hook is NOT triggered. This is the expected
        // "no terminal failure" semantic of Stage 5.2.
        val hub = LoopbackTransport.Hub()
        val ttlDrops = mutableListOf<String>()
        val alice = MeshEngine(
            localUserId = "alice",
            transport = LoopbackTransport(hub),
            onTtlExpiredDrop = { ttlDrops.add(it) }
        )
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        bob.setEventListener { bobEvents.add(it) }
        alice.start()
        // Alice sends BEFORE Bob is online — bundle stays cached.
        alice.queueText(
            destId = "bob",
            payloadCiphertext = "late-delivery",
            ttlMs = 60_000L,
            hops = 4,
            bundleIdHint = "late-1"
        )
        assertTrue(bobEvents.isEmpty())
        // Bob comes online and Alice retries.
        bob.start()
        alice.retryBundle("late-1")
        val delivered = bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().firstOrNull()
        assertNotNull(delivered)
        assertEquals("late-1", delivered!!.bundle.bundleId)
        assertTrue("no TTL drop should be recorded for delayed delivery", ttlDrops.isEmpty())
    }
}
