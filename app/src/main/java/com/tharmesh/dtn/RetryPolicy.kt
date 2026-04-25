package com.tharmesh.dtn

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Stage 5.2 — per-bundle retry decision maker. Pure Kotlin; no Android, no
 * coroutines, no threads of its own. Caller serialises by holding [lock] over
 * its `should/record` calls if needed; individual ops are already synchronised.
 *
 * Contract:
 *  - [shouldAttempt] returns true when the per-bundle [BundleState.nextRetryAt]
 *    has elapsed (relative to the supplied `nowMs`). First call for an unseen
 *    bundleId always returns true (no warm-up delay on the first attempt —
 *    that's the caller's responsibility, e.g. immediate broadcast on
 *    origination).
 *  - [recordAttempt] increments the attemptCount and schedules the next-eligible
 *    retry timestamp using the [RetryConfig] curve plus symmetric jitter.
 *  - [onDelivered] / [onTtlExpired] free per-bundle state when the bundle
 *    leaves the retry pool (delivered/read/expired).
 *  - [onPeerConnectedBypass] resets every bundle's attemptCount to 0 — the
 *    topology changed, so backoff on stale state is no longer informative.
 *    The retry loop will re-issue these bundles on the next tick at the
 *    base delay.
 *
 * There is no max-attempt cap by design — TharMesh is delay-tolerant. Retries
 * end only when the bundle is delivered / read OR the bundle's TTL expires
 * (the latter is enforced upstream in [MeshEngine.broadcastBundle] /
 * [MessageRepository.startStoreAndForwardLoop]).
 */
class RetryPolicy(
    val config: RetryConfig = RetryConfig.DEFAULT,
    /**
     * Random source for jitter. Defaults to [Math.random]; tests inject a
     * deterministic seed for reproducibility.
     */
    private val rand: () -> Double = { Math.random() }
) {

    /**
     * Per-bundle retry state. `attemptCount` is the number of times the retry
     * loop has issued a re-broadcast for this bundleId. `nextRetryAt` is the
     * earliest absolute timestamp at which the next attempt is eligible.
     */
    data class BundleState(
        val attemptCount: Int,
        val nextRetryAt: Long
    )

    private val lock = Any()
    private val state: MutableMap<String, BundleState> = HashMap()

    // Diagnostic counter (totals across all bundleIds, never decremented).
    private val attemptsTotal = AtomicLong(0L)

    fun attemptsTotal(): Long = attemptsTotal.get()

    /** Test/diagnostics introspection — current state for [bundleId], or null if unknown. */
    fun currentState(bundleId: String): BundleState? = synchronized(lock) { state[bundleId] }

    /** Test/diagnostics introspection — number of bundles with retry state. */
    fun trackedBundleCount(): Int = synchronized(lock) { state.size }

    /**
     * Returns true iff [bundleId] is eligible for a retry at [nowMs]. An unseen
     * bundleId is always eligible (caller is expected to immediately broadcast
     * on origination; this method is called by the retry loop, not the
     * origination path).
     */
    fun shouldAttempt(bundleId: String, nowMs: Long): Boolean = synchronized(lock) {
        val s = state[bundleId] ?: return@synchronized true
        nowMs >= s.nextRetryAt
    }

    /**
     * Record that the retry loop has re-broadcast [bundleId] at [nowMs]. Increments
     * `attemptCount` and computes the next eligible retry timestamp using the
     * [RetryConfig] curve.
     */
    fun recordAttempt(bundleId: String, nowMs: Long) = synchronized(lock) {
        val prev = state[bundleId]
        val nextAttempt = (prev?.attemptCount ?: 0) + 1
        val delay = computeDelayMs(nextAttempt)
        state[bundleId] = BundleState(
            attemptCount = nextAttempt,
            nextRetryAt = nowMs + delay
        )
        attemptsTotal.incrementAndGet()
    }

    /**
     * Compute the next-attempt delay using `base * factor^(attempt-1)` clamped
     * at `maxDelayMs`, then apply symmetric jitter. Visible for testing.
     */
    internal fun computeDelayMs(attemptCount: Int): Long {
        // attemptCount=1 → base, =2 → base*factor, =3 → base*factor^2, …, then clamp.
        val raw = config.baseDelayMs.toDouble() * config.growthFactor.pow((attemptCount - 1).coerceAtLeast(0))
        val clamped = min(raw, config.maxDelayMs.toDouble())
        val jitter = config.jitterFraction
        val factor = if (jitter == 0.0) 1.0 else 1.0 + ((rand() * 2.0 - 1.0) * jitter)
        // Guard against rand() returning out-of-spec values that would zero-out the delay.
        val jittered = max(0.0, clamped * factor)
        return jittered.toLong().coerceAtLeast(0L)
    }

    /** Bundle was delivered / read — drop its retry state. */
    fun onDelivered(bundleId: String) = synchronized(lock) {
        state.remove(bundleId)
    }

    /** Bundle's TTL expired — drop its retry state. The retry loop will skip it on the next tick. */
    fun onTtlExpired(bundleId: String) = synchronized(lock) {
        state.remove(bundleId)
    }

    /**
     * A new peer connected — the topology may have improved. Reset every tracked
     * bundle's attemptCount to 0 so the next retry tick re-issues them at the base
     * delay rather than on a stale exponential. Returns the number of bundles whose
     * state was reset (zero if there are no tracked bundles).
     */
    fun onPeerConnectedBypass(nowMs: Long): Int = synchronized(lock) {
        val keys = state.keys.toList()
        for (k in keys) {
            // Don't reset to nextRetryAt=nowMs — that would cause every tracked bundle
            // to fire on the very next tick simultaneously (retry storm). Instead, set
            // nextRetryAt to nowMs (eligible immediately) but bound the broadcast rate
            // via PerPeerSendPacer downstream. attemptCount=0 starts the curve fresh.
            state[k] = BundleState(attemptCount = 0, nextRetryAt = nowMs)
        }
        keys.size
    }

    /** Forget every tracked bundle — used by tests and by [MeshEngine.stop]. */
    fun reset() = synchronized(lock) {
        state.clear()
        attemptsTotal.set(0L)
    }
}
