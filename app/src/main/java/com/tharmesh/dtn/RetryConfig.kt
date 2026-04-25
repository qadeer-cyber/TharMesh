package com.tharmesh.dtn

/**
 * Stage 5.2 — tuning knobs for the per-bundle store-and-forward retry policy.
 *
 * Defaults are calibrated for real-world DTN behaviour: the backoff curve
 * starts aggressive (5s after the first miss) and decays to a 60s ceiling so
 * a long drought (peer hours away) costs at most ~one attempt per minute.
 *
 * NOTE — there is intentionally no "maxAttempts" cap. TharMesh is delay-
 * tolerant; a peer may legitimately return after hours. Retries stop only on
 * delivery / read or TTL expiry. See [RetryPolicy] for the decision logic.
 */
data class RetryConfig(
    /** Delay applied after the FIRST failed attempt. */
    val baseDelayMs: Long = 5_000L,
    /** Hard ceiling on per-attempt delay regardless of attemptCount. */
    val maxDelayMs: Long = 60_000L,
    /** Geometric growth factor per attempt: delay = base * factor^(attempt-1). */
    val growthFactor: Double = 2.0,
    /**
     * Symmetric jitter as a fraction of the computed delay. e.g. 0.20 → ±20 %.
     * Avoids retry-storm synchronisation when many devices come back online
     * simultaneously after a power outage / SOS event.
     */
    val jitterFraction: Double = 0.20,
    /** Periodicity of the retry-loop tick. Bounds worst-case latency on retry decisions. */
    val tickIntervalMs: Long = 1_000L,
    /** Coalesce window for [PeerChurnDebouncer] retry/sync triggers. */
    val churnDebounceMs: Long = 1_500L,
    /** Per-peer minimum gap between [transport.send] calls (see [PerPeerSendPacer]). */
    val perPeerSendGapMs: Long = 40L
) {
    init {
        require(baseDelayMs > 0) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be >= baseDelayMs" }
        require(growthFactor >= 1.0) { "growthFactor must be >= 1.0" }
        require(jitterFraction in 0.0..1.0) { "jitterFraction must be in [0,1]" }
        require(tickIntervalMs > 0) { "tickIntervalMs must be positive" }
        require(churnDebounceMs >= 0) { "churnDebounceMs must be non-negative" }
        require(perPeerSendGapMs >= 0) { "perPeerSendGapMs must be non-negative" }
    }

    companion object {
        /** Production defaults, calibrated against the constraints in Stage 5.2 design notes. */
        val DEFAULT: RetryConfig = RetryConfig()
    }
}
