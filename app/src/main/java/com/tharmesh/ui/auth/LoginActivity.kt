// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.ui.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tharmesh.app.R
import com.tharmesh.TharMeshApp
import com.tharmesh.auth.GoogleAuthService
import com.tharmesh.data.UserPrefs
import com.tharmesh.data.UserProfile
import com.tharmesh.ui.main.MainActivity
import com.tharmesh.ui.onboarding.OnboardingActivity

/**
 * First-run gate. If a profile already exists, skip straight to [MainActivity]. Otherwise
 * show "Continue with Google" (if `BuildConfig.GOOGLE_WEB_CLIENT_ID` is configured) and
 * "Continue anonymously" (always available — kept so the app still runs without OAuth
 * credentials during development).
 */
class LoginActivity : AppCompatActivity() {

    private val googleAuth by lazy { GoogleAuthService(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (UserPrefs.hasProfile(this)) {
            goToNext()
            return
        }
        setContentView(R.layout.activity_login)

        val status: TextView = findViewById(R.id.login_status)
        val googleBtn: Button = findViewById(R.id.login_google_button)
        val anonBtn: Button = findViewById(R.id.login_anonymous_button)

        if (googleAuth.isAvailable) {
            googleBtn.isEnabled = true
            status.text = getString(R.string.login_ready)
            // Silent sign-in if the user was already signed in via Google earlier.
            val cached = googleAuth.lastSignedInAccount(this)
            if (cached != null) {
                val sub = cached.id
                if (sub != null) {
                    finishWith(
                        UserProfile(
                            userId = UserPrefs.userIdFromGoogleSub(sub),
                            username = cached.displayName?.takeIf { it.isNotBlank() }
                                ?: (cached.email ?: "user"),
                            email = cached.email,
                            avatarUrl = cached.photoUrl?.toString(),
                            authProvider = UserPrefs.PROVIDER_GOOGLE
                        )
                    )
                    return
                }
            }
        } else {
            googleBtn.isEnabled = false
            status.text = getString(R.string.login_missing_oauth)
        }

        googleBtn.setOnClickListener {
            val intent = googleAuth.silentSignInIntent()
            if (intent != null) {
                startActivityForResult(intent, REQUEST_GOOGLE_SIGN_IN)
            } else {
                status.text = getString(R.string.login_missing_oauth)
            }
        }

        anonBtn.setOnClickListener {
            val profile = UserPrefs.ensureProfile(this)
            finishWith(profile.copy(authProvider = UserPrefs.PROVIDER_ANONYMOUS))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_GOOGLE_SIGN_IN) {
            val result = googleAuth.profileFromIntent(data)
            val status: TextView = findViewById(R.id.login_status)
            result.onSuccess { profile ->
                finishWith(profile)
            }.onFailure { e ->
                status.text = getString(R.string.login_failed_fmt, e.message ?: "")
            }
        }
    }

    private fun finishWith(profile: UserProfile) {
        UserPrefs.saveProfile(this, profile)
        TharMeshApp.get().ensureMeshStarted()
        goToNext()
    }

    /**
     * Stage 7 PR C — fresh installs (anonymous path with auto-generated
     * `user-<8hex>` username) drop into [OnboardingActivity] for the
     * three-step name + mesh + first-contact flow before reaching
     * [MainActivity]. Existing installs and Google-signed users with a
     * real display name are grandfathered through
     * [UserPrefs.shouldShowOnboarding].
     */
    private fun goToNext() {
        // Stage 8.3 — Pakistan compliance Terms-of-Use gate. Every fresh
        // install AND every existing install on the first launch after
        // upgrade lands on TermsActivity until the user explicitly
        // accepts. Onward routing (Onboarding vs. Main) happens inside
        // TermsActivity so the gate is the single chokepoint and we can
        // trust hasAcceptedCurrentTerms == true everywhere downstream.
        val target = when {
            !UserPrefs.hasAcceptedCurrentTerms(this) ->
                com.tharmesh.ui.legal.TermsActivity::class.java
            UserPrefs.shouldShowOnboarding(this) ->
                OnboardingActivity::class.java
            else ->
                MainActivity::class.java
        }
        val next = Intent(this, target)
        next.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(next)
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        private const val REQUEST_GOOGLE_SIGN_IN = 7001
    }
}
