package com.tharmesh.dtn

import com.tharmesh.transport.loopback.LoopbackTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MeshEngineMalformedBundleTest {

    @Test
    fun garbageWire_doesNotDeliverOrAck() {
        val hub = LoopbackTransport.Hub()
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        val bob = MeshEngine("bob", LoopbackTransport(hub))
        bob.setEventListener { bobEvents.add(it) }
        bob.start()
        val mallory = LoopbackTransport(hub)
        mallory.start("mallory")
        mallory.send(
            "bob",
            "NOT_A_FRAME".toByteArray(Charsets.UTF_8),
            0L
        )
        mallory.send(
            "bob",
            ("BUNDLE|mallory|not-json").toByteArray(Charsets.UTF_8),
            1L
        )
        assertTrue(bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().isEmpty())
        assertTrue(bobEvents.filterIsInstance<MeshEvent.BundleAcked>().isEmpty())
    }

    @Test
    fun expiredBundle_onWire_isIgnored() {
        val hub = LoopbackTransport.Hub()
        var clock = 1_000L
        val bobEvents = CopyOnWriteArrayList<MeshEvent>()
        val bob = MeshEngine("bob", LoopbackTransport(hub), now = { clock })
        bob.setEventListener { bobEvents.add(it) }
        bob.start()
        val expired = MeshBundle(
            bundleId = "exp-wire",
            srcId = "mallory",
            destId = "bob",
            payloadCiphertext = "late",
            ttlUntil = 1L,
            hopsLeft = 3,
            signature = "sig",
            status = "PENDING"
        )
        clock = 5_000L
        val frame = "BUNDLE|mallory|" + BundleCodec.encode(expired)
        val mallory = LoopbackTransport(hub)
        mallory.start("mallory")
        mallory.send("bob", frame.toByteArray(Charsets.UTF_8), 9L)
        assertEquals(0, bobEvents.filterIsInstance<MeshEvent.BundleDelivered>().size)
    }
}