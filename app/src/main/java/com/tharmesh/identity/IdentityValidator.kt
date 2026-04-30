// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.identity

/**
 * Stage 11.1 — single source of truth for "is this string a valid peer
 * identity?".
 *
 * Pre-11.1 the contact-add paths (nearby banner, nearby picker, QR scan,
 * manual invite-code entry, onboarding first-contact) all accepted ANY
 * string as a userId. A peer whose [com.tharmesh.data.UserProfile.userId]
 * was somehow truncated — e.g. a half-written profile from a crashed
 * install, a bogus QR payload, or the user typing a partial invite code
 * into the manual-add dialog — produced a persistent `"user-"` row in
 * `contacts` / `conversations` that could never be chatted with and
 * could never be rediscovered by the same device.
 *
 * [isValidUserId] rejects the four failure modes observed in the field:
 *
 *  1. Blank or whitespace-only input.
 *  2. The anonymous prefix [ANONYMOUS_USER_ID_PREFIX] with an empty,
 *     whitespace-only, or shorter-than-expected suffix (the actual bug —
 *     `"user-"`, `"user- "`, `"user-a"` etc.).
 *  3. Strings shorter than [MIN_USER_ID_LEN] characters — no real
 *     TharMesh userId is this short, and dedup logic that keys on userId
 *     breaks down if arbitrarily short strings collide.
 *  4. Strings containing ASCII control characters — defensive against
 *     QR payloads that survive URL-decoding with embedded control bytes.
 *
 * Everything else passes, including:
 *  - Anonymous IDs: `user-<8+ hex>` (UserPrefs generates `user-<8 hex>`,
 *    so the exact length varies if future code ever widens the suffix).
 *  - Google-derived IDs: `U<12 hex>` (see [com.tharmesh.data.UserPrefs.userIdFromGoogleSub]).
 *  - Opaque 16–64 char IDs (forward-compatible if the userId format
 *    ever changes).
 *
 * Kept pure / referentially transparent so callers can gate-check user
 * input without touching I/O, and unit tests can exercise every branch
 * without a Room database or Android context.
 */
object IdentityValidator {

    /** Prefix emitted by [com.tharmesh.data.UserPrefs.ensureProfile] for anonymous users. */
    const val ANONYMOUS_USER_ID_PREFIX: String = "user-"

    /**
     * Minimum length for any valid userId. Chosen so:
     *  - `"user-"` (5 chars, the observed truncation) is rejected.
     *  - The shortest real userIds we emit — anonymous `user-<8 hex>` (13 chars)
     *    and Google-derived `U<12 hex>` (13 chars) — comfortably pass.
     *  - A future, shorter opaque-ID format still has headroom.
     */
    const val MIN_USER_ID_LEN: Int = 7

    /**
     * Minimum suffix length when the userId uses [ANONYMOUS_USER_ID_PREFIX].
     * Matches the 8-hex suffix emitted by [com.tharmesh.data.UserPrefs.ensureProfile];
     * relaxed to 6 so shorter / differently-formatted anon IDs from forks don't
     * false-positive as truncated.
     */
    const val MIN_ANON_SUFFIX_LEN: Int = 6

    /**
     * `true` iff [userId] is safe to persist as a `ContactEntity` primary
     * key and route messages to. Called from:
     *  - [com.tharmesh.data.MessageRepository.addOrMergeContact] as a hard
     *    gate (invalid input is rejected; the UI layer surfaces a toast).
     *  - UI dialogs that let the user type an invite code before hitting
     *    the repository (optional early-exit to avoid a round trip).
     *  - [com.tharmesh.data.IdentityDedupMigration] when sweeping existing
     *    DB rows for pre-11.1 corruption.
     */
    @JvmStatic
    fun isValidUserId(userId: String): Boolean {
        val trimmed = userId.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length < MIN_USER_ID_LEN) return false
        if (trimmed.any { it.isISOControl() }) return false
        if (trimmed.startsWith(ANONYMOUS_USER_ID_PREFIX)) {
            val suffix = trimmed.substring(ANONYMOUS_USER_ID_PREFIX.length).trim()
            if (suffix.length < MIN_ANON_SUFFIX_LEN) return false
        }
        return true
    }
}
