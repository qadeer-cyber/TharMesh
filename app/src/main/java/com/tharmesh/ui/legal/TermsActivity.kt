// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.legal

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.data.UserPrefs
import com.tharmesh.ui.auth.LoginActivity
import com.tharmesh.ui.main.MainActivity
import com.tharmesh.ui.onboarding.OnboardingActivity
import tharmesh.app.R

/**
 * Stage 8.3 — Pakistan compliance Terms-of-Use gate. Routed to from
 * [LoginActivity.goToNext] when [UserPrefs.hasAcceptedCurrentTerms]
 * returns false (every fresh install, AND every existing install on
 * the first launch after upgrading to a build that carries this gate).
 *
 * The body text comes from [LegalDocBodies.TERMS] so the gate copy is
 * byte-identical to the in-app re-read at Settings → Help → Terms of Use.
 *
 * On Accept:
 *   - [UserPrefs.markTermsAccepted] persists the accepted version code +
 *     wall-clock timestamp.
 *   - The activity routes onward to [OnboardingActivity] (first installs)
 *     or [MainActivity] (existing installs) depending on
 *     [UserPrefs.shouldShowOnboarding].
 *
 * On Decline:
 *   - Toast a short reason and finish the app entirely. This guarantees
 *     no part of TharMesh's mesh / messaging surface is reachable
 *     without explicit acceptance — including via deep-link
 *     navigation from a notification or shortcut.
 */
class TermsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terms_gate)

        findViewById<Button>(R.id.terms_gate_accept).setOnClickListener {
            UserPrefs.markTermsAccepted(this)
            routeOnward()
        }
        findViewById<Button>(R.id.terms_gate_decline).setOnClickListener {
            Toast.makeText(
                this,
                R.string.legal_terms_gate_decline_toast,
                Toast.LENGTH_LONG
            ).show()
            // Finish this activity AND tell the system to take the task
            // off the back stack, so a back-press from Recents does not
            // resurrect the gate without re-launching the app.
            finishAffinity()
        }
    }

    private fun routeOnward() {
        val target = if (UserPrefs.shouldShowOnboarding(this)) {
            OnboardingActivity::class.java
        } else {
            MainActivity::class.java
        }
        val next = Intent(this, target)
        next.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(next)
        setResult(Activity.RESULT_OK)
        finish()
    }
}
