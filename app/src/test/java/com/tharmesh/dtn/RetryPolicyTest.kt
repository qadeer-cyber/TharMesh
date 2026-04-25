package com.tharmesh.dtn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 5.2 — unit tests for the per-bundle retry decision maker. Pure Kotlin;
 * no Android, no Robolectric, no coroutines. Jitter is pinned to 0 so the
 * curve is deterministic, and a separate test exercises bounded jitter using
 * a deterministic random source.
 */
class RetryPolicyTest {

    private fun policyNoJitter(config: RetryConfig = RetryConfig.DEFAULT.copy(jitterFraction = 0.0)) =
        RetryPolicy(config = config, rand = { 0.5 })

    @Test
    fun firstShouldAttemptIsTrueWhenStateIsEmpty() {
        val p = policyNoJitter()
        assertTrue(p.shouldAttempt("b1", nowMs = 100L))
    }

    @Test
    fun backoffCurveProgresses5_10_20_40_60_60() {
        val cfg = RetryConfig.DEFAULT.copy(
            baseDelayMs = 5_000L,
            maxDelayMs = 60_000L,
            growthFactor = 2.0,
            jitterFraction = 0.0
        )
        val p = RetryPolicy(config = cfg, rand = { 0.5 })
        // attempt N → expected delay
        val expected = listOf(
            1 to 5_000L,
            2 to 10_000L,
            3 to 20_000L,
            4 to 40_000L,
            5 to 60_000L,  // clamped
            6 to 60_000L,
            12 to 60_000L
        )
        for ((attempt, want) in expected) {
            assertEquals("attempt=$attempt", want, p.computeDelayMs(attempt))
        }
    }

