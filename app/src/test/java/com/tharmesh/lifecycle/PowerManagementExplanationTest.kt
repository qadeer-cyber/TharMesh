package com.tharmesh.lifecycle

import com.tharmesh.dtn.RetryConfig
import com.tharmesh.dtn.RetryPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerManagementExplanationTest {

  @Test
  fun dozeCopy_isExplicitAboutLimitsAndNoWakeLock() {
    val body = PowerManagementExplanation.BODY_EN.lowercase()
    assertTrue(body.contains(PowerManagementExplanation.DOZE_KEYWORD.lowercase()))
    assertTrue(body.contains(PowerManagementExplanation.FOREGROUND_SERVICE_KEYWORD))
    assertTrue(body.contains("wake lock"))
  }

  @Test
  fun retryPolicy_hasBoundedCeiling_notUnboundedStorm() {
    val policy = RetryPolicy(RetryConfig.DEFAULT, rand = { 0.0 })
    var t = 0L
    policy.recordAttempt("b1", t)
    repeat(50) { t += 1; policy.recordAttempt("b1", t) }
    val state = policy.currentState("b1")!!
    val delay = policy.computeDelayMs(state.attemptCount)
    assertTrue("backoff must cap under storm", delay <= RetryConfig.DEFAULT.maxDelayMs)
  }
}