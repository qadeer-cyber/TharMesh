// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.legal

import androidx.annotation.StringRes
import tharmesh.app.R

/**
 * Stage 8.3 — single source of truth mapping each legal-document key
 * to its title + body string resources. Both the in-app
 * [LegalDocActivity] (read-only viewer) and the first-launch
 * [TermsActivity] gate route through this enum so the body text the
 * user accepts at the gate is byte-identical to the body text they
 * see when they re-open Terms from Settings.
 *
 * Stable ordinals NOT relied upon — callers pass the [name] across
 * intent extras, which is what we match on at the receiving end.
 */
enum class LegalDocBodies(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int
) {
    /**
     * Required acceptance gate body. Bump
     * [com.tharmesh.data.UserPrefs.LEGAL_TERMS_VERSION_CURRENT] whenever
     * [R.string.legal_doc_terms_body] changes to re-prompt users.
     */
    TERMS(R.string.legal_doc_terms_title, R.string.legal_doc_terms_body),

    /** Informational only. No acceptance is recorded for Privacy. */
    PRIVACY(R.string.legal_doc_privacy_title, R.string.legal_doc_privacy_body),

    /**
     * Informational only. The Disclaimer is intentionally NOT part of
     * the acceptance gate \u2014 it is a transparency screen reachable
     * any time from Settings \u2192 Legal \u2192 Disclaimer.
     */
    DISCLAIMER(R.string.legal_doc_disclaimer_title, R.string.legal_doc_disclaimer_body);

    companion object {
        /**
         * Resolve a [LegalDocBodies] from its [name] string, or `null` if
         * the name doesn't match a known doc. Used by [LegalDocActivity]
         * to decode its intent extra defensively (an unrecognised key
         * means we close the activity rather than crash with an enum
         * mismatch).
         */
        @JvmStatic
        fun fromKeyOrNull(key: String?): LegalDocBodies? {
            if (key.isNullOrBlank()) return null
            return values().firstOrNull { it.name == key }
        }
    }
}
