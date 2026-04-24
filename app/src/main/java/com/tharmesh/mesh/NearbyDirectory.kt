package com.tharmesh.mesh

import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide source of truth for "who's nearby on the mesh".
 *
 * Thin facade over an injected [MeshDataSource]. There is no fallback roster and no
 * simulation — if the underlying data source exposes zero nodes, the UI renders an
 * empty state. When a real Bluetooth / Wi-Fi Direct / Nearby transport is wired, swap
 * [EmptyMeshDataSource] for a real implementation.
 */
class NearbyDirectory(
    private val source: MeshDataSource
) {

    val nodes: StateFlow<List<MeshNode>> get() = source.nodes
    val scanState: StateFlow<ScanState> get() = source.scanState

    /** Online nodes ranked best-first via [MeshNode.score]. */
    fun onlineRanked(): List<MeshNode> = nodes.value
        .filter { it.online }
        .sortedByDescending { it.score() }

    /** Number of peers currently reachable. Drives SOS target count + health card. */
    fun onlineCount(): Int = nodes.value.count { it.online }

    fun startScan() = source.startScan()
    fun stopScan() = source.stopScan()
}
