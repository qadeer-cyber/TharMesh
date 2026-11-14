// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.transport.nearby

/**
 * Bounded connect debounce + pending timeouts for [NearbyConnectionsTransport].
 * Pure JVM-testable; no Android Context.
 */
class NearbyMeshConnectPolicy(
    private val debounceMs: Long = DEBOUNCE_MS,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lastRequestAt: MutableMap<String, Long> = HashMap()
    private val pendingSince: MutableMap<String, Long> = HashMap()

    fun allowConnectionRequest(peerId: String): Boolean {
        val now = nowMs()
        if (pendingSince.containsKey(peerId)) return false
        val last = lastRequestAt[peerId]
        if (last != null && now - last < debounceMs) return false
        lastRequestAt[peerId] = now
        pendingSince[peerId] = now
        return true
    }

    fun markConnected(peerId: String) {
        pendingSince.remove(peerId)
    }

    fun markDisconnected(peerId: String) {
        pendingSince.remove(peerId)
    }

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
    }
}