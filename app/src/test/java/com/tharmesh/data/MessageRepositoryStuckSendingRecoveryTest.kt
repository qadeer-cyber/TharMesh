package com.tharmesh.data

import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.MessageEntity
import com.tharmesh.dtn.RetryConfig
import com.tharmesh.dtn.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Stage 5.2 — exercises [runRetryTickStandalone] (the testable shape of the
 * MessageRepository retry-tick body) against deterministic pending lists.
 * The standalone function lives at file level on [MessageRepository.kt] so
 * tests don't need to instantiate a RoomDatabase.
 *
 * Asserts:
 *  - SENDING rows fire the stuck-recovered hook AND retry.
 *  - QUEUED rows retry without firing the stuck-recovered hook.
 *  - The retry hook fires once per re-broadcast.
 *  - The per-bundle backoff window is respected on subsequent ticks.
 *  - bundleId-less rows are silently skipped.
 *  - Crash-recovery: the very first tick after process restart sees the
 *    SENDING row and re-issues it.
 */
class MessageRepositoryStuckSendingRecoveryTest {

    private fun row(
        id: Long,
        bundleId: String?,
        status: String,
        from: String = "alice",
        to: String = "bob"
    ) = MessageEntity(
        id = id,
        fromUserId = from,
        toUserId = to,
        peerUserId = to,
        body = "x",
        status = status,
        timestamp = 0L,
        bundleId = bundleId
    )

    private fun newPolicy(cfg: RetryConfig = RetryConfig.DEFAULT.copy(jitterFraction = 0.0)) =
        RetryPolicy(cfg, rand = { 0.5 })

    @Test
    fun stuckSendingRow_isReBroadcastAndFiresHook() {
        val stuck = mutableListOf<String>()
        val retried = mutableListOf<String>()
        val rebroadcast = mutableListOf<String>()
        val policy = newPolicy()
        runRetryTickStandalone(
            nowMs = 0L,
            pending = listOf(row(1, "stuck-1", MessageStatus.SENDING)),
            retryPolicy = policy,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { stuck.add(it) },
            retryBundle = { rebroadcast.add(it); true }
        )
        assertEquals(listOf("stuck-1"), stuck)
        assertEquals(listOf("stuck-1"), retried)
        assertEquals(listOf("stuck-1"), rebroadcast)
        assertEquals(1, policy.currentState("stuck-1")?.attemptCount)
    }

    @Test
    fun queuedRow_retriesWithoutFiringStuckRecoveryHook() {
        val stuck = mutableListOf<String>()
        val retried = mutableListOf<String>()
        runRetryTickStandalone(
            nowMs = 0L,
            pending = listOf(row(2, "fresh-1", MessageStatus.QUEUED)),
            retryPolicy = newPolicy(),
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { stuck.add(it) },
            retryBundle = { true }
        )
        assertTrue(stuck.isEmpty())
        assertEquals(listOf("fresh-1"), retried)
    }

    @Test
    fun perBundleBackoffSkipsRetryUntilWindowElapses() {
        val retried = mutableListOf<String>()
        val cfg = RetryConfig.DEFAULT.copy(jitterFraction = 0.0)
        val policy = RetryPolicy(cfg, rand = { 0.5 })
        val pending = listOf(row(3, "bo-1", MessageStatus.QUEUED))
        // Tick 1 at t=0 → eligible (first attempt).
        runRetryTickStandalone(
            nowMs = 0L, pending = pending, retryPolicy = policy,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { true }
        )
        assertEquals(1, retried.size)
        // Next eligible time is t=5000 (base delay, jitter=0). Tick at t=4999 → no-op.
        runRetryTickStandalone(
            nowMs = 4_999L, pending = pending, retryPolicy = policy,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { true }
        )
        assertEquals(1, retried.size)
        // Tick at t=5000 → second attempt fires.
        runRetryTickStandalone(
            nowMs = 5_000L, pending = pending, retryPolicy = policy,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { true }
        )
        assertEquals(2, retried.size)
    }

    @Test
    fun rowsWithoutBundleIdAreSkipped() {
        val retried = mutableListOf<String>()
        runRetryTickStandalone(
            nowMs = 0L,
            pending = listOf(row(4, bundleId = null, status = MessageStatus.QUEUED)),
            retryPolicy = newPolicy(),
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { true }
        )
        assertTrue(retried.isEmpty())
    }

    @Test
    fun multipleRowsAreRetriedInOrder_perBundleBackoffStateIsIndependent() {
        val retried = mutableListOf<String>()
        val stuck = mutableListOf<String>()
        runRetryTickStandalone(
            nowMs = 0L,
            pending = listOf(
                row(5, "m1", MessageStatus.QUEUED),
                row(6, "m2", MessageStatus.SENDING),
                row(7, "m3", MessageStatus.QUEUED)
            ),
            retryPolicy = newPolicy(),
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { stuck.add(it) },
            retryBundle = { true }
        )
        assertEquals(listOf("m1", "m2", "m3"), retried)
        assertEquals(listOf("m2"), stuck)
    }

    @Test
    fun emptyPendingList_doesNothing() {
        val retried = mutableListOf<String>()
        runRetryTickStandalone(
            nowMs = 0L,
            pending = emptyList(),
            retryPolicy = newPolicy(),
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { true }
        )
        assertTrue(retried.isEmpty())
    }

    @Test
    fun stuckSendingRecovered_increments_acrossMultipleTicks() {
        val stuckCount = AtomicLong(0L)
        val cfg = RetryConfig.DEFAULT.copy(jitterFraction = 0.0)
        val policy = RetryPolicy(cfg, rand = { 0.5 })
        // 3 ticks; each spaced far apart so the per-bundle backoff doesn't gate any.
        runRetryTickStandalone(
            nowMs = 0L,
            pending = listOf(row(10, "a", MessageStatus.SENDING)),
            retryPolicy = policy,
            onRetryAttempt = { },
            onStuckSendingRecovered = { stuckCount.incrementAndGet() },
            retryBundle = { true }
        )
        runRetryTickStandalone(
            nowMs = 100_000L,
            pending = listOf(
                row(10, "a", MessageStatus.QUEUED),  // not stuck this tick
                row(11, "b", MessageStatus.SENDING)
            ),
            retryPolicy = policy,
            onRetryAttempt = { },
            onStuckSendingRecovered = { stuckCount.incrementAndGet() },
            retryBundle = { true }
        )
        runRetryTickStandalone(
            nowMs = 1_000_000L,
            pending = listOf(row(11, "b", MessageStatus.SENDING)),
            retryPolicy = policy,
            onRetryAttempt = { },
            onStuckSendingRecovered = { stuckCount.incrementAndGet() },
            retryBundle = { true }
        )
        assertEquals(3L, stuckCount.get())
    }

    @Test
    fun crashRecovery_sendingRowAfterRestart_isReBroadcastOnFirstTick() {
        // Field-test scenario: process death between Transport.send() and
        // PayloadSent leaves a row in SENDING. After restart, in-memory state
        // (RetryPolicy + retry counters) is fresh, but the DB still carries
        // the SENDING row. The first retry tick must:
        //  1) detect the stuck row (fire onStuckSendingRecovered),
        //  2) re-issue the bundle (retryBundle lambda invoked),
        //  3) record the attempt in the new policy (so subsequent ticks back off).
        val stuck = AtomicLong(0L)
        val retried = AtomicLong(0L)
        val rebroadcast = mutableListOf<String>()
        val policy = newPolicy()  // fresh — emulates post-restart state
        runRetryTickStandalone(
            nowMs = 0L,
            pending = listOf(row(99, "x", MessageStatus.SENDING)),
            retryPolicy = policy,
            onRetryAttempt = { retried.incrementAndGet() },
            onStuckSendingRecovered = { stuck.incrementAndGet() },
            retryBundle = { rebroadcast.add(it); true }
        )
        assertEquals(1L, stuck.get())
        assertEquals(1L, retried.get())
        assertEquals(listOf("x"), rebroadcast)
        assertNotNull(policy.currentState("x"))
        assertEquals(1, policy.currentState("x")?.attemptCount)
    }

    /**
     * Regression for the Devin Review finding on PR #10: when retryBundle is a
     * no-op (TTL expired in the engine cache), the tick must NOT advance the
     * retryAttempts counter, must NOT call recordAttempt, and must call
     * onTtlExpired so the policy state map can be freed. Without this, every
     * eligible tick on a stuck-expired row would keep firing the diagnostic
     * and keep growing the policy map.
     */
    @Test
    fun ttlExpiredRetry_doesNotInflateCounters_andFreesPolicyState() {
        val retried = AtomicLong(0L)
        val stuck = AtomicLong(0L)
        val policy = newPolicy()
        // First tick: pretend the bundle is fresh — record an attempt so the
        // policy map has state for this bid.
        runRetryTickStandalone(
            nowMs = 0L,
            pending = listOf(row(20, "ttl-1", MessageStatus.QUEUED)),
            retryPolicy = policy,
            onRetryAttempt = { retried.incrementAndGet() },
            onStuckSendingRecovered = { stuck.incrementAndGet() },
            retryBundle = { true }
        )
        assertEquals(1L, retried.get())
        assertNotNull(policy.currentState("ttl-1"))

        // Second tick: simulate TTL expiry by returning false from retryBundle.
        // Counters must NOT advance, policy state must be freed.
        runRetryTickStandalone(
            nowMs = 100_000L,
            pending = listOf(row(20, "ttl-1", MessageStatus.QUEUED)),
            retryPolicy = policy,
            onRetryAttempt = { retried.incrementAndGet() },
            onStuckSendingRecovered = { stuck.incrementAndGet() },
            retryBundle = { false }
        )
        assertEquals("retryAttempts must not increment on TTL no-op", 1L, retried.get())
        assertEquals("stuckSendingRecovered must not fire on TTL no-op", 0L, stuck.get())
        assertEquals(
            "policy state must be freed after TTL expiry",
            null,
            policy.currentState("ttl-1")
        )
    }

    // ---------- No-connected-peers suppression ----------

    @Test
    fun noConnectedPeers_suppressesRetry_andDoesNotTouchPolicyState() {
        val retried = AtomicLong(0L)
        val rebroadcast = mutableListOf<String>()
        val suppressed = mutableListOf<String>()
        val policy = newPolicy()
        runRetryTickStandalone(
            nowMs = 0L,
            pending = listOf(row(40, "off-1", MessageStatus.QUEUED)),
            retryPolicy = policy,
            onRetryAttempt = { retried.incrementAndGet() },
            onStuckSendingRecovered = { },
            retryBundle = { rebroadcast.add(it); true },
            hasConnectedPeers = { false },
            onRetrySuppressedNoPeers = { suppressed.add(it) }
        )
        // No re-broadcast was attempted, no retry was recorded, no policy state
        // was created. The suppression hook fired exactly once with the bundleId.
        assertEquals(0L, retried.get())
        assertTrue(rebroadcast.isEmpty())
        assertEquals(listOf("off-1"), suppressed)
        assertEquals(
            "policy state must be untouched during offline suppression",
            null,
            policy.currentState("off-1")
        )
    }

    @Test
    fun noConnectedPeers_doesNotAdvanceBackoff_acrossMultipleTicks() {
        // Regression: before this fix, every offline tick advanced the retry
        // counter and consumed the backoff curve — by the time the peer
        // returned, nextRetryAt could be pinned at the maxDelay ceiling for
        // minutes before any send was re-attempted. This test asserts the
        // curve is preserved across an outage.
        val cfg = RetryConfig.DEFAULT.copy(jitterFraction = 0.0)
        val policy = RetryPolicy(cfg, rand = { 0.5 })
        val pending = listOf(row(41, "off-2", MessageStatus.QUEUED))

        // Tick 1 at t=0 with a peer present → seeds policy state (attempt=1, next=5000).
        runRetryTickStandalone(
            nowMs = 0L, pending = pending, retryPolicy = policy,
            onRetryAttempt = { }, onStuckSendingRecovered = { },
            retryBundle = { true },
            hasConnectedPeers = { true }
        )
        val afterSeed = policy.currentState("off-2")!!
        assertEquals(1, afterSeed.attemptCount)
        assertEquals(5_000L, afterSeed.nextRetryAt)

        // Ticks 2..5 at 60s intervals with NO peers — policy state must be frozen.
        for (t in longArrayOf(60_000L, 120_000L, 180_000L, 240_000L)) {
            runRetryTickStandalone(
                nowMs = t, pending = pending, retryPolicy = policy,
                onRetryAttempt = { }, onStuckSendingRecovered = { },
                retryBundle = { true },
                hasConnectedPeers = { false }
            )
        }
        val afterOutage = policy.currentState("off-2")!!
        assertEquals(
            "attemptCount must not advance during offline period",
            1, afterOutage.attemptCount
        )
        assertEquals(
            "nextRetryAt must not advance during offline period",
            5_000L, afterOutage.nextRetryAt
        )
    }

