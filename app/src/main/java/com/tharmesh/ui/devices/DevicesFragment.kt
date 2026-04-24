package com.tharmesh.ui.devices

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tharmesh.ui.dashboard.NearbyEntry
import tharmesh.app.R

/**
 * Nearby Mesh Nodes list with scan button + empty state.
 *
 * Scan currently just toggles button label — real hookup waits for the Nearby
 * transport to publish PeerFound events.
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
        val items = listOf(
            FullDevice("Arjun Verma", "2.5m", NearbyEntry.Quality.STRONG, R.drawable.bg_avatar),
            FullDevice("Meera Joshi", "5.1m", NearbyEntry.Quality.STRONG, R.drawable.bg_avatar_green),
            FullDevice("Rohit Singh", "7.3m", NearbyEntry.Quality.GOOD, R.drawable.bg_avatar_amber),
            FullDevice("Kavya Sharma", "8.8m", NearbyEntry.Quality.GOOD, R.drawable.bg_avatar),
            FullDevice("Priya Iyer", "12.4m", NearbyEntry.Quality.FAIR, R.drawable.bg_avatar_green),
            FullDevice("Ibrahim Khan", "18.7m", NearbyEntry.Quality.WEAK, R.drawable.bg_avatar_amber)
        )
        recycler.adapter = DevicesAdapter(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        scan.setOnClickListener {
            scan.isEnabled = false
            scan.setText(R.string.devices_scanning)
            scan.postDelayed({
                scan.isEnabled = true
                scan.setText(R.string.devices_scan)
            }, 1200L)
        }
    }
}

data class FullDevice(
    val name: String,
    val distance: String,
    val quality: NearbyEntry.Quality,
    val avatarBg: Int
)
