package com.tharmesh.transport.loopback

import com.tharmesh.transport.TransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackTransportTest {

    @Test
    fun send_deliversPayloadToPeer() {
        val hub = LoopbackTransport.Hub()
        val a = LoopbackTransport(hub)
        val b = LoopbackTransport(hub)
        val received = mutableListOf<TransportEvent>()
        b.setListener { received.add(it) }
        a.start("a")
        b.start("b")

        val ok = a.send("b", "hi".toByteArray(), sendId = 42L)

        assertTrue("send should succeed", ok)
        val payload = received.filterIsInstance<TransportEvent.PayloadReceived>().single()
        assertEquals("a", payload.peerId)
        assertEquals("hi", String(payload.bytes))
    }

    @Test
    fun send_withUnknownPeer_emitsError() {
        val hub = LoopbackTransport.Hub()
        val a = LoopbackTransport(hub)
        val events = mutableListOf<TransportEvent>()
        a.setListener { events.add(it) }
        a.start("a")

        val ok = a.send("ghost", "x".toByteArray(), sendId = 7L)

        assertEquals(false, ok)
        val err = events.filterIsInstance<TransportEvent.Error>().single()
        assertEquals(7L, err.sendId)
    }
}
