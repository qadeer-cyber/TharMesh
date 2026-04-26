// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.invite

import android.content.Context
import android.content.Intent
import com.tharmesh.TharMeshApp
import com.tharmesh.data.UserPrefs
import com.tharmesh.identity.IdentityQrPayload
import com.tharmesh.identity.QrCodec
import tharmesh.app.R

/**
 * Stage 7 PR D — single source of truth for "share invite" actions
 * scattered across MessagesFragment empty state, ChatActivity overflow,
 * and the existing "Share invite" button in `MyQrActivity`.
 *
 * Builds a [QrCodec.encode]'d `tharmesh://invite?…` URI containing the
 * user's identity, wraps it in a one-line message, and fires
 * [Intent.ACTION_SEND] with `text/plain` so the system chooser surfaces
 * SMS, email, messaging apps, etc. The chooser title is sourced from
 * [R.string.invite_share_chooser].
 *
 * Returns `false` when the user has no profile yet (e.g. fresh install
 * before LoginActivity completed) — callers can show an error toast.
 */
object InviteSharer {

    /**
     * Build the user's current invite URI. `null` if no profile exists.
     * Public so callers (tests, MyQrActivity) can render the URI without
     * launching a share intent.
     */
    fun buildInviteUri(context: Context): String? {
        val profile = UserPrefs.readProfile(context) ?: return null
        val identity = UserPrefs.readIdentity(context)
        return QrCodec.encode(
            IdentityQrPayload(
                userId = profile.userId,
                name = profile.username,
                publicKeyBase64 = identity?.publicKeyBase64.orEmpty()
            )
        )
    }

    fun share(context: Context): Boolean {
        val uri = buildInviteUri(context) ?: return false
        val profile = UserPrefs.readProfile(context) ?: return false
        val body = context.getString(R.string.invite_share_body, profile.username, uri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.invite_share_subject))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        val chooser = Intent.createChooser(
            intent,
            context.getString(R.string.invite_share_chooser)
        ).apply {
            // Required when launching from a non-Activity Context (rare,
            // but we expose this object for arbitrary callers).
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(chooser) }.isSuccess
    }
}