    @Test
    fun jitterIsBoundedByConfiguredFraction() {
        val cfg = RetryConfig.DEFAULT.copy(jitterFraction = 0.20)
        // Sweep rand() across [0,1] and assert delay stays within ±20% of base.
        val samples = listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0)
        for (r in samples) {
            val p = RetryPolicy(config = cfg, rand = { r })
            val d = p.computeDelayMs(1)
            val lo = (cfg.baseDelayMs * 0.80).toLong()
            val hi = (cfg.baseDelayMs * 1.20).toLong()
            assertTrue("rand=$r delay=$d must be >= $lo", d >= lo)
            assertTrue("rand=$r delay=$d must be <= $hi", d <= hi)
        }
    }

    @Test
    fun recordAttemptAdvancesNextRetryAt() {
        val p = policyNoJitter()
        val t0 = 1_000L
        p.recordAttempt("b1", t0)
        val s1 = p.currentState("b1")!!
        assertEquals(1, s1.attemptCount)
        // next retry = t0 + base (5000) since jitter=0
        assertEquals(t0 + 5_000L, s1.nextRetryAt)
        // Before nextRetryAt → not eligible
        assertFalse(p.shouldAttempt("b1", t0 + 4_999L))
        assertTrue(p.shouldAttempt("b1", t0 + 5_000L))
    }

    @Test
    fun maxIntervalNeverExceedsCap() {
        val cfg = RetryConfig.DEFAULT.copy(jitterFraction = 0.0)
        val p = RetryPolicy(config = cfg, rand = { 0.5 })
        // Run many attempts and assert the curve stays clamped at maxDelayMs.
        for (i in 1..50) {
            val d = p.computeDelayMs(i)
            assertTrue("attempt=$i delay=$d > cap", d <= cfg.maxDelayMs)
        }
    }

    @Test
    fun onPeerConnectedBypassResetsAllAttempts() {
        val p = policyNoJitter()
        p.recordAttempt("b1", 1_000L)
        p.recordAttempt("b1", 6_000L)
        p.recordAttempt("b2", 1_000L)
        assertEquals(2, p.currentState("b1")!!.attemptCount)
        assertEquals(1, p.currentState("b2")!!.attemptCount)
        val reset = p.onPeerConnectedBypass(nowMs = 10_000L)
        assertEquals(2, reset)
        // After bypass: attemptCount=0, nextRetryAt=now (so eligible immediately)
        val s1 = p.currentState("b1")!!
        assertEquals(0, s1.attemptCount)
        assertEquals(10_000L, s1.nextRetryAt)
        assertTrue(p.shouldAttempt("b1", 10_000L))
        assertTrue(p.shouldAttempt("b2", 10_000L))
    }

    @Test
    fun onDeliveredAndOnTtlExpiredFreeState() {
        val p = policyNoJitter()
        p.recordAttempt("b1", 1_000L)
        p.recordAttempt("b2", 1_000L)
        assertNotNull(p.currentState("b1"))
        assertNotNull(p.currentState("b2"))
        p.onDelivered("b1")
        p.onTtlExpired("b2")
        assertNull(p.currentState("b1"))
        assertNull(p.currentState("b2"))
        assertEquals(0, p.trackedBundleCount())
    }

    @Test
    fun attemptsTotalCountsCumulativeAcrossBundles() {
        val p = policyNoJitter()
        p.recordAttempt("a", 0L)
        p.recordAttempt("a", 6_000L)
        p.recordAttempt("b", 0L)
        assertEquals(3L, p.attemptsTotal())
    }

    @Test
    fun thereIsNoMaxAttemptCap_retriesContinueIndefinitely() {
        // TharMesh is delay-tolerant — retries stop only on delivery / TTL,
        // never on attempt count. Verify the policy does not flag any
        // terminal state regardless of how many attempts are recorded.
        val p = policyNoJitter()
        var t = 0L
        for (i in 1..1_000) {
            p.recordAttempt("b", t)
            t = p.currentState("b")!!.nextRetryAt + 1
            // shouldAttempt at t must always return true — there is no cap.
            assertTrue("attempt #$i must remain eligible", p.shouldAttempt("b", t))
        }
        assertEquals(1_000, p.currentState("b")!!.attemptCount)
    }

    // ---------- Stage 5.3 — configOverride for SOS / Field Test curves ----------

    @Test
    fun computeDelayMs_overload_appliesProvidedConfigCurve() {
        val p = RetryPolicy(config = RetryConfig.DEFAULT.copy(jitterFraction = 0.0), rand = { 0.5 })
        // Default curve at attempt 3 → 20s; SOS curve at attempt 3 → 4s.
        assertEquals(20_000L, p.computeDelayMs(3))
        assertEquals(4_000L, p.computeDelayMs(3, RetryConfig.SOS))
    }

    @Test
    fun recordAttempt_withSOSConfigOverride_usesAggressiveCurve() {
        val p = policyNoJitter()
        val t0 = 0L
        // First attempt with SOS override should schedule next retry at t0 + 1000ms
        // (vs. 5000ms with the default curve).
        p.recordAttempt("sos1", t0, RetryConfig.SOS)
        val s = p.currentState("sos1")!!
        assertEquals(1, s.attemptCount)
        assertEquals(t0 + 1_000L, s.nextRetryAt)
        // Second SOS attempt → 2s.
        p.recordAttempt("sos1", s.nextRetryAt, RetryConfig.SOS)
        val s2 = p.currentState("sos1")!!
        assertEquals(s.nextRetryAt + 2_000L, s2.nextRetryAt)
    }

    @Test
    fun sosCurveCapsAt8s() {
        val p = RetryPolicy(config = RetryConfig.DEFAULT, rand = { 0.5 })
        // attempt N → expected delay under SOS curve
        val expected = listOf(
            1 to 1_000L,
            2 to 2_000L,
            3 to 4_000L,
            4 to 8_000L,
            5 to 8_000L,  // clamped
            10 to 8_000L
        )
        for ((attempt, want) in expected) {
            assertEquals("SOS attempt=$attempt", want, p.computeDelayMs(attempt, RetryConfig.SOS))
        }
    }
}
