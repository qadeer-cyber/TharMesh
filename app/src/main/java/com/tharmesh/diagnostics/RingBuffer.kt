package com.tharmesh.diagnostics

/**
 * Fixed-capacity FIFO ring buffer. Oldest entry is evicted on overflow. Not
 * thread-safe on its own — callers (see [DiagnosticsCollector]) serialize
 * writes through their own lock.
 *
 * Stage 5.1 — used to retain the last N [com.tharmesh.dtn.MeshEvent]s as a
 * rolling diagnostic tail without growing unbounded memory on long field
 * tests.
 */
class RingBuffer<T>(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
    }

    private val data: ArrayList<T?> = ArrayList<T?>(capacity).apply {
        repeat(capacity) { add(null) }
    }
    // Index of the next write slot.
    private var head: Int = 0
    private var filled: Int = 0

    val size: Int get() = filled
    val isEmpty: Boolean get() = filled == 0
    val isFull: Boolean get() = filled == capacity

    fun add(item: T) {
        data[head] = item
        head = (head + 1) % capacity
        if (filled < capacity) filled++
    }

    /** Snapshot oldest → newest. Returns a new list; caller owns it. */
    fun snapshot(): List<T> {
        if (filled == 0) return emptyList()
        val out = ArrayList<T>(filled)
        val start = if (filled < capacity) 0 else head
        for (i in 0 until filled) {
            val idx = (start + i) % capacity
            @Suppress("UNCHECKED_CAST")
            out.add(data[idx] as T)
        }
        return out
    }

    fun clear() {
        for (i in 0 until capacity) data[i] = null
        head = 0
        filled = 0
    }
}
