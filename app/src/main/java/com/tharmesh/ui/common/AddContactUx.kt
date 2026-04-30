// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.ui.common

import android.content.Context
import android.widget.Toast
import com.tharmesh.data.AddContactResult
import tharmesh.app.R

/**
 * Stage 11.1 — shared UI hooks for [AddContactResult]. Keeps the
 * invalid-identity toast and the "merged into existing" toast consistent
 * across every add-contact entry point (nearby banner, nearby picker, QR
 * scan, manual invite).
 *
 * Main-thread only — callers hop to Main before invoking.
 */
object AddContactUx {

    /**
     * Show the correct user-facing toast for [result] and return `true`
     * iff the caller should proceed to open a chat. The "open chat"
     * semantic is:
     *  - [AddContactResult.Created] / [AddContactResult.Updated] → open
     *    using the input userId.
     *  - [AddContactResult.Merged] → open using
     *    `result.canonical.userId` (NOT the input userId; that row was
     *    deleted as part of the merge). Caller gets `true` AND the
     *    canonical userId via [canonicalUserIdOrNull].
     *  - [AddContactResult.Invalid] / [AddContactResult.Blocked] → do
     *    not open any chat; return `false`.
     */
    @JvmStatic
    fun shouldOpenChat(context: Context, result: AddContactResult): Boolean {
        return when (result) {
            AddContactResult.Invalid -> {
                Toast.makeText(
                    context.applicationContext,
                    R.string.identity_invalid_toast,
                    Toast.LENGTH_LONG
                ).show()
                false
            }
            is AddContactResult.Blocked -> false
            is AddContactResult.Merged -> {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(
                        R.string.identity_merged_toast,
                        result.canonical.displayName
                    ),
                    Toast.LENGTH_LONG
                ).show()
                true
            }
            is AddContactResult.Created, is AddContactResult.Updated -> true
        }
    }

    /**
     * The userId the caller should navigate to after [result]. Returns
     * null iff [result] is [AddContactResult.Invalid] or
     * [AddContactResult.Blocked]. For [AddContactResult.Merged] this is
     * the CANONICAL userId (not the input one — that row is gone).
     */
    @JvmStatic
    fun canonicalUserIdOrNull(result: AddContactResult): String? = when (result) {
        AddContactResult.Invalid -> null
        is AddContactResult.Blocked -> null
        is AddContactResult.Created -> result.entity.userId
        is AddContactResult.Updated -> result.entity.userId
        is AddContactResult.Merged -> result.canonical.userId
    }

    /**
     * The display name the caller should pass as the chat title.
     * Pre-condition: [result] is not Invalid / Blocked.
     */
    @JvmStatic
    fun canonicalDisplayNameOrNull(result: AddContactResult): String? = when (result) {
        AddContactResult.Invalid -> null
        is AddContactResult.Blocked -> null
        is AddContactResult.Created -> result.entity.displayName
        is AddContactResult.Updated -> result.entity.displayName
        is AddContactResult.Merged -> result.canonical.displayName
    }
}
