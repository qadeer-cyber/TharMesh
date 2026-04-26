package com.tharmesh.ui.diagnostics

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.tharmesh.TharMeshApp
import com.tharmesh.diagnostics.DiagnosticsCollector
import com.tharmesh.diagnostics.FieldTestMode
import tharmesh.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stage 5.1 — Field Test Mode diagnostics screen. Read-only view of the
 * in-memory [DiagnosticsCollector] counters + the last N [com.tharmesh.dtn.MeshEvent]s,
 * with Refresh / Clear / Share actions. No new XML theme / color resources,
 * no new dependencies; reuses `TmCard`, `TmButton.Ghost`, `TmButton.Primary`,
 * and the `Text.*` styles already present.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var counters: TextView
    private lateinit var recent: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1_000L)
        }
    }

    private val collector: DiagnosticsCollector
        get() = (application as TharMeshApp).diagnostics

    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        counters = findViewById(R.id.diagnostics_counters)
        recent = findViewById(R.id.diagnostics_recent)

        findViewById<Button>(R.id.button_refresh).setOnClickListener { render() }
        findViewById<Button>(R.id.button_clear).setOnClickListener {
            collector.reset()
            render()
        }
        findViewById<Button>(R.id.button_share).setOnClickListener { share() }

        // Stage 5.3 — Field Test retry-tuning toggles. We deliberately apply
        // a radio-style mutual exclusion: turning one on auto-disables the
        // other, both in the prefs (so resolveRetryConfig() sees a clean
        // pick) and in the UI (so the user sees the picked state).
        val backoff = findViewById<SwitchCompat>(R.id.switch_disable_backoff)
        val highFreq = findViewById<SwitchCompat>(R.id.switch_force_high_freq)
        backoff.isChecked = FieldTestMode.isBackoffDisabled(this)
        highFreq.isChecked = FieldTestMode.isHighFrequencyForced(this)
        val backoffListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            FieldTestMode.setBackoffDisabled(this, checked)
            if (checked && highFreq.isChecked) {
                highFreq.isChecked = false
                FieldTestMode.setHighFrequencyForced(this, false)
            }
        }
        val highFreqListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            FieldTestMode.setHighFrequencyForced(this, checked)
            if (checked && backoff.isChecked) {
                backoff.isChecked = false
                FieldTestMode.setBackoffDisabled(this, false)
            }
        }
        backoff.setOnCheckedChangeListener(backoffListener)
        highFreq.setOnCheckedChangeListener(highFreqListener)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        val s = collector.snapshot()
        counters.text = buildString {
            append("uptime         ").append(formatUptime(s.uptimeMs)).append('\n')
            append("last event     ").append(if (s.lastEventAt == 0L) "—" else ts.format(Date(s.lastEventAt))).append('\n')
            append("peers found    ").append(s.peersFound).append('\n')
            append("peers online   ").append(s.peersCurrentlyConnected)
                .append(" (+").append(s.peersConnected).append(" / -").append(s.peersDisconnected).append(")\n")
            append("bundles SENDING  ").append(s.bundlesSending).append('\n')
            append("bundles SENT     ").append(s.bundlesSent).append('\n')
            append("bundles DELIVERED").append(' ').append(s.bundlesDelivered).append('\n')
            append("bundles ACKED    ").append(s.bundlesAcked).append('\n')
            append("bundles READ     ").append(s.bundlesRead).append('\n')
            append("bundles FAILED   ").append(s.bundlesFailed).append('\n')
            append("retry attempts   ").append(s.retryAttempts).append('\n')
            append("peer churn supp. ").append(s.peerChurnEvents).append('\n')
            append("send rejected    ").append(s.sendRejected).append('\n')
            append("send paced       ").append(s.sendPaced).append('\n')
            append("ttl expired drops").append(' ').append(s.ttlExpiredDrops).append('\n')
            append("stuck SENDING rec").append(' ').append(s.stuckSendingRecovered).append('\n')
            append("retry supp (offline) ").append(s.retrySuppressedNoPeers).append('\n')
            // Stage 7 PR E — local-only growth counters. Read from
            // SharedPreferences via [com.tharmesh.data.GrowthMetrics]; no
            // network or backend involvement.
            val g = com.tharmesh.data.GrowthMetrics.snapshot(this@DiagnosticsActivity)
            append("growth\n")
            append("  contacts added   ").append(g.contactsAdded).append('\n')
            append("  invites sent     ").append(g.invitesSent).append('\n')
            append("  invites accepted ").append(g.invitesAccepted).append('\n')
            append("  chats started    ").append(g.chatsStarted).append('\n')
            append("relay forwards   ").append(s.relayForwards)
                .append("  (").append(formatBytes(s.relayedBytesTotal)).append(")\n")
            val perPeer = collector.relayedBytesByPeer()
            if (perPeer.isEmpty()) {
                append("relayed by peer  —")
            } else {
                append("relayed by peer")
                val forwards = collector.relayForwardsByPeer()
                for ((peerId, bytes) in perPeer) {
                    append("\n  ")
                        .append(truncatePeerId(peerId))
                        .append(' ')
                        .append(formatBytes(bytes))
                        .append(" / ")
                        .append(forwards[peerId] ?: 0L)
                        .append(" fwd")
                }
            }
        }
        val events = collector.recentEvents()
        recent.text = if (events.isEmpty()) {
            getString(R.string.diagnostics_recent_empty)
        } else {
            val sb = StringBuilder(events.size * 48)
            // Show newest-first so latest events are visible without scrolling.
            for (i in events.indices.reversed()) {
                val e = events[i]
                sb.append(ts.format(Date(e.timestampMs)))
                    .append("  ")
                    .append(e.kind)
                    .append("  ")
                    .append(e.detail)
                    .append('\n')
            }
            sb.toString()
        }
    }

    private fun share() {
        val body = collector.exportJson()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_share_subject))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_share)))
    }

    private fun formatUptime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes.toDouble() / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun truncatePeerId(peerId: String): String =
        if (peerId.length <= 12) peerId else peerId.substring(0, 12) + "…"
}
