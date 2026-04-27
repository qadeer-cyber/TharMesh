package com.tharmesh.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tharmesh.TharMeshApp
import com.tharmesh.mesh.MeshNode
import com.tharmesh.ui.widget.MeshGraphView
import com.tharmesh.ui.widget.SignalBarsView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tharmesh.app.R

/**
 * Home / Mesh Dashboard: status card, signal bars, mesh graph, nearby devices.
 *
 * Nearby devices now come from [com.tharmesh.mesh.NearbyDirectory] so the top-4
 * preview matches what the Devices tab and picker sheet show in real time.
 */
class DashboardFragment : Fragment() {

    private lateinit var adapter: NearbyAdapter
    private val entries: MutableList<NearbyEntry> = mutableListOf()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler: RecyclerView = view.findViewById(R.id.recycler_nearby)
        val emptyView: View = view.findViewById(R.id.nearby_empty)
        val graph: MeshGraphView = view.findViewById(R.id.mesh_graph)
        val signalBars: SignalBarsView? = view.findViewById(R.id.network_signal_bars)
        val statusTitle: TextView = view.findViewById(R.id.mesh_status_title)
        val statusSub: TextView = view.findViewById(R.id.mesh_status_sub)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.isNestedScrollingEnabled = false
        adapter = NearbyAdapter(entries)
        recycler.adapter = adapter

        val directory = TharMeshApp.get().directory
        viewLifecycleOwner.lifecycleScope.launch {
            directory.nodes.collectLatest { nodes ->
                val top = nodes.asSequence()
                    .filter { it.online }
                    .sortedByDescending { it.score() }
                    .take(4)
                    .map { it.toNearbyEntry() }
                    .toList()
                entries.clear()
                entries.addAll(top)
                adapter.notifyDataSetChanged()
                val isEmpty = top.isEmpty()
                recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
                emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE

                val onlineCount = nodes.count { it.online }
                graph.setPeerCount(onlineCount)
                // Stage 9.2 — feed the same online count into the signal-bars
                // indicator so it actually animates instead of showing 4
                // permanently-dim bars (the old self-animating implementation
                // was replaced by a peer-count-driven one).
                signalBars?.setPeerCount(onlineCount)
                if (onlineCount == 0) {
                    statusTitle.setText(R.string.dash_searching_title)
                    statusSub.setText(R.string.dash_searching_sub)
                } else {
                    statusTitle.setText(R.string.dash_connected_title)
                    statusSub.text = getString(R.string.dash_connected_sub_fmt, onlineCount)
                }
            }
        }
    }

    private fun MeshNode.toNearbyEntry(): NearbyEntry {
        val q = when (quality()) {
            MeshNode.Quality.STRONG -> NearbyEntry.Quality.STRONG
            MeshNode.Quality.GOOD -> NearbyEntry.Quality.GOOD
            MeshNode.Quality.FAIR -> NearbyEntry.Quality.FAIR
            MeshNode.Quality.WEAK -> NearbyEntry.Quality.WEAK
        }
        return NearbyEntry(name, distanceLabel(), q, avatarBg)
    }
}
