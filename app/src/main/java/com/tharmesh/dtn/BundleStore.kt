package com.tharmesh.dtn

/**
 * Persistence port for [MeshEngine]. Implementations MUST be safe to call from
 * background threads (Nearby callback threads, store-and-forward retry coroutine,
 * LRU eviction during cache mutation) and SHOULD be idempotent on upsert so that
 * duplicate receive paths (e.g. relay chain re-delivery) do not corrupt state.
 *
 * Kept as an interface so MeshEngine can be unit-tested without a real Room
 * database — pass a no-op or in-memory implementation from tests.
 */
interface BundleStore {

    /** Insert-or-replace. Keyed by [MeshBundle.bundleId]. */
    fun upsert(bundle: MeshBundle)

    /** All non-expired bundles (ttlUntil >= nowMs), oldest-first. */
    fun loadActive(nowMs: Long): List<MeshBundle>

    /** Update just the status of an existing row. No-op if the row is missing. */
    fun updateStatus(bundleId: String, status: String)

    /** Remove a bundle by primary key. No-op if missing. */
    fun delete(bundleId: String)

    /** Drop every bundle whose TTL has passed. Returns number of rows removed. */
    fun deleteExpired(nowMs: Long): Int
}
