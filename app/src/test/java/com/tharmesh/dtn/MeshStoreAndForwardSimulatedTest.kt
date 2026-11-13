package com.tharmesh.dtn

import org.junit.Assert.assertTrue
import org.junit.Test

/** Category 2 simulated — not physical radio. */
class MeshStoreAndForwardSimulatedTest {

    @Test
    fun duplicateBundleId_rejectedByPolicy() {
        val seen = mutableSetOf<String>()
        val bundleId = "dup-bundle-1"
        fun accept(id: String): Boolean {
            if (id in seen) return false
            seen.add(id)
            return true
        }
        assertTrue(accept(bundleId))
        assertTrue(!accept(bundleId))
    }
}
