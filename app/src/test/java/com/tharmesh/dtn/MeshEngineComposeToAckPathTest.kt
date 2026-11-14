package com.tharmesh.dtn

import com.tharmesh.transport.loopback.LoopbackTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit path: queueText (compose + Room persist + broadcast) → transport → receive →
 * ACK → [MeshEvent.BundleAcked]. Transport-level failure semantics are in
 * [MeshEngineSendCorrelationTest] and [MessageStatusTransportFailureTest].
 */
class MeshEngineComposeToAckPathTest {

  @Test
  fun queueText_deliversAndAcks_onLoopbackTransport() {
    val hub = LoopbackTransport.Hub()
    val aEvents = mutableListOf<MeshEvent>()
    val alice = MeshEngine("alice", LoopbackTransport(hub))
    val bob = MeshEngine("bob", LoopbackTransport(hub))
    alice.setEventListener { aEvents.add(it) }
    alice.start()
    bob.start()
    val bundle = alice.queueText("bob", "cipher-audit", 60_000L, 4, bundleIdHint = "path-1")
    assertTrue(aEvents.any { it is MeshEvent.BundleSending && it.bundleId == bundle.bundleId })
    assertTrue(aEvents.any { it is MeshEvent.BundleSent && it.bundleId == bundle.bundleId })
    val acked = aEvents.filterIsInstance<MeshEvent.BundleAcked>()
    assertEquals(1, acked.size)
    assertEquals(bundle.bundleId, acked[0].bundleId)
  }
}