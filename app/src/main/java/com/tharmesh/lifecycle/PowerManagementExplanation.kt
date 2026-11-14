// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.lifecycle

/**
 * User-visible explanation of Android power management vs mesh delivery.
 * Copy is mirrored in [tharmesh.app.R.string.mesh_power_doze_explanation].
 */
object PowerManagementExplanation {

    const val DOZE_KEYWORD: String = "Doze"
    const val FOREGROUND_SERVICE_KEYWORD: String = "foreground"

    /**
     * English body shown in Settings → Notifications / mesh help surfaces.
     * Must mention that Doze can defer radio work and that the mesh service
     * uses START_STICKY without holding a wake lock.
     */
    const val BODY_EN: String =
        "When Android enters Doze or battery saver, the system may pause background " +
            "radio work even while TharMesh's foreground mesh service is running. " +
            "TharMesh does not hold a wake lock; it retries on a bounded schedule when " +
            "the app is active again. Delivery is not guaranteed while the device is deeply idle."
}