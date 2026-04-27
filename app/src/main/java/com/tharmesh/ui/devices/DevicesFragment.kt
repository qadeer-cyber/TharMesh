package com.tharmesh.ui.devices

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tharmesh.TharMeshApp
import com.tharmesh.mesh.MeshNode
import com.tharmesh.mesh.ScanState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tharmesh.app.R

/**
 * Nearby Mesh Nodes tab.
 *
 * Renders the live device list from the real [com.tharmesh.mesh.MeshDataSource] — no
 * demo data, no fabricated fluctuation. The screen has four display states driven by
 * [com.tharmesh.mesh.ScanState]:
 *   * IDLE     — "Ready to scan" copy + Scan button
 *   * SCANNING — "Scanning..." copy + disabled button
 *   * NONE     — "No nearby mesh nodes / turn on Bluetooth/Wi-Fi" empty card
 *   * FOUND    — live list of devices
 */
class DevicesFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_devices, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler: RecyclerView = view.findViewById(R.id.recycler_devices)
        val empty: View = view.findViewById(R.id.empty_state)
        val emptyTitle: TextView = view.findViewById(R.id.empty_title)
        val emptySub: TextView = view.findViewById(R.id.empty_sub)
        val scan: Button = view.findViewById(R.id.button_scan)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        val adapter = DevicesAdapter()
        recycler.adapter = adapter

        val directory = TharMeshApp.get().directory

        // Stage 9.2 — brand-header status dot. Connected when at least one
        // peer is online; Searching when transport is up but the directory
        // is empty; Offline otherwise.
        val brandDot = view.findViewById<com.tharmesh.ui.widget.PulsingDot>(R.id.dot_brand_status)

        viewLifecycleOwner.lifecycleScope.launch {
            directory.nodes.collectLatest { list ->
                val ranked = list.sortedWith(
                    compareByDescending<MeshNode> { it.online }
                        .thenByDescending { it.score() }
                )
                adapter.submit(ranked)
                val onlineCount = list.count { it.online }
                // Go through SystemStatus.resolveForUi so a perms-revoked-
                // at-runtime state correctly flips the dot to Offline.
                brandDot?.setStatus(
                    com.tharmesh.ui.system.SystemStatus.resolveForUi(
                        requireContext(),
                        peerCount = onlineCount
                    )
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            directory.scanState.collectLatest { state ->
                val nodesEmpty = directory.nodes.value.isEmpty()
                when {
                    !nodesEmpty -> {
                        empty.visibility = View.GONE
                        recycler.visibility = View.VISIBLE
                        scan.isEnabled = true
                        scan.setText(R.string.devices_scan)
                    }
                    state == ScanState.SCANNING -> {
                        empty.visibility = View.VISIBLE
                        recycler.visibility = View.GONE
                        emptyTitle.setText(R.string.devices_scanning)
                        emptySub.setText(R.string.devices_scanning_sub)
                        scan.isEnabled = false
                        scan.setText(R.string.devices_scanning)
                    }
                    state == ScanState.IDLE -> {
                        empty.visibility = View.VISIBLE
                        recycler.visibility = View.GONE
                        emptyTitle.setText(R.string.devices_idle)
                        emptySub.setText(R.string.devices_idle_sub)
                        scan.isEnabled = true
                        scan.setText(R.string.devices_scan)
                    }
                    else -> {
                        empty.visibility = View.VISIBLE
                        recycler.visibility = View.GONE
                        emptyTitle.setText(R.string.devices_empty)
                        emptySub.setText(R.string.devices_empty_sub)
                        scan.isEnabled = true
                        scan.setText(R.string.devices_scan)
                    }
                }
            }
        }

        scan.setOnClickListener { directory.startScan() }
    }
}
