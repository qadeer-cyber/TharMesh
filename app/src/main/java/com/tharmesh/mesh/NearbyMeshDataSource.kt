package com.tharmesh.mesh

import androidx.annotation.DrawableRes
import com.tharmesh.dtn.MeshEngine
import tharmesh.app.R
import com.tharmesh.dtn.MeshEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.absoluteValue

/**
 * Real [MeshDataSource] backed by peer-lifecycle events emitted by [MeshEngine].
 *
 * Maintains a map of userId → PeerRecord populated from [MeshEvent.PeerFound] /
 * [MeshEvent.PeerConnected] / [MeshEvent.PeerDisconnected]. The [nodes] StateFlow
 * is published whenever the map changes; [scanState] flips to FOUND when the map
 * has at least one peer and back to IDLE / SCANNING / NONE otherwise.
 *
 * Thread safety: Nearby's callback thread dispatches into MeshEngine → firePeerEvent
 * → onPeerEvent here. All mutations of the internal map happen under [lock]. The
 * published snapshot is immutable (`List<MeshNode>` from `toList()`).
 *
 * No fabricated peers. Zero peers = empty list + NONE (when not scanning).
 */
class NearbyMeshDataSource(
    private val engine: MeshEngine
) : MeshDataSource {

    private data class PeerRecord(
        val userId: String,
        val displayName: String,
        val connected: Boolean,
        val firstSeenMs: Long
    )

    private val lock = Any()
    private val peers: MutableMap<String, PeerRecord> = LinkedHashMap()
    private var scanning: Boolean = false

    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val _scanState = MutableStateFlow(ScanState.IDLE)
    override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val listener: (MeshEvent) -> Unit = { event -> onPeerEvent(event) }

    init {
        engine.addPeerListener(listener)
    }

    override fun startScan() {
        synchronized(lock) {
            scanning = true
        }
        publish()
    }

    override fun stopScan() {
        synchronized(lock) {
            scanning = false
        }
        publish()
    }

    private fun onPeerEvent(event: MeshEvent) {
        synchronized(lock) {
            when (event) {
                is MeshEvent.PeerFound -> {
                    val existing = peers[event.peerId]
                    peers[event.peerId] = PeerRecord(
                        userId = event.peerId,
                        displayName = event.displayName.ifBlank { event.peerId },
                        connected = existing?.connected ?: false,
                        firstSeenMs = existing?.firstSeenMs ?: System.currentTimeMillis()
                    )
                }
                is MeshEvent.PeerConnected -> {
                    val existing = peers[event.peerId]
                    peers[event.peerId] = PeerRecord(
                        userId = event.peerId,
                        displayName = existing?.displayName ?: event.peerId,
                        connected = true,
                        firstSeenMs = existing?.firstSeenMs ?: System.currentTimeMillis()
                    )
                }
                is MeshEvent.PeerDisconnected -> {
                    // Remove outright — if Nearby re-discovers it, we'll re-add via PeerFound.
                    peers.remove(event.peerId)
                }
                else -> return
            }
        }
        publish()
    }

    private fun publish() {
        val snapshot: List<PeerRecord>
        val isScanning: Boolean
        synchronized(lock) {
            snapshot = peers.values.toList()
            isScanning = scanning
        }
        val nowMs = System.currentTimeMillis()
        val list = snapshot.map { rec ->
            val uptimeMin = ((nowMs - rec.firstSeenMs) / 60_000L).toInt().coerceAtLeast(0)
            MeshNode(
                userId = rec.userId,
                name = rec.displayName,
                // Real distance/signal/battery require transport RSSI + out-of-band battery
                // reporting, neither of which Nearby Connections exposes. Leave as neutral
                // placeholders — the UI shows "—" for unknown signal and hides distance.
                distance = 0f,
                signal = if (rec.connected) 50 else 0,
                battery = 0,
                uptimeMinutes = uptimeMin,
                online = rec.connected,
                avatarBg = avatarBgFor(rec.userId)
            )
        }
        _nodes.value = list
        _scanState.value = when {
            list.isNotEmpty() -> ScanState.FOUND
            isScanning -> ScanState.SCANNING
            else -> ScanState.NONE
        }
    }

    @DrawableRes
    private fun avatarBgFor(userId: String): Int {
        // Deterministic: same userId → same avatar background across sessions.
        val idx = (userId.hashCode().absoluteValue) % AVATAR_BGS.size
        return AVATAR_BGS[idx]
    }

    companion object {
        private val AVATAR_BGS = intArrayOf(
            R.drawable.bg_avatar,
            R.drawable.bg_avatar_green,
            R.drawable.bg_avatar_amber
        )
    }
}
