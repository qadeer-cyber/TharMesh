package com.tharmesh.mesh

import com.tharmesh.dtn.RetryConfig
import com.tharmesh.dtn.RetryPolicy
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshForegroundServiceGuardTest {

    @Test
    fun manifest_doesNotDeclareWakeLock() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest missing in unit-test cwd", manifest.isFile)
        val text = manifest.readText()
        assertFalse("WAKE_LOCK must not be declared", text.contains("WAKE_LOCK"))
    }

    @Test
    fun retryPolicy_backoffStaysBounded_underManyAttempts() {
        val policy = RetryPolicy(RetryConfig.DEFAULT.copy(jitterFraction = 0.0), rand = { 0.5 })
        var t = 0L
        repeat(40) {
            t += 50
            policy.recordAttempt("storm-1", t)
        }
        val delay = policy.computeDelayMs(policy.currentState("storm-1")!!.attemptCount)
        assertTrue(delay <= RetryConfig.DEFAULT.maxDelayMs)
    }
}