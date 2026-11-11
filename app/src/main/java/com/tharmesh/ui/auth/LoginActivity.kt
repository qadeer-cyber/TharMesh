// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.ui.auth

import com.tharmesh.ui.theme.Appearance
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tharmesh.app.BuildConfig
import tharmesh.app.R
import com.tharmesh.TharMeshApp
import com.tharmesh.account.AuthBackend
import com.tharmesh.account.RegistrationGate
import com.tharmesh.auth.GoogleAuthService
import com.tharmesh.data.UserPrefs
import com.tharmesh.data.UserProfile
import com.tharmesh.ui.account.AccountFlowActivity
import com.tharmesh.ui.main.MainActivity
import com.tharmesh.ui.onboarding.OnboardingActivity

/**
 * First-run gate. An existing local profile, including older installs that
 * never had a cloud account, goes straight in. A fresh install must register.
 * If the account server is not configured, this screen says so and does not
 * create a local stand-in account.
 */
class LoginActivity : AppCompatActivity() {

    private val googleAuth by lazy { GoogleAuthService(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.applyTheme(this)
        super.onCreate(savedInstanceState)
        if (UserPrefs.hasProfile(this)) {
            goToNext()
            return
        }
        setContentView(R.layout.activity_login)

        val status: TextView = findViewById(R.id.login_status)
        val googleBtn: Button = findViewById(R.id.login_google_button)
        val anonBtn: Button = findViewById(R.id.login_anonymous_button)
        val createBtn: Button = findViewById(R.id.login_create_button)
        val signInBtn: Button = findViewById(R.id.login_signin_button)
        val cloudReady = AuthBackend.configured(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val gate = RegistrationGate.decide(hasExistingProfile = false, cloudConfigured = cloudReady)
        anonBtn.visibility = android.view.View.GONE
        anonBtn.setOnClickListener {
            status.setText(R.string.login_registration_required)
        }
        if (gate == RegistrationGate.Decision.BLOCKED_UNCONFIGURED) {
            status.setText(R.string.account_error_not_configured)
            val blocked = android.view.View.OnClickListener {
                status.setText(R.string.account_error_not_configured)
            }
            createBtn.setOnClickListener(blocked)
            signInBtn.setOnClickListener(blocked)
            googleBtn.setOnClickListener(blocked)
            return
        }
        createBtn.setOnClickListener {
            startActivity(AccountFlowActivity.intent(this, AccountFlowActivity.Screen.CREATE))
        }
        signInBtn.setOnClickListener {
            startActivity(AccountFlowActivity.intent(this, AccountFlowActivity.Screen.SIGN_IN))
        }
        if (!googleAuth.isAvailable) {
            googleBtn.isEnabled = false
            status.setText(R.string.login_missing_oauth)
        }
        googleBtn.setOnClickListener {
            val intent = googleAuth.silentSignInIntent()
            if (intent != null) {
                startActivityForResult(intent, REQUEST_GOOGLE_SIGN_IN)
            } else {
                status.setText(R.string.login_missing_oauth)
            }
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
        val freshMesh = UserPrefs.readIdentity(this) == null
        if (freshMesh && profile.authProvider == UserPrefs.PROVIDER_GOOGLE) {
            // Google sign-in must not mint a mesh key by itself.
            UserPrefs.setIdentityHeld(this, true)
        }
        UserPrefs.saveProfile(this, profile)
        if (freshMesh && profile.authProvider == UserPrefs.PROVIDER_GOOGLE) {
            startActivity(AccountFlowActivity.intent(this, AccountFlowActivity.Screen.SECURE_IDENTITY))
            finish()
            return
        }
        // Stage 8.4 \u2014 do NOT start the mesh *transport* here (i.e. don't
        // call ensureMeshStarted): if a returning user has Nearby
        // permissions already granted but has not yet accepted the
        // current Terms version, starting the transport would mean we
        // begin advertising / discovering / processing inbound bundles
        // before the compliance gate. The transport is now started
        // exclusively in [TermsActivity.routeOnward] (via the targeted
        // activities) and [MainActivity.onCreate], both of which only
        // run once [UserPrefs.hasAcceptedCurrentTerms] is true.
        //
        // We DO still need to construct the mesh graph (engine,
        // repository, etc.) here so that downstream screens like
        // [com.tharmesh.ui.onboarding.OnboardingActivity] can access
        // [TharMeshApp.repository] during onboarding step 3 (QR scan).
        // [TharMeshApp.ensureMeshReady] is permission-safe and
        // transport-safe \u2014 it only constructs objects in memory
        // and starts no advertising / discovery.
        TharMeshApp.get().ensureMeshReady()
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
