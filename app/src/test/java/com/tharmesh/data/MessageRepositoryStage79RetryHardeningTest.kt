package com.tharmesh.data

import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.MessageEntity
import com.tharmesh.dtn.RetryConfig
import com.tharmesh.dtn.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 7.9 — locks in the retry-tick hardening contract:
 *
 *  - When [retryBundle] returns false because the bundle is in cache but
 *    terminally non-deliverable (TTL expired, already DELIVERED_FINAL),
 *    [runRetryTickStandalone] frees per-bundle policy state, fires
 *    `onTtlClear` / `onStateForgotten`, and the row is left alone on
 *    subsequent ticks.
 *
 *  - When [retryBundle] returns false because the bundle is NOT in cache
 *    (LRU-evicted, or a brand-new cold start that has not yet rehydrated
 *    BundleStore), the tick MUST preserve per-bundle policy state so the
 *    next tick can re-issue. The legacy code freed state in this case
 *    too, which left the message row stuck QUEUED and lost the SOS
 *    priority bit + offline-queue tracker entry.
 *
 *  Pure unit test — no DB, no MeshEngine, no transport. Drives
 *  [runRetryTickStandalone] directly.
 */
class MessageRepositoryStage79RetryHardeningTest {

    private fun row(id: Long, bid: String, status: String) = MessageEntity(
        id = id,
        fromUserId = "alice",
        toUserId = "bob",
        peerUserId = "bob",
        body = "x",
        status = status,
        timestamp = 0L,
        bundleId = bid
    )

    private fun policy(cfg: RetryConfig = RetryConfig.DEFAULT.copy(jitterFraction = 0.0)) =
        RetryPolicy(cfg, rand = { 0.5 })

    @Test
    fun cacheMiss_preservesPolicyState_andDoesNotFireTtlHooks() {
        // Seed the policy with a known state — first attempt at t=0 places
        // nextRetryAt at 5 000 ms (DEFAULT base delay, jitter=0). At t=10s
        // the row IS eligible for retry, but retryBundle returns false
        // because the bundle is missing from cache (simulated LRU eviction
        // or cold-start before rehydration).
        val p = policy()
        // Seed the policy as if we'd already attempted once (i.e. the
        // bundle was originally enqueued, sent, then the cache evicted it).
        p.markOriginated("evicted-1", 0L, null)
        val ttlCleared = mutableListOf<String>()
        val stateForgotten = mutableListOf<String>()
        val retried = mutableListOf<String>()
        runRetryTickStandalone(
            nowMs = 10_000L,
            pending = listOf(row(1L, "evicted-1", MessageStatus.QUEUED)),
            retryPolicy = p,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { false },               // retryBundle returns false
            onTtlClear = { ttlCleared.add(it) },
            onStateForgotten = { stateForgotten.add(it) },
            hasCachedBundle = { false }            // ...because cache miss
        )
        assertTrue(retried.isEmpty())
        // Policy state preserved: priority/offline/backoff bookkeeping
        // intact for the next tick.
        assertNotNull("Cache-miss tick must NOT free policy state", p.currentState("evicted-1"))
        assertTrue("onTtlClear must NOT fire on cache miss", ttlCleared.isEmpty())
        assertTrue("onStateForgotten must NOT fire on cache miss", stateForgotten.isEmpty())
    }

    @Test
    fun terminalFailure_freesPolicyState_andFiresTtlHooks() {
        // Same shape as above, but retryBundle returns false WITH a cache
        // hit — this is the legitimate terminal-condition path (TTL
        // expired or already DELIVERED_FINAL inside the engine).
        val p = policy()
        p.markOriginated("expired-1", 0L, null)
        val ttlCleared = mutableListOf<String>()
        val stateForgotten = mutableListOf<String>()
        val retried = mutableListOf<String>()
        runRetryTickStandalone(
            nowMs = 10_000L,
            pending = listOf(row(2L, "expired-1", MessageStatus.QUEUED)),
            retryPolicy = p,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { false },
            onTtlClear = { ttlCleared.add(it) },
            onStateForgotten = { stateForgotten.add(it) },
            hasCachedBundle = { true }             // cached but terminal
        )
        assertTrue(retried.isEmpty())
        assertNull("Terminal failure tick must free policy state", p.currentState("expired-1"))
        assertEquals(listOf("expired-1"), ttlCleared)
        assertEquals(listOf("expired-1"), stateForgotten)
    }

    @Test
    fun cacheMissTick_followedByCacheHitTick_recoversTheRow() {
        // Models the canonical process-death scenario: the very first tick
        // after restart sees the QUEUED row but the cache has not been
        // rehydrated yet (e.g. MeshEngine.start hasn't run). The tick must
        // be a no-op. The next tick (after rehydration) must successfully
        // re-broadcast.
        val p = policy()
        p.markOriginated("recovered-1", 0L, null)
        val retried = mutableListOf<String>()
        var cached = false  // simulates rehydration completing between ticks

        // Tick 1: pre-rehydration. retryBundle returns false (cache miss).
        runRetryTickStandalone(
            nowMs = 10_000L,
            pending = listOf(row(3L, "recovered-1", MessageStatus.QUEUED)),
            retryPolicy = p,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { false },
            hasCachedBundle = { cached }
        )
        assertTrue("Pre-rehydration tick must skip", retried.isEmpty())
        assertNotNull(p.currentState("recovered-1"))

        // Tick 2: post-rehydration. retryBundle returns true (re-broadcast
        // succeeds against the now-cached bundle).
        cached = true
        runRetryTickStandalone(
            nowMs = 11_000L,
            pending = listOf(row(3L, "recovered-1", MessageStatus.QUEUED)),
            retryPolicy = p,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { true },
            hasCachedBundle = { true }
        )
        assertEquals(listOf("recovered-1"), retried)
    }
}
