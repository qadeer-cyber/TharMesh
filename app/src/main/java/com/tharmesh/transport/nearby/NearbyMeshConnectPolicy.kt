// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.transport.nearby

/**
 * Bounded connect debounce + pending timeouts for [NearbyConnectionsTransport].
 * Pure JVM-testable; no Android Context.
 */
class NearbyMeshConnectPolicy(
    private val debounceMs: Long = DEBOUNCE_MS,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val minReconnectBackoffMs: Long = MIN_RECONNECT_BACKOFF_MS,
    private val maxReconnectBackoffMs: Long = MAX_RECONNECT_BACKOFF_MS
) {
    private val lastRequestAt: MutableMap<String, Long> = HashMap()
    private val pendingSince: MutableMap<String, Long> = HashMap()
    private val failureStreak: MutableMap<String, Int> = HashMap()
    private val backoffUntil: MutableMap<String, Long> = HashMap()

    fun allowConnectionRequest(peerId: String): Boolean {
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

    fun markConnected(peerId: String) {
        pendingSince.remove(peerId)
        failureStreak.remove(peerId)
        backoffUntil.remove(peerId)
    }

    fun markDisconnected(peerId: String) {
        pendingSince.remove(peerId)
    }

    /** Record a failed connect or unexpected disconnect; applies bounded exponential backoff. */
    fun recordConnectFailure(peerId: String) {
        pendingSince.remove(peerId)
        val streak = (failureStreak[peerId] ?: 0) + 1
        failureStreak[peerId] = streak
        backoffUntil[peerId] = nowMs() + reconnectBackoffMs(streak)
    }

    fun reconnectBackoffMs(streak: Int): Long {
        if (streak <= 0) return 0L
        var delay = minReconnectBackoffMs
        repeat(streak - 1) {
            delay = (delay * 2).coerceAtMost(maxReconnectBackoffMs)
        }
        return delay.coerceAtMost(maxReconnectBackoffMs)
    }

    fun backoffRemainingMs(peerId: String): Long {
        val until = backoffUntil[peerId] ?: return 0L
        return (until - nowMs()).coerceAtLeast(0L)
    }

    fun pendingConnectionCount(): Int = pendingSince.size

    fun pendingTimedOut(peerId: String): Boolean {
        val since = pendingSince[peerId] ?: return false
        return nowMs() - since >= connectTimeoutMs
    }

    fun clearPending(peerId: String) {
        pendingSince.remove(peerId)
    }

    companion object {
        const val DEBOUNCE_MS: Long = 2_000L
        const val CONNECT_TIMEOUT_MS: Long = 30_000L
        const val MIN_RECONNECT_BACKOFF_MS: Long = 1_000L
        const val MAX_RECONNECT_BACKOFF_MS: Long = 30_000L
    }
}