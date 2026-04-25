package com.tharmesh.diagnostics

import com.tharmesh.dtn.MeshEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Aggregates [MeshEvent] emissions into live counters plus a rolling tail of
 * the most recent events. Stage 5.1 — Field Test Mode + Diagnostics.
 *
 * Wiring: [com.tharmesh.TharMeshApp] subscribes a single collector instance to
 * [com.tharmesh.dtn.MeshEngine.addEventListener]. The collector is purely
 * passive — it never mutates mesh state and its failures can never break the
 * engine (the engine's fire path already swallows listener throws).
 *
 * Thread-safety: counters are [AtomicLong]s; the recent-events ring buffer is
 * guarded by [recentLock]. Snapshot / JSON export are safe to call from the UI
 * thread while the mesh engine fires events from its IO dispatcher.
 */
class DiagnosticsCollector(
    recentCapacity: Int = 200,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    // --- counters ---
    // Peer lifecycle. peersFound is cumulative; peersCurrentlyConnected is a
    // net gauge derived from (connected - disconnected).
    val peersFound = AtomicLong(0)
    val peersConnected = AtomicLong(0)
    val peersDisconnected = AtomicLong(0)
    // Bundle lifecycle.
    val bundlesSending = AtomicLong(0)
    val bundlesSent = AtomicLong(0)
    val bundlesDelivered = AtomicLong(0)
    val bundlesAcked = AtomicLong(0)
    val bundlesRead = AtomicLong(0)
    val bundlesFailed = AtomicLong(0)

    private val createdAt: Long = now()
    @Volatile private var lastEventAt: Long = 0L

    private val recentLock = Any()
    private val recent = RingBuffer<RecentEvent>(recentCapacity)

    data class RecentEvent(
        val timestampMs: Long,
        val kind: String,
        val detail: String
    )

    /** Primary entry point — wire via `meshEngine.addEventListener(collector::onEvent)`. */
    fun onEvent(event: MeshEvent) {
        lastEventAt = now()
        when (event) {
            is MeshEvent.PeerFound -> {
                peersFound.incrementAndGet()
                record("PeerFound", event.peerId)
            }
            is MeshEvent.PeerConnected -> {
                peersConnected.incrementAndGet()
                record("PeerConnected", event.peerId)
            }
            is MeshEvent.PeerDisconnected -> {
                peersDisconnected.incrementAndGet()
                record("PeerDisconnected", event.peerId)
            }
            is MeshEvent.BundleSending -> {
                bundlesSending.incrementAndGet()
                record("BundleSending", event.bundleId)
            }
            is MeshEvent.BundleSent -> {
                bundlesSent.incrementAndGet()
                record("BundleSent", event.bundleId)
            }
            is MeshEvent.BundleDelivered -> {
                bundlesDelivered.incrementAndGet()
                record("BundleDelivered", event.bundle.bundleId)
            }
            is MeshEvent.BundleAcked -> {
                bundlesAcked.incrementAndGet()
                record("BundleAcked", "${event.bundleId} by=${event.ackedByUserId}")
            }
            is MeshEvent.BundleRead -> {
                bundlesRead.incrementAndGet()
                record("BundleRead", "${event.bundleId} by=${event.readByUserId}")
            }
            is MeshEvent.BundleFailed -> {
                bundlesFailed.incrementAndGet()
                record("BundleFailed", "${event.bundleId} reason=${event.reason}")
            }
        }
    }

    private fun record(kind: String, detail: String) {
        synchronized(recentLock) {
            recent.add(RecentEvent(lastEventAt, kind, detail))
        }
    }

    /** Oldest → newest snapshot of the recent-events tail. */
    fun recentEvents(): List<RecentEvent> = synchronized(recentLock) { recent.snapshot() }

    /** Net currently-connected peers (best-effort; never negative in practice). */
    fun peersCurrentlyConnected(): Long {
        val diff = peersConnected.get() - peersDisconnected.get()
        return if (diff < 0) 0 else diff
    }

    data class Snapshot(
        val createdAt: Long,
        val uptimeMs: Long,
        val lastEventAt: Long,
        val peersFound: Long,
        val peersConnected: Long,
        val peersDisconnected: Long,
        val peersCurrentlyConnected: Long,
        val bundlesSending: Long,
        val bundlesSent: Long,
        val bundlesDelivered: Long,
        val bundlesAcked: Long,
        val bundlesRead: Long,
        val bundlesFailed: Long
    )

    fun snapshot(): Snapshot {
        val t = now()
        return Snapshot(
            createdAt = createdAt,
            uptimeMs = t - createdAt,
            lastEventAt = lastEventAt,
            peersFound = peersFound.get(),
            peersConnected = peersConnected.get(),
            peersDisconnected = peersDisconnected.get(),
            peersCurrentlyConnected = peersCurrentlyConnected(),
            bundlesSending = bundlesSending.get(),
            bundlesSent = bundlesSent.get(),
            bundlesDelivered = bundlesDelivered.get(),
            bundlesAcked = bundlesAcked.get(),
            bundlesRead = bundlesRead.get(),
            bundlesFailed = bundlesFailed.get()
        )
    }

    /** Export counters + recent events as JSON for a share intent. */
    fun exportJson(): String {
        val s = snapshot()
        val counters = JSONObject()
            .put("createdAt", s.createdAt)
            .put("uptimeMs", s.uptimeMs)
            .put("lastEventAt", s.lastEventAt)
            .put("peersFound", s.peersFound)
            .put("peersConnected", s.peersConnected)
            .put("peersDisconnected", s.peersDisconnected)
            .put("peersCurrentlyConnected", s.peersCurrentlyConnected)
            .put("bundlesSending", s.bundlesSending)
            .put("bundlesSent", s.bundlesSent)
            .put("bundlesDelivered", s.bundlesDelivered)
            .put("bundlesAcked", s.bundlesAcked)
            .put("bundlesRead", s.bundlesRead)
            .put("bundlesFailed", s.bundlesFailed)
        val events = JSONArray()
        for (e in recentEvents()) {
            events.put(
                JSONObject()
                    .put("t", e.timestampMs)
                    .put("kind", e.kind)
                    .put("detail", e.detail)
            )
        }
        return JSONObject()
            .put("stage", "5.1")
            .put("generatedAt", s.lastEventAt.let { if (it == 0L) now() else it })
            .put("counters", counters)
            .put("recentEvents", events)
            .toString(2)
    }

    /** Reset counters + recent events. Used by the "Clear" UI action. */
    fun reset() {
        peersFound.set(0); peersConnected.set(0); peersDisconnected.set(0)
        bundlesSending.set(0); bundlesSent.set(0); bundlesDelivered.set(0)
        bundlesAcked.set(0); bundlesRead.set(0); bundlesFailed.set(0)
        synchronized(recentLock) { recent.clear() }
        lastEventAt = 0L
    }
}
