// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Debug-only: seed local profile, terms, and mesh identity for offline relaunch smoke.

package com.tharmesh.ui.debug

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.data.UserPrefs
import com.tharmesh.data.UserProfile
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.ui.main.MainActivity

/**
 * Seeds an activated local install (no cloud token) and opens [MainActivity].
 * Invoked from [scripts/emulator-0.4-smoke.sh] only.
 */
class OfflineRelaunchSmokeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seedOfflineActivatedInstall()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun seedOfflineActivatedInstall() {
        UserPrefs.saveProfile(
            this,
            UserProfile(
                userId = "offline-smoke-user",
                username = "Offline Smoke",
                authProvider = UserPrefs.PROVIDER_ANONYMOUS
            )
        )
        UserPrefs.markOnboarded(this)
        UserPrefs.markTermsAccepted(this)
        UserPrefs.setIdentityHeld(this, false)
        val mesh = CryptoIdentity.generate()
        UserPrefs.writeIdentity(this, mesh, allowReplace = true)
    }
}
