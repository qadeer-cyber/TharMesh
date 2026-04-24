package com.tharmesh.ui.devices

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tharmesh.TharMeshApp
import com.tharmesh.mesh.MeshNode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tharmesh.app.R

/**
 * Nearby Mesh Nodes tab.
 *
 * Observes [com.tharmesh.mesh.NearbyDirectory] so the list stays in sync with signal /
 * online-state fluctuations from the simulation (and, later, from the real Nearby
 * transport). The scan button currently just flashes its label — the simulation is
 * always running under the hood so there's no "paused" state to resume.
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
        val empty = view.findViewById<View>(R.id.empty_state)
        val scan: Button = view.findViewById(R.id.button_scan)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        val adapter = DevicesAdapter()
        recycler.adapter = adapter

        val directory = TharMeshApp.get().directory
        viewLifecycleOwner.lifecycleScope.launch {
            directory.nodes.collectLatest { list ->
                val ranked = list.sortedWith(
                    compareByDescending<MeshNode> { it.online }
                        .thenByDescending { it.score() }
                )
                adapter.submit(ranked)
                empty.visibility = if (ranked.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        scan.setOnClickListener {
            scan.isEnabled = false
            scan.setText(R.string.devices_scanning)
            scan.postDelayed({
                if (view.isAttachedToWindow) {
                    scan.isEnabled = true
                    scan.setText(R.string.devices_scan)
                }
            }, 1200L)
        }
    }
}
