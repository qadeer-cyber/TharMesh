// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.data

import com.tharmesh.db.dao.RetryStateDao
import com.tharmesh.db.entity.RetryStateEntity
import com.tharmesh.dtn.RetryPolicy

/**
 * Persistence mirror for [RetryPolicy] state + the
 * [MessageRepository.priorityBundleIds] SOS set. Writes are synchronous
 * (the caller is already on an IO coroutine context when it invokes
 * [save] / [remove] / [updatePriority]). Tests can substitute the
 * [InMemory] implementation so they don't have to spin up Room.
 *
 * The contract is additive over [RetryPolicy]: saving never changes the
 * in-memory behaviour; hydration on startup is the only point at which
 * persisted rows feed back into the policy.
 */
interface RetryStatePersistence {

    data class Snapshot(
        val bundleId: String,
        val attemptCount: Int,
        val nextRetryAt: Long,
        val priority: Boolean
    )

    fun save(bundleId: String, state: RetryPolicy.BundleState, priority: Boolean)
    fun updatePriority(bundleId: String, priority: Boolean)
    fun remove(bundleId: String)
    fun removeAll()
    fun loadAll(): List<Snapshot>

    /** Room-backed implementation wired by [com.tharmesh.TharMeshApp]. */
    class Room(private val dao: RetryStateDao) : RetryStatePersistence {
        override fun save(bundleId: String, state: RetryPolicy.BundleState, priority: Boolean) {
            dao.upsert(
                RetryStateEntity(
                    bundleId = bundleId,
                    attemptCount = state.attemptCount,
                    nextRetryAt = state.nextRetryAt,
                    priority = if (priority) 1 else 0
                )
            )
        }

        override fun updatePriority(bundleId: String, priority: Boolean) {
            dao.updatePriority(bundleId, if (priority) 1 else 0)
        }

        override fun remove(bundleId: String) {
            dao.deleteByBundleId(bundleId)
        }

        override fun removeAll() {
            dao.deleteAll()
        }

        override fun loadAll(): List<Snapshot> = dao.loadAll().map { row ->
            Snapshot(
                bundleId = row.bundleId,
                attemptCount = row.attemptCount,
                nextRetryAt = row.nextRetryAt,
                priority = row.priority != 0
            )
        }
    }

    /**
     * Test-friendly in-memory persistence. Lets unit tests drive the
     * save/load cycle without Room — a bundle is written to the map on
     * each `save` and the `loadAll` returns whatever is still present.
     */
    class InMemory : RetryStatePersistence {
        private val backing: MutableMap<String, Snapshot> = HashMap()

        @Synchronized
        override fun save(bundleId: String, state: RetryPolicy.BundleState, priority: Boolean) {
            backing[bundleId] = Snapshot(bundleId, state.attemptCount, state.nextRetryAt, priority)
        }

        @Synchronized
        override fun updatePriority(bundleId: String, priority: Boolean) {
            val prev = backing[bundleId] ?: return
            backing[bundleId] = prev.copy(priority = priority)
        }

        @Synchronized
        override fun remove(bundleId: String) {
            backing.remove(bundleId)
        }

        @Synchronized
        override fun removeAll() {
            backing.clear()
        }

        @Synchronized
        override fun loadAll(): List<Snapshot> = backing.values.toList()
    }
}
