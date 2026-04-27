// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.legal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.data.UserPrefs
import tharmesh.app.R
import java.util.Date

/**
 * Stage 8.3 — read-only viewer for Terms of Use, Privacy Policy, and
 * About / Disclaimer. The doc to render is supplied via [EXTRA_DOC_KEY]
 * (the [LegalDocBodies] enum name); unknown keys finish the activity
 * without crashing.
 *
 * Reachable from:
 *  - Settings → Help → Terms of Use / Privacy Policy / About
 *  - The first-launch [TermsActivity] also re-uses the same body strings
 *    so what the user sees at the gate is byte-identical to the
 *    in-app version.
 *
 * Activity has no edit affordance — the only way to indicate
 * acceptance is through [TermsActivity], which writes via
 * [UserPrefs.markTermsAccepted].
 */
class LegalDocActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legal_doc)

        val key = intent?.getStringExtra(EXTRA_DOC_KEY)
        val doc = LegalDocBodies.fromKeyOrNull(key)
        if (doc == null) {
            finish()
            return
        }

        findViewById<TextView>(R.id.legal_title).setText(doc.titleRes)
        findViewById<TextView>(R.id.legal_body).setText(doc.bodyRes)
        findViewById<ImageView>(R.id.legal_back).setOnClickListener { finish() }

        val acceptedAtView = findViewById<TextView>(R.id.legal_accepted_at)
        if (doc == LegalDocBodies.TERMS) {
            val acceptedAt = UserPrefs.termsAcceptedAtMs(this)
            acceptedAtView.visibility = View.VISIBLE
            acceptedAtView.text = if (acceptedAt > 0L) {
                val formatted = DateFormat.getLongDateFormat(this).format(Date(acceptedAt))
                getString(R.string.legal_doc_terms_accepted_at_fmt, formatted)
            } else {
                getString(R.string.legal_doc_terms_not_yet_accepted)
            }
        } else {
            acceptedAtView.visibility = View.GONE
        }
    }

    companion object {
        const val EXTRA_DOC_KEY = "legal_doc_key"

        @JvmStatic
        fun intent(context: Context, doc: LegalDocBodies): Intent {
            return Intent(context, LegalDocActivity::class.java)
                .putExtra(EXTRA_DOC_KEY, doc.name)
        }
    }
}
