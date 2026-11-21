// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.transport.nearby

/**
 * Debounce, timeout and bounded-exponential-backoff policy for Nearby connection
 * attempts.
 *
 * Thread safety: Nearby lifecycle callbacks, the connect-timeout runnable and the
 * UI (via [pendingConnectionCount]) all touch this object from different threads,
 * so every method holds [lock]. Plain maps guarded by one lock keep the
 * failure-streak / backoff updates atomic with respect to each other, which a
 * ConcurrentHashMap alone would not.
 */
class NearbyMeshConnectPolicy(
    private val debounceMs: Long = DEBOUNCE_MS,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val minReconnectBackoffMs: Long = MIN_RECONNECT_BACKOFF_MS,
    private val maxReconnectBackoffMs: Long = MAX_RECONNECT_BACKOFF_MS
) {
    private val lock = Any()
    private val lastRequestAt: MutableMap<String, Long> = HashMap()
    private val pendingSince: MutableMap<String, Long> = HashMap()
    private val failureStreak: MutableMap<String, Int> = HashMap()
    private val backoffUntil: MutableMap<String, Long> = HashMap()

    fun allowConnectionRequest(peerId: String): Boolean = synchronized(lock) {
        val now = nowMs()
        val backoffDeadline = backoffUntil[peerId]
        if (backoffDeadline != null && now < backoffDeadline) return false
        if (pendingSince.containsKey(peerId)) return false
        val last = lastRequestAt[peerId]
        if (last != null && now - last < debounceMs) return false
        lastRequestAt[peerId] = now
        pendingSince[peerId] = now
        return true
    }

    fun markConnected(peerId: String): Unit = synchronized(lock) {
        pendingSince.remove(peerId)
        failureStreak.remove(peerId)
        backoffUntil.remove(peerId)
    }

    fun markDisconnected(peerId: String): Unit = synchronized(lock) {
        pendingSince.remove(peerId)
    }

    /** Record a failed connect or unexpected disconnect; applies bounded exponential backoff. */
    fun recordConnectFailure(peerId: String): Unit = synchronized(lock) {
        pendingSince.remove(peerId)
        val streak = (failureStreak[peerId] ?: 0) + 1
        failureStreak[peerId] = streak
        backoffUntil[peerId] = nowMs() + reconnectBackoffMs(streak)
    }

    /** Pure backoff curve: min * 2^(streak-1), clamped to [maxReconnectBackoffMs]. */
    fun reconnectBackoffMs(streak: Int): Long {
        if (streak <= 0) return 0L
        // Bound the doubling count: the curve saturates at maxReconnectBackoffMs long
        // before 32 doublings, and an unbounded `repeat(streak - 1)` would spin for a
        // pathological streak value.
        val doublings = (streak - 1).coerceAtMost(MAX_BACKOFF_DOUBLINGS)
        var delay = minReconnectBackoffMs
        repeat(doublings) {
            delay = (delay * 2).coerceAtMost(maxReconnectBackoffMs)
        }
        return delay.coerceAtMost(maxReconnectBackoffMs)
    }

    fun backoffRemainingMs(peerId: String): Long = synchronized(lock) {
        val until = backoffUntil[peerId] ?: return 0L
        return (until - nowMs()).coerceAtLeast(0L)
    }

    fun pendingConnectionCount(): Int = synchronized(lock) { pendingSince.size }

    fun pendingTimedOut(peerId: String): Boolean = synchronized(lock) {
        val since = pendingSince[peerId] ?: return false
        return nowMs() - since >= connectTimeoutMs
    }

    fun clearPending(peerId: String): Unit = synchronized(lock) {
        pendingSince.remove(peerId)
    }

    companion object {
        const val DEBOUNCE_MS: Long = 2_000L
        const val CONNECT_TIMEOUT_MS: Long = 30_000L
        const val MIN_RECONNECT_BACKOFF_MS: Long = 1_000L
        const val MAX_RECONNECT_BACKOFF_MS: Long = 30_000L
        private const val MAX_BACKOFF_DOUBLINGS: Int = 32
    }
}
