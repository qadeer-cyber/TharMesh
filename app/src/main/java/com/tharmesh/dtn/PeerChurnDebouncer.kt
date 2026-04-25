package com.tharmesh.dtn

/**
 * Stage 5.2 — coalesces rapid `PeerConnected` triggers for the same peer into a
 * single fire after a `windowMs` settle period. Pure Kotlin; no coroutines, no
 * threads. The retry loop drives [processDue] each tick, which fires any peer
 * whose last reconnect is at least `windowMs` in the past.
 *
 * Semantics:
 *  - [onPeerConnected] records the latest reconnect timestamp for [peerId] and
 *    arms a pending fire. If the same peer reconnects again before [processDue]
 *    runs, the timer effectively resets (the fire condition is "now ≥ last
 *    reconnect + window"), so a flapping peer fires AT MOST once per stable
 *    window — coalescing N reconnects in a tight burst into 1 trailing fire.
 *  - [processDue] iterates pending peers and calls the registered action for
 *    each one whose window has elapsed. The action runs without the internal
 *    lock held so it is free to touch other locks (e.g. mesh).
 *
 * Diagnostics:
 *  - [suppressedTotal] increments every time [onPeerConnected] is called for a
 *    peer that already has a pending fire — i.e. a coalesced churn event.
 */
class PeerChurnDebouncer(
    private val windowMs: Long,
    private val action: (peerId: String) -> Unit
) {

    private data class Pending(val peerId: String, val lastEventAt: Long)

    private val lock = Any()
    // peerId → most recent reconnect timestamp + "fire pending" flag (presence in map = pending).
    private val pending: MutableMap<String, Pending> = HashMap()

    @Volatile private var suppressedCount: Long = 0L

    /** Number of times a reconnect was suppressed because a fire was already pending. */
    fun suppressedTotal(): Long = suppressedCount

    /**
     * Record that [peerId] has reconnected at [nowMs]. If a fire was already
     * pending, this resets the settle window — the new lastEventAt pushes the
     * trailing fire forward — and increments [suppressedTotal].
     */
    fun onPeerConnected(peerId: String, nowMs: Long) {
        synchronized(lock) {
            val existing = pending[peerId]
            if (existing != null) {
                suppressedCount++
            }
            pending[peerId] = Pending(peerId, nowMs)
        }
    }

    /**
     * Fire the trailing action for every peer whose `lastEventAt + windowMs <= nowMs`.
     * Returns the number of fires actually performed (zero if nothing was due).
     * The action is invoked OUTSIDE the internal lock so the caller is free to
     * synchronise on other monitors (e.g. the mesh engine) without risking a
     * lock-ordering deadlock.
     */
    fun processDue(nowMs: Long): Int {
        val due = synchronized(lock) {
            val ready = pending.values.filter { it.lastEventAt + windowMs <= nowMs }.toList()
            for (p in ready) {
                pending.remove(p.peerId)
            }
            ready
        }
        for (p in due) {
            try {
                action(p.peerId)
            } catch (_: Throwable) {
                // Action must not break the debouncer; subsequent peers still fire.
            }
        }
        return due.size
    }

    /** Number of peers currently waiting for their settle window to elapse. */
    fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** Drop pending state. Used on mesh shutdown. */
    fun reset() {
        synchronized(lock) {
            pending.clear()
        }
        suppressedCount = 0L
    }
}
