// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.
package com.tharmesh.ui.about

/**
 * Stage 10.1 — pure-logic helper for the About / System info card.
 *
 * Sanitizes the raw `BuildConfig` values into display-ready strings so the
 * activity layout never has to reason about empty / null / placeholder
 * inputs, and the unit-test suite can verify the formatting rules without
 * pulling in Android.
 *
 * Rules:
 *   - [version]  : trimmed; `"—"` if blank.
 *   - [buildType]: trimmed + lower-cased; `"—"` if blank.
 *   - [commit]   : trimmed; treated as missing if blank or already `"—"`,
 *                  in which case the dash is preserved verbatim. Long
 *                  hashes (40 chars) are truncated to the conventional
 *                  short 7-char form for display.
 */
object AboutBuildInfo {

    private const val MISSING = "—"
    private const val SHORT_HASH_LEN = 7

    data class Display(
        val version: String,
        val buildType: String,
        val commit: String,
    )

    fun format(version: String?, buildType: String?, commit: String?): Display {
        return Display(
            version = sanitize(version),
            buildType = sanitize(buildType?.lowercase()),
            commit = sanitizeCommit(commit),
        )
    }

    private fun sanitize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (trimmed.isEmpty()) MISSING else trimmed
    }

    private fun sanitizeCommit(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == MISSING) return MISSING
        // Truncate full 40-char SHA to the conventional 7-char short form
        // for the system-info card. Already-short hashes are returned
        // unchanged.
        return if (trimmed.length > SHORT_HASH_LEN) {
            trimmed.substring(0, SHORT_HASH_LEN)
        } else {
            trimmed
        }
    }
}
