// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

/**
 * Never show a device as linked until the server directory fetch succeeded.
 */
object DeviceListPresentation {

    const val LABEL_LINKED = "linked"
    const val LABEL_UNCONFIRMED = "unconfirmed"
    const val LABEL_LOADING = "loading"
    const val LABEL_REVOKED = "revoked"

    fun rowLabel(cloudConfirmed: Boolean?, revoked: Boolean): String {
        if (cloudConfirmed == null) return LABEL_LOADING
        if (cloudConfirmed != true) return LABEL_UNCONFIRMED
        return if (revoked) LABEL_REVOKED else LABEL_LINKED
    }

    fun mayShowLinkedPendingEnrollment(cloudConfirmed: Boolean?): Boolean = cloudConfirmed == true
}
