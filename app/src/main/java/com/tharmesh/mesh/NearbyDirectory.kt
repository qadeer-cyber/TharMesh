package com.tharmesh.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Process-wide source of truth for "who's nearby on the mesh".
 *
 * Wraps a [MeshDataSource] that can be **swapped at runtime** via [setSource]. This
 * matters because [com.tharmesh.TharMeshApp.ensureMeshStarted] may defer transport
 * wiring until runtime permissions are granted — by that point Fragments are already
 * running and have captured a reference to `TharMeshApp.get().directory`. Exposing
 * owned [MutableStateFlow]s that this class keeps in sync with whichever source is
 * current guarantees that callers see peer updates without needing to re-read the
 * directory reference.
 *
 * There is no fallback roster and no simulation — if the underlying data source
 * exposes zero nodes, the UI renders an empty state.
 */
class NearbyDirectory(
    private val scope: CoroutineScope,
    initialSource: MeshDataSource
) {

    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val _scanState = MutableStateFlow(ScanState.IDLE)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val sourceLock = Any()
    private var source: MeshDataSource = initialSource
    private var mirrorJob: Job? = null

    init {
        setSource(initialSource)
    }

    /**
     * Swap the underlying data source. Cancels mirroring from the previous source,
     * resets the published nodes / scanState to the new source's current values, and
     * starts mirroring from the new one. Safe to call from any thread.
     */
    fun setSource(newSource: MeshDataSource) {
        synchronized(sourceLock) {
            mirrorJob?.cancel()
            source = newSource
            _nodes.value = newSource.nodes.value
            _scanState.value = newSource.scanState.value
            mirrorJob = scope.launch {
                launch {
                    newSource.nodes.collect { value -> _nodes.value = value }
                }
                launch {
                    newSource.scanState.collect { value -> _scanState.value = value }
                }
            }
        }
    }

    /** Online nodes ranked best-first via [MeshNode.score]. */
    fun onlineRanked(): List<MeshNode> = nodes.value
        .filter { it.online }
        .sortedByDescending { it.score() }

    /** Number of peers currently reachable. Drives SOS target count + health card. */
    fun onlineCount(): Int = nodes.value.count { it.online }

    fun startScan() {
        val s = synchronized(sourceLock) { source }
        s.startScan()
    }

    fun stopScan() {
        val s = synchronized(sourceLock) { source }
        s.stopScan()
    }
}
