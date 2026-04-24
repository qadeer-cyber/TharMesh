package com.tharmesh.dtn

import com.tharmesh.transport.Transport
import com.tharmesh.transport.TransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in Phase B fixes:
 *  - TTL / hop enforcement at handleBundle time (drop expired, drop zero-hop).
 *  - Cache size cap with LRU eviction.
 */
class MeshEngineTtlHopsCacheTest {

    private class SilentTransport : Transport {
        private var listener: ((TransportEvent) -> Unit)? = null
        override fun start(localPeerId: String) {}
        override fun stop() {}
        override fun send(peerId: String, payload: ByteArray, sendId: Long): Boolean = true
        override fun setListener(listener: (TransportEvent) -> Unit) { this.listener = listener }
        fun fire(event: TransportEvent) { listener?.invoke(event) }
    }

    @Test
    fun cache_evictsOldestOverCap() {
        // Use relayed (non-local) bundles so they are not protected by the
        // "never evict local undelivered outbound" invariant. The eviction
        // loop is allowed to drop these to stay within maxCacheSize.
        val transport = SilentTransport()
        val engine = MeshEngine(localUserId = "me", transport = transport, maxCacheSize = 3)
        repeat(5) { i ->
            engine.cachePut(
                MeshBundle(
                    bundleId = "b$i",
                    srcId = "other",
                    destId = "someone-else",
                    payloadCiphertext = "body$i",
                    ttlUntil = System.currentTimeMillis() + 60_000L,
                    hopsLeft = 4,
                    signature = "sig",
                    status = "FORWARDED"
                )
            )
        }
        assertEquals("Cache capped at maxCacheSize", 3, engine.cacheSize())
    }

    @Test
    fun cache_doesNotEvictLocalUndeliveredOutbound() {
        // All 5 bundles originate from localUserId and are QUEUED — the retry loop
        // still needs them, so the cache is allowed to temporarily exceed the cap
        // rather than drop pending outbound traffic on the floor.
        val transport = SilentTransport()
        val engine = MeshEngine(localUserId = "me", transport = transport, maxCacheSize = 2)
        repeat(5) { i ->
            engine.queueText(
                destId = "peer$i",
                payloadCiphertext = "body$i",
                ttlMs = 60_000L,
                hops = 4
            )
        }
        assertEquals("Local undelivered outbound is pinned", 5, engine.cacheSize())
    }

    @Test
    fun queueText_clampsNegativeHops() {
        val transport = SilentTransport()
        val engine = MeshEngine(localUserId = "me", transport = transport)
        val bundle = engine.queueText(
            destId = "peer",
            payloadCiphertext = "hi",
            ttlMs = 60_000L,
            hops = -5
        )
        assertEquals(0, bundle.hopsLeft)
    }

    @Test
    fun retryBundle_skipsExpiredTtl() {
        var clock = 1_000L
        val transport = SilentTransport()
        val engine = MeshEngine(
            localUserId = "me",
            transport = transport,
            now = { clock }
        )
        val bundle = engine.queueText(
            destId = "peer",
            payloadCiphertext = "hi",
            ttlMs = 500L,
            hops = 4
        )
        // Fast-forward past TTL. retryBundle should be a no-op now.
        clock = 100_000L
        val eventsBefore = mutableListOf<MeshEvent>()
        engine.setEventListener { eventsBefore.add(it) }
        engine.retryBundle(bundle.bundleId)
        assertTrue("Expired retry must not emit any event", eventsBefore.isEmpty())
    }
}
