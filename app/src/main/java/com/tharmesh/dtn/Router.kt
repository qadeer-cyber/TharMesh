// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer / Qadeer Cyber. All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.dtn

/**
 * Forwarding policy.
 *
 * [shouldForward] enforces the three relay-safety invariants and returns `true` only
 * when a fresh (bundleId, peerId) pair is eligible for transmission:
 *
 *   1. `hopsLeft > 0`  — prevents infinite relay loops.
 *   2. `ttlUntil >= now`  — bundle has not expired.
 *   3. (bundleId, peerId) pair has not been forwarded before  — prevents ping-pong
 *      storms across bidirectional links (A→B→A→B…).
 *
 * The (bundleId, peerId) memo is capped at [MAX_ENTRIES] with oldest-first LRU
 * eviction so a long-running node can't leak memory over weeks of relay traffic.
 */
class Router(private val maxEntries: Int = MAX_ENTRIES) {

    // LinkedHashMap with access-order=false (insertion order) so the oldest entry is
    // always removable via iterator().next(). Synchronized externally.
    private val sentToPeer: LinkedHashMap<String, Boolean> = LinkedHashMap()
    private val lock = Any()

    fun shouldForward(bundle: MeshBundle, peerId: String, nowMs: Long): Boolean {
        if (bundle.hopsLeft <= 0) {
            return false
        }
        if (bundle.ttlUntil < nowMs) {
            return false
        }
        val key = bundle.bundleId + "@" + peerId
        synchronized(lock) {
            if (sentToPeer.containsKey(key)) {
                return false
            }
            sentToPeer[key] = true
            // Bounded memo — evict oldest entries LRU-style.
            while (sentToPeer.size > maxEntries) {
                val iter = sentToPeer.entries.iterator()
                if (!iter.hasNext()) break
                iter.next()
                iter.remove()
            }
        }
        return true
    }

    /** Test-visible memo size. */
    internal fun memoSize(): Int = synchronized(lock) { sentToPeer.size }

    companion object {
        /** Max (bundleId, peerId) memo entries before LRU eviction kicks in. */
        const val MAX_ENTRIES: Int = 10_000
    }
}
