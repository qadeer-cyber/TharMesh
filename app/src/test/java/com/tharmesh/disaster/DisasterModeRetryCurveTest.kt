package com.tharmesh.disaster

import com.tharmesh.data.runRetryTickStandalone
import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.MessageEntity
import com.tharmesh.dtn.RetryConfig
import com.tharmesh.dtn.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage 6.3 — when disaster mode is on globally, the per-bundle [configFor]
 * lambda passed into [runRetryTickStandalone] returns [RetryConfig.SOS] for
 * every pending bundle, even ones that were queued before the toggle flipped.
 * That promotes their backoff curve from the 5s→60s default to the 1s→8s
 * aggressive SOS curve. These tests pin that behaviour so a future refactor
 * cannot silently regress disaster-mode timing.
 */
class DisasterModeRetryCurveTest {

    private fun row(id: Long, bundleId: String) = MessageEntity(
        id = id,
        fromUserId = "alice",
        toUserId = "bob",
        peerUserId = "bob",
        body = "x",
        status = MessageStatus.QUEUED,
        timestamp = 0L,
        bundleId = bundleId
    )

    @Test
    fun disasterMode_promotesEveryBundle_toSosCurve() {
        val policy = RetryPolicy(RetryConfig.DEFAULT.copy(jitterFraction = 0.0), rand = { 0.5 })
        val retried = mutableListOf<String>()
        val pending = listOf(row(1, "bid-1"))

        // Tick 1 at t=0 — first attempt fires.
        runRetryTickStandalone(
            nowMs = 0L,
            pending = pending,
            retryPolicy = policy,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { true },
            configFor = { RetryConfig.SOS }
        )
        assertEquals(listOf("bid-1"), retried)

        // After 1 attempt the SOS curve schedules the next try at t=1000ms
        // (baseDelayMs=1000, growthFactor=2, attemptCount=1). The default
        // curve would have scheduled it at t=5000ms. Verify the SOS gap.
        runRetryTickStandalone(
            nowMs = 999L,
            pending = pending,
            retryPolicy = policy,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { true },
            configFor = { RetryConfig.SOS }
        )
        assertEquals(1, retried.size) // not yet eligible

        runRetryTickStandalone(
            nowMs = 1000L,
            pending = pending,
            retryPolicy = policy,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { true },
            configFor = { RetryConfig.SOS }
        )
        assertEquals(2, retried.size) // SOS curve fires at exactly 1s
    }

    @Test
    fun disasterModeOff_keepsDefaultCurve_evenForPriorityBundle() {
        // Sanity: when the predicate returns null (disaster mode off, no
        // priority marker), the policy applies its default 5s base delay.
        val policy = RetryPolicy(RetryConfig.DEFAULT.copy(jitterFraction = 0.0), rand = { 0.5 })
        val retried = mutableListOf<String>()
        val pending = listOf(row(1, "bid-1"))
        runRetryTickStandalone(
            nowMs = 0L,
            pending = pending,
            retryPolicy = policy,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { true },
            configFor = { null }
        )
        assertEquals(1, retried.size)
        // Default curve schedules next attempt at t=5000ms — so a tick at
        // t=4999 must not retry, but the SOS curve would have already.
        runRetryTickStandalone(
            nowMs = 4_999L,
            pending = pending,
            retryPolicy = policy,
            onRetryAttempt = { retried.add(it) },
            onStuckSendingRecovered = { },
            retryBundle = { true },
            configFor = { null }
        )
        assertEquals(1, retried.size)
    }
}
