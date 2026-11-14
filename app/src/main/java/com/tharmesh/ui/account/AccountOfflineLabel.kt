// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.ui.account

import android.content.Context
import android.net.ConnectivityManager
import com.tharmesh.account.CloudSessionStore
import com.tharmesh.account.LocalAuth
import com.tharmesh.account.OfflineMode
import com.tharmesh.account.OfflineModeResolver
import com.tharmesh.data.UserPrefs
import tharmesh.app.R

object AccountOfflineLabel {
    fun read(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        @Suppress("DEPRECATION")
        val online = cm?.activeNetworkInfo?.isConnected == true
        val mode = OfflineModeResolver.resolve(
            hasNetwork = online,
            hasLocalMesh = UserPrefs.readIdentity(context) != null,
            localAuth = if (UserPrefs.hasProfile(context)) {
                LocalAuth.OFFLINE_AUTHORIZED
            } else {
                CloudSessionStore.readLocal(context)
            },
            cloud = CloudSessionStore.readCloud(context)
        )
        val id = when (mode) {
            OfflineMode.ONLINE_SESSION -> R.string.account_status_online
            OfflineMode.OFFLINE_AUTHORIZED -> R.string.account_status_offline_authorized
            OfflineMode.TOKEN_EXPIRED_STILL_LOCAL -> R.string.account_status_token_expired
            OfflineMode.CLOUD_UNREACHABLE -> R.string.account_status_unreachable
            OfflineMode.CLOUD_SIGNED_OUT -> R.string.account_status_signed_out
            OfflineMode.NEEDS_SIGN_IN -> R.string.account_status_needs_sign_in
        }
        return context.getString(id)
    }
}
