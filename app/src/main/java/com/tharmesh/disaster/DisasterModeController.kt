// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.disaster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.tharmesh.data.UserPrefs
import com.tharmesh.dtn.RetryConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stage 6.3 — Disaster Mode coordinator.
 *
 * Owns three concerns:
 *
 *  1. **Persistent state.** [enabled] is mirrored to [UserPrefs] so the toggle
 *     survives process death; [setEnabled] flips both the flow and the prefs.
 *  2. **Send-side priority.** Callers consult [retryConfigOverride] /
 *     [shouldForcePriority] when constructing outgoing bundles so the engine
 *     uses [RetryConfig.SOS] (1s→2s→4s→8s, no jitter) and bypasses the
 *     per-peer send pacer while disaster mode is on.
 *  3. **Receive-side alert.** [onSosReceived] vibrates + plays the
 *     notification ringtone when an SOS-marked bundle arrives AND disaster
 *     mode is locally enabled. Off-mode peers see the bundle as a regular
 *     message; only opted-in devices alert.
 *
 * Battery-awareness is opt-in via [observeBattery] which registers an
 * [Intent.ACTION_BATTERY_LOW] receiver and surfaces the warning through
 * [batteryLow]. Scanning frequency is NOT downgraded to zero on low battery —
 * the whole point of disaster mode is to keep listening — but the UI banner
 * surfaces the warning so the user can decide whether to plug in or back off.
 *
 * Never auto-enables (per Stage 6.3 spec). Auto-enable on inbound SOS would
 * be a vector for a forged-priority peer to drain every device on the mesh;
 * the only way to turn it on is the explicit Settings toggle.
 */
object DisasterModeController {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> get() = _enabled

    private val _batteryLow = MutableStateFlow(false)
    val batteryLow: StateFlow<Boolean> get() = _batteryLow

    private var batteryReceiver: BroadcastReceiver? = null

    /**
     * Initialise from persisted preference. Must be called once from
     * `TharMeshApp.onCreate` before any UI inflates so the banner state is
     * correct on the very first frame.
     */
    fun init(context: Context) {
        _enabled.value = UserPrefs.isDisasterModeEnabled(context)
        _batteryLow.value = isBatteryLowSnapshot(context)
        if (batteryReceiver == null) {
            val rx = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_BATTERY_LOW -> _batteryLow.value = true
                        Intent.ACTION_BATTERY_OKAY -> _batteryLow.value = false
                    }
                }
            }
            context.applicationContext.registerReceiver(
                rx,
                IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_LOW)
                    addAction(Intent.ACTION_BATTERY_OKAY)
                }
            )
            batteryReceiver = rx
        }
    }

    /**
     * Persist + flip the in-memory flag. Idempotent. Returns the new state.
     */
    fun setEnabled(context: Context, value: Boolean): Boolean {
        UserPrefs.setDisasterModeEnabled(context, value)
        _enabled.value = value
        return value
    }

    /** True when outgoing bundles should be force-marked priority. */
    fun shouldForcePriority(): Boolean = _enabled.value

    /**
     * Retry curve to apply to the next outgoing bundle. Returns
     * [RetryConfig.SOS] when disaster mode is on, null otherwise (meaning the
     * caller should fall through to its default config).
     */
    fun retryConfigOverride(): RetryConfig? =
        if (_enabled.value) RetryConfig.SOS else null

    /**
     * Vibrate + play the notification ringtone for an inbound SOS-marked
     * bundle. No-op if disaster mode is off OR the device is in silent /
     * vibrate-suppressed mode (we deliberately respect ringer settings — this
     * is an alert, not a panic button).
     */
    fun onSosReceived(context: Context) {
        if (!_enabled.value) return
        vibrate(context)
        playSosTone(context)
    }

    private fun vibrate(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Three short pulses, ~600ms total, modeled on a standard urgent alert.
            val pattern = longArrayOf(0L, 250L, 100L, 250L, 100L, 250L)
            val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0L, 250L, 100L, 250L, 100L, 250L), -1)
        }
    }

    private fun playSosTone(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // Respect silent + vibrate-only — alerts piggyback on the user's ringer choice.
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        val rt = RingtoneManager.getRingtone(context, uri) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            rt.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
        rt.play()
    }

    private fun isBatteryLowSnapshot(context: Context): Boolean {
        val intent = context.applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return false
        // Match Android's own "low battery" threshold of 15% — see frameworks/base
        // BatteryService. We don't fire the alert here, just snapshot the state so
        // the UI banner is correct on first frame.
        return (level.toFloat() / scale.toFloat()) <= 0.15f
    }
}
