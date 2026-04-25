// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.data

import com.tharmesh.dtn.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryStatePersistenceTest {

    @Test
    fun inMemoryImpl_savesLoadsAndRemoves() {
        val p: RetryStatePersistence = RetryStatePersistence.InMemory()
        p.save("a", RetryPolicy.BundleState(attemptCount = 2, nextRetryAt = 1_000L), priority = true)
        p.save("b", RetryPolicy.BundleState(attemptCount = 0, nextRetryAt = 2_000L), priority = false)

        val rows = p.loadAll().associateBy { it.bundleId }
        assertEquals(2, rows.size)
        assertEquals(2, rows["a"]!!.attemptCount)
        assertEquals(1_000L, rows["a"]!!.nextRetryAt)
        assertTrue(rows["a"]!!.priority)
        assertFalse(rows["b"]!!.priority)

        p.remove("a")
        val after = p.loadAll().associateBy { it.bundleId }
        assertEquals(1, after.size)
        assertNull(after["a"])

        p.removeAll()
        assertTrue(p.loadAll().isEmpty())
    }

    @Test
    fun updatePriority_flipsExistingRow_andIsNoopForMissingRow() {
        val p: RetryStatePersistence = RetryStatePersistence.InMemory()
        p.save("a", RetryPolicy.BundleState(attemptCount = 1, nextRetryAt = 500L), priority = false)
        p.updatePriority("a", true)
        assertTrue(p.loadAll().first { it.bundleId == "a" }.priority)

        p.updatePriority("missing", true) // no-op, no throw
        assertEquals(1, p.loadAll().size)
    }

    @Test
    fun retryPolicy_hydrate_seedsFromPersistedSnapshot_andOverwritesPriorState() {
        val pol = RetryPolicy()
        pol.markOriginated("a", nowMs = 0L)
        assertEquals(1, pol.trackedBundleCount())

        val seed = mapOf(
            "x" to RetryPolicy.BundleState(attemptCount = 3, nextRetryAt = 42_000L),
            "y" to RetryPolicy.BundleState(attemptCount = 5, nextRetryAt = 99_000L)
        )
        pol.hydrate(seed)

        assertNull("pre-hydrate state must be dropped", pol.currentState("a"))
        assertEquals(2, pol.trackedBundleCount())
        assertEquals(3, pol.currentState("x")!!.attemptCount)
        assertEquals(42_000L, pol.currentState("x")!!.nextRetryAt)
        assertEquals(5, pol.currentState("y")!!.attemptCount)
    }

    @Test
    fun retryPolicy_snapshot_returnsCopy_mutationsDoNotLeakIntoPolicy() {
        val pol = RetryPolicy()
        pol.markOriginated("a", nowMs = 0L)
        pol.recordAttempt("a", nowMs = 10L)
        val snap = pol.snapshot().toMutableMap()
        assertEquals(1, snap.size)
        // Mutating the snapshot must not affect the policy.
        snap["a"] = RetryPolicy.BundleState(attemptCount = 99, nextRetryAt = 99L)
        snap["z"] = RetryPolicy.BundleState(attemptCount = 7, nextRetryAt = 7L)
        assertNull(pol.currentState("z"))
        val still = pol.currentState("a")!!
        assertEquals(1, still.attemptCount)
    }

    @Test
    fun retryPolicyRoundTripsThroughPersistence_restoresCurveAndPriority() {
        // Pretend we ran a full curve before the "crash".
        val beforeCrash = RetryPolicy()
        beforeCrash.markOriginated("sos-1", nowMs = 0L, configOverride = com.tharmesh.dtn.RetryConfig.SOS)
        beforeCrash.recordAttempt("sos-1", nowMs = 1_000L, configOverride = com.tharmesh.dtn.RetryConfig.SOS)
        beforeCrash.recordAttempt("sos-1", nowMs = 2_500L, configOverride = com.tharmesh.dtn.RetryConfig.SOS)

        val store: RetryStatePersistence = RetryStatePersistence.InMemory()
        // Mirror the final state.
        val finalState = beforeCrash.currentState("sos-1")!!
        store.save("sos-1", finalState, priority = true)

        // Simulate a cold start by constructing a fresh policy + hydrating.
        val afterRestart = RetryPolicy()
        val seed = store.loadAll().associate { row ->
            row.bundleId to RetryPolicy.BundleState(row.attemptCount, row.nextRetryAt)
        }
        afterRestart.hydrate(seed)

        val restored = afterRestart.currentState("sos-1")!!
        assertEquals(finalState.attemptCount, restored.attemptCount)
        assertEquals(finalState.nextRetryAt, restored.nextRetryAt)

        // Priority bit is restored from the persisted row, not from the policy.
        val row = store.loadAll().first { it.bundleId == "sos-1" }
        assertTrue(row.priority)
    }
}
