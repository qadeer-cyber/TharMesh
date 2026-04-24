package com.tharmesh.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Abstraction over "who is reachable on the mesh right now".
 *
 * The UI layer depends only on this interface, so we can swap between a stub (empty — see
 * [EmptyMeshDataSource]) and a real Bluetooth / Wi-Fi Direct / Nearby implementation
 * without touching any fragment or adapter.
 *
 * Implementations MUST NOT fabricate devices. Empty is a valid, expected state.
 */
interface MeshDataSource {
    val nodes: StateFlow<List<MeshNode>>
    val scanState: StateFlow<ScanState>

    /** Begin listening for devices (advertise + discover). Idempotent. */
    fun startScan()

    /** Stop listening. Idempotent. */
    fun stopScan()
}

/**
 * Discovery lifecycle states surfaced to the UI so the Devices screen can show
 * accurate empty/loading/found copy instead of a blank list.
 */
enum class ScanState {
    /** Scan has never been started this session. */
    IDLE,

    /** Actively advertising/discovering — show a "Scanning..." indicator. */
    SCANNING,

    /** Scanning finished (or is still live) and at least one peer is known. */
    FOUND,

    /** Scan has run but no peers were discovered. Show empty-state copy. */
    NONE
}

/**
 * Stub data source used until the real Bluetooth / Wi-Fi Direct transport is wired.
 * Returns zero devices, and only transitions IDLE -> SCANNING -> NONE so the UI's
 * empty-state copy is exercised correctly.
 *
 * Critically: this does NOT fabricate demo peers. Empty means empty.
 */
class EmptyMeshDataSource : MeshDataSource {
    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val _scanState = MutableStateFlow(ScanState.IDLE)
    override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    override fun startScan() {
        _scanState.value = ScanState.SCANNING
        // With no real radio wired up there is nothing to find; settle into NONE so the
        // UI can show "No nearby mesh nodes / turn on Bluetooth or Wi-Fi to scan".
        _scanState.value = ScanState.NONE
    }

    override fun stopScan() {
        _scanState.value = ScanState.IDLE
    }
}
