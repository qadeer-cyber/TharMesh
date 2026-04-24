package com.tharmesh.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tharmesh.app.R

/**
 * Home / Mesh Dashboard: status card, signal bars, mesh graph, nearby devices.
 *
 * NOTE: nearby-device list is currently demo data — real device discovery will
 * be plumbed through TharMeshApp.repository once the Nearby transport emits
 * PeerFound/PeerConnected events up to a dedicated ViewModel.
 */
class DashboardFragment : Fragment() {

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
        recycler.adapter = NearbyAdapter(
            listOf(
                NearbyEntry("Arjun Verma", "2.5m", NearbyEntry.Quality.STRONG, R.drawable.bg_avatar),
                NearbyEntry("Meera Joshi", "5.1m", NearbyEntry.Quality.STRONG, R.drawable.bg_avatar_green),
                NearbyEntry("Rohit Singh", "7.3m", NearbyEntry.Quality.GOOD, R.drawable.bg_avatar_amber),
                NearbyEntry("Kavya Sharma", "8.8m", NearbyEntry.Quality.GOOD, R.drawable.bg_avatar)
            )
        )
    }
}
