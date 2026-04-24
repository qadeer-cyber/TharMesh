package com.tharmesh.mesh

import androidx.annotation.DrawableRes

/**
 * Runtime view-model for a device visible on the mesh.
 *
 * Until the real Nearby transport starts publishing PeerFound / PeerLost events, the
 * [com.tharmesh.mesh.NearbyDirectory] seeds this from a fixed roster and mutates
 * signal/battery/online over time so the UI feels alive.
 *
 * [score] is the smart-ranking score used to pick relay candidates and to sort the
 * device picker. Higher = better. All inputs are clamped to 0..100 before blending.
 */
data class MeshNode(
    val userId: String,
    val name: String,
    /** Metres, rendered as "2.5m" in the UI. Clamped to [0, 40]. */
    val distance: Float,
    /** 0..100. Drives the chip colour + part of the relay score. */
    val signal: Int,
    /** 0..100. Drives battery-aware relay preference. */
    val battery: Int,
    /** Minutes online in the current session. Clamped to 60 for scoring. */
    val uptimeMinutes: Int,
    val online: Boolean,
    @DrawableRes val avatarBg: Int
) {
    /** 0..100. Higher = prefer for relay / show first in pickers. */
    fun score(): Double {
        if (!online) return 0.0
        val s = signal.coerceIn(0, 100).toDouble()
        val b = battery.coerceIn(0, 100).toDouble()
        val u = (uptimeMinutes.coerceIn(0, 60) / 60.0) * 100.0
        return 0.5 * s + 0.3 * b + 0.2 * u
    }

    fun quality(): Quality = when {
        !online -> Quality.WEAK
        signal >= 75 -> Quality.STRONG
        signal >= 55 -> Quality.GOOD
        signal >= 35 -> Quality.FAIR
        else -> Quality.WEAK
    }

    fun distanceLabel(): String = if (distance >= 10f) "${distance.toInt()}m"
    else String.format("%.1fm", distance)

    enum class Quality { STRONG, GOOD, FAIR, WEAK }
}
