package com.tharmesh.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
