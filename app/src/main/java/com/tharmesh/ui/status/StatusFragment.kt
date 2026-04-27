package com.tharmesh.ui.status

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.tharmesh.TharMeshApp
import com.tharmesh.ui.widget.applyPremiumPress
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tharmesh.app.R

/**
 * Status tab — Emergency Broadcast + live network health.
 *
 * SOS now actually fans out a bundle to every online node in the
 * [com.tharmesh.mesh.NearbyDirectory] via [com.tharmesh.data.MessageRepository.broadcastSos].
 * Result is shown on a pulsing red card ("SOS sent to N nodes") so the user sees the
 * broadcast actually happened.
 */
class StatusFragment : Fragment() {

    private var pulseAnim: ObjectAnimator? = null
    private lateinit var sosResult: TextView
    private lateinit var sosResultCard: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_status, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val batteryStat: View = view.findViewById(R.id.health_battery)
        val meshStat: View = view.findViewById(R.id.health_mesh)
        val storageStat: View = view.findViewById(R.id.health_storage)
        // Real battery level via BatteryManager; "—" for mesh/storage until we wire real
        // counters. No fabricated percentages.
        bindHealthStat(batteryStat, R.drawable.ic_battery, readBatteryLevelPercent(), R.string.status_battery_label)
        bindHealthStat(meshStat, R.drawable.ic_wifi, "0 nodes", R.string.status_network_label)
        bindHealthStat(storageStat, R.drawable.ic_storage, "—", R.string.status_storage_label)

        sosResultCard = view.findViewById(R.id.sos_result_card)
        sosResult = view.findViewById(R.id.sos_result_text)
        sosResultCard.visibility = View.GONE

        val sos: Button = view.findViewById(R.id.button_sos)
        sos.setOnClickListener { triggerSos() }
        // Stage 9.2 — strong-press feedback on the SOS CTA.
        sos.applyPremiumPress()

        // Stage 9.2 — perception layer wiring: brand status dot, animated
        // signal bars, and ambient breathing pulse on the SOS card glow
        // wrapper. All driven from the same directory.nodes flow.
        val brandDot = view.findViewById<com.tharmesh.ui.widget.PulsingDot>(R.id.dot_brand_status)
        val signalBars = view.findViewById<com.tharmesh.ui.widget.SignalBarsView>(R.id.network_signal_bars)
        val sosGlow = view.findViewById<View>(R.id.sos_card_glow_wrap)
        sosGlow?.let { wrap ->
            // Subtle breathing pulse on the red glow halo so the SOS surface
            // reads as "armed and waiting" — independent of the
            // post-broadcast result-card pulse below.
            ObjectAnimator.ofFloat(wrap, "alpha", 0.55f, 1.0f).apply {
                duration = 1800L
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }

        val directory = TharMeshApp.get().directory
        viewLifecycleOwner.lifecycleScope.launch {
            directory.nodes.collectLatest { nodes ->
                val online = nodes.count { it.online }
                meshStat.findViewById<TextView>(R.id.health_value).text =
                    if (online == 1) "1 node" else "$online nodes"
                signalBars?.setPeerCount(online)
                brandDot?.setStatus(
                    when {
                        online > 0 -> com.tharmesh.ui.system.SystemStatus.Connected
                        TharMeshApp.get().isMeshStarted() -> com.tharmesh.ui.system.SystemStatus.Searching
                        else -> com.tharmesh.ui.system.SystemStatus.Offline
                    }
                )
            }
        }
    }

    private fun triggerSos() {
        val app = TharMeshApp.get()
        if (!app.isMeshStarted()) return
        val targets = app.directory.onlineRanked().map { it.userId }
        if (targets.isEmpty()) {
            showResult(getString(R.string.status_sos_none), error = true)
            return
        }
        showResult(getString(R.string.status_sos_sending), error = false)
        val body = getString(R.string.status_sos_default_body)
        viewLifecycleOwner.lifecycleScope.launch {
            val delivered = app.repository.broadcastSos(body, targets)
            showResult(getString(R.string.status_sos_sent_fmt, delivered), error = false)
        }
    }

    private fun showResult(text: String, error: Boolean) {
        sosResult.text = text
        sosResultCard.setBackgroundResource(
            if (error) R.drawable.bg_card_danger_soft
            else R.drawable.bg_card_danger
        )
        sosResultCard.visibility = View.VISIBLE
        pulseAnim?.cancel()
        pulseAnim = ObjectAnimator.ofFloat(sosResultCard, "alpha", 0.55f, 1.0f).apply {
            duration = 900L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = 3
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    override fun onDestroyView() {
        pulseAnim?.cancel()
        pulseAnim = null
        super.onDestroyView()
    }

    private fun bindHealthStat(stat: View, iconRes: Int, value: String, labelRes: Int) {
        stat.findViewById<ImageView>(R.id.health_icon).setImageResource(iconRes)
        stat.findViewById<TextView>(R.id.health_value).text = value
        stat.findViewById<TextView>(R.id.health_label).setText(labelRes)
    }

    /**
     * Returns the current battery level as "NN%" from [android.os.BatteryManager]. Falls
     * back to "—" when the system broadcast is unavailable so we never show a fabricated
     * percentage.
     */
    private fun readBatteryLevelPercent(): String {
        val intent = requireContext().registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        ) ?: return "—"
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return "—"
        val pct = (level * 100f / scale).toInt().coerceIn(0, 100)
        return "$pct%"
    }
}