    @Test
    fun noConnectedPeers_respectsShouldAttemptGate_doesNotFireSuppressionDuringBackoff() {
        // While the per-bundle backoff window is still open (shouldAttempt
        // returns false), the offline-suppression hook must NOT fire either —
        // there was no retry to suppress. Only bundles the tick would have
        // otherwise retried should count toward retrySuppressedNoPeers.
        val cfg = RetryConfig.DEFAULT.copy(jitterFraction = 0.0)
        val policy = RetryPolicy(cfg, rand = { 0.5 })
        val suppressed = mutableListOf<String>()
        val pending = listOf(row(42, "off-3", MessageStatus.QUEUED))

        // Seed attempt at t=0 with peer → next eligible at t=5000.
        runRetryTickStandalone(
            nowMs = 0L, pending = pending, retryPolicy = policy,
            onRetryAttempt = { }, onStuckSendingRecovered = { },
            retryBundle = { true },
            hasConnectedPeers = { true }
        )
        // Tick at t=2500 (backoff not elapsed) with NO peers — no suppression fired.
        runRetryTickStandalone(
            nowMs = 2_500L, pending = pending, retryPolicy = policy,
            onRetryAttempt = { }, onStuckSendingRecovered = { },
            retryBundle = { true },
            hasConnectedPeers = { false },
            onRetrySuppressedNoPeers = { suppressed.add(it) }
        )
        assertTrue(
            "suppression must not fire when shouldAttempt would have blocked",
            suppressed.isEmpty()
        )
    }

    @Test
    fun onTtlClearCallback_firesOnceWithBundleId_whenRetryReturnsFalse() {
        // Stage 5.3 follow-up — verifies the new onTtlClear hook on
        // runRetryTickStandalone fires on the no-op path so the repository can
        // drop SOS priority tracking alongside the retry-policy state.
        val cleared = mutableListOf<String>()
        val policy = newPolicy()
        // Seed policy state.
        runRetryTickStandalone(
            nowMs = 0L,
            pending = listOf(row(30, "p1", MessageStatus.QUEUED)),
            retryPolicy = policy,
            onRetryAttempt = { },
            onStuckSendingRecovered = { },
            retryBundle = { true }
        )
        assertTrue("onTtlClear must NOT fire on the success path", cleared.isEmpty())

        // TTL no-op tick.
        runRetryTickStandalone(
            nowMs = 100_000L,
            pending = listOf(row(30, "p1", MessageStatus.QUEUED)),
            retryPolicy = policy,
            onRetryAttempt = { },
            onStuckSendingRecovered = { },
            retryBundle = { false },
            onTtlClear = { cleared.add(it) }
        )
        assertEquals(listOf("p1"), cleared)
    }

    @Test
    fun persistenceHooks_fireAfterStateChanges() {
        val policy = newPolicy()
        val persisted = mutableListOf<String>()
        val forgotten = mutableListOf<String>()

        // Success path: onStatePersisted fires AFTER recordAttempt,
        // onStateForgotten does not fire.
        runRetryTickStandalone(
            nowMs = 0L,
            pending = listOf(row(40, "p1", MessageStatus.QUEUED)),
            retryPolicy = policy,
            onRetryAttempt = { },
            onStuckSendingRecovered = { },
            retryBundle = { true },
            onStatePersisted = { persisted.add(it) },
            onStateForgotten = { forgotten.add(it) }
        )
        assertEquals(listOf("p1"), persisted)
        assertTrue("onStateForgotten must NOT fire on the success path", forgotten.isEmpty())
        // State exists in the policy at the moment the hook fires — verify
        // that contract by checking the current policy snapshot now.
        assertEquals(1, policy.currentState("p1")?.attemptCount)

        // TTL-expiry path: onStateForgotten fires AFTER onTtlExpired,
        // onStatePersisted does not fire.
        runRetryTickStandalone(
            nowMs = 100_000L,
            pending = listOf(row(40, "p1", MessageStatus.QUEUED)),
            retryPolicy = policy,
            onRetryAttempt = { },
            onStuckSendingRecovered = { },
            retryBundle = { false },
            onStatePersisted = { persisted.add(it) },
            onStateForgotten = { forgotten.add(it) }
        )
        assertEquals(listOf("p1"), forgotten)
        assertEquals("no extra persist on TTL tick", 1, persisted.size)
    }
}
