package com.tharmesh.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RingBufferTest {

    @Test
    fun `empty ring buffer reports empty and size zero`() {
        val rb = RingBuffer<Int>(4)
        assertTrue(rb.isEmpty)
        assertFalse(rb.isFull)
        assertEquals(0, rb.size)
        assertEquals(emptyList<Int>(), rb.snapshot())
    }

    @Test
    fun `partial fill preserves insertion order`() {
        val rb = RingBuffer<Int>(4)
        rb.add(1); rb.add(2); rb.add(3)
        assertEquals(3, rb.size)
        assertFalse(rb.isFull)
        assertEquals(listOf(1, 2, 3), rb.snapshot())
    }

    @Test
    fun `overflow evicts oldest and keeps newest in order`() {
        val rb = RingBuffer<Int>(3)
        listOf(1, 2, 3, 4, 5).forEach { rb.add(it) }
        assertTrue(rb.isFull)
        assertEquals(3, rb.size)
        // Oldest → newest. 1 and 2 were evicted.
        assertEquals(listOf(3, 4, 5), rb.snapshot())
    }

    @Test
    fun `snapshot returns an independent copy`() {
        val rb = RingBuffer<String>(2)
        rb.add("a"); rb.add("b")
        val snap = rb.snapshot()
        rb.add("c")
        assertEquals(listOf("a", "b"), snap)
        assertEquals(listOf("b", "c"), rb.snapshot())
    }

    @Test
    fun `clear resets to empty`() {
        val rb = RingBuffer<Int>(3)
        rb.add(1); rb.add(2); rb.add(3); rb.add(4) // overflow
        rb.clear()
        assertTrue(rb.isEmpty)
        assertEquals(0, rb.size)
        assertEquals(emptyList<Int>(), rb.snapshot())
        // Usable again after clear.
        rb.add(9)
        assertEquals(listOf(9), rb.snapshot())
    }

    @Test
    fun `capacity must be positive`() {
        try {
            RingBuffer<Int>(0)
            fail("expected IllegalArgumentException for capacity=0")
        } catch (_: IllegalArgumentException) {
        }
        try {
            RingBuffer<Int>(-1)
            fail("expected IllegalArgumentException for capacity=-1")
        } catch (_: IllegalArgumentException) {
        }
    }
}
