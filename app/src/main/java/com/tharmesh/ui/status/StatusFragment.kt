package com.tharmesh.ui.status

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import tharmesh.app.R

/**
 * Status tab — emergency broadcast, network alerts, device health.
 *
 * SOS currently shows a toast; wiring it into MeshEngine.broadcastEmergency is
 * left for a follow-up so we don't accidentally spam a live mesh.
 */
class StatusFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_status, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindHealthStat(view.findViewById(R.id.health_battery), R.drawable.ic_battery, "86%", R.string.status_battery_label)
        bindHealthStat(view.findViewById(R.id.health_mesh), R.drawable.ic_wifi, "4 nodes", R.string.status_network_label)
        bindHealthStat(view.findViewById(R.id.health_storage), R.drawable.ic_storage, "128 MB", R.string.status_storage_label)

        view.findViewById<Button>(R.id.button_sos).setOnClickListener {
            Toast.makeText(requireContext(), "SOS broadcast queued — demo only", Toast.LENGTH_LONG).show()
        }
    }

    private fun bindHealthStat(stat: View, iconRes: Int, value: String, labelRes: Int) {
        stat.findViewById<ImageView>(R.id.health_icon).setImageResource(iconRes)
        stat.findViewById<TextView>(R.id.health_value).text = value
        stat.findViewById<TextView>(R.id.health_label).setText(labelRes)
    }
}
