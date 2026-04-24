package com.tharmesh.ui.dashboard

import androidx.annotation.DrawableRes

/** UI model for a single nearby-device row on the dashboard. */
data class NearbyEntry(
    val name: String,
    val distance: String,
    val quality: Quality,
    @DrawableRes val avatarBg: Int
) {
    enum class Quality { STRONG, GOOD, FAIR, WEAK }
}
