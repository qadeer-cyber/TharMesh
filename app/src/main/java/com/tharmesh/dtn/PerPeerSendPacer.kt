package com.tharmesh.dtn

/**
 * Stage 5.2 — enforces a minimum inter-send gap PER PEER on the broadcast /
 * forward fan-out. Pure Kotlin; thread-safe via a single internal lock.
 *
 * Rationale: when N pending bundles flush to M peers in a tight loop, Nearby
 * Connections starts rejecting `send()` calls under buffer pressure (returning
 * `false`, surfaced as artificial `BundleFailed`). A 40 ms gap per peer is a
 * cheap insurance policy and is invisible to user-perceived latency on any
 * single chat message.
 *
 * Semantics:
 *  - [acquireSlot] returns true and advances the per-peer last-send timestamp
 *    iff at least [gapMs] has elapsed since the previous successful acquire.
 *    Otherwise returns false WITHOUT updating state — the caller is expected
 *    to defer (the retry loop will re-issue, or a relay forward simply skips).
 *  - [reset] clears all per-peer state. Used on mesh shutdown.
 *
 * Diagnostics:
 *  - [pacedTotal] counts cumulative deferrals across the lifetime of this
 *    instance.
 */
class PerPeerSendPacer(
    private val gapMs: Long
) {

    private val lock = Any()
    private val lastSendAt: MutableMap<String, Long> = HashMap()

    @Volatile private var pacedCount: Long = 0L

    /** Number of times [acquireSlot] returned false (deferred sends). */
    fun pacedTotal(): Long = pacedCount

    /**
     * Try to acquire a send slot for [peerId] at [nowMs]. Returns true and updates
     * the per-peer last-send timestamp when the gap is satisfied; false (and
     * increments [pacedTotal]) otherwise.
     */
    fun acquireSlot(peerId: String, nowMs: Long): Boolean = synchronized(lock) {
        val last = lastSendAt[peerId]
        if (last != null && nowMs - last < gapMs) {
            pacedCount++
            return false
        }
        lastSendAt[peerId] = nowMs
        return true
    }

    /** Clear per-peer state. Used on mesh shutdown / disconnect. */
    fun reset() {
        synchronized(lock) {
            lastSendAt.clear()
        }
        pacedCount = 0L
    }
}
