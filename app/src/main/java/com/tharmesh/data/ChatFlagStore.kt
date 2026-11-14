// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.data

import android.content.Context
import com.tharmesh.account.ChatFlags

/** Pin, archive, and mute live beside Room so a flag change is not a migration. */
object ChatFlagStore {
    private const val PREFS = "tharmesh_chat_flags"
    private const val PINNED = "pinned"
    private const val ARCHIVED = "archived"
    private const val MUTED = "muted"

    fun read(context: Context): ChatFlags {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ChatFlags(
            pinned = prefs.getStringSet(PINNED, emptySet()) ?: emptySet(),
            archived = prefs.getStringSet(ARCHIVED, emptySet()) ?: emptySet(),
            muted = prefs.getStringSet(MUTED, emptySet()) ?: emptySet()
        )
    }

    fun write(context: Context, flags: ChatFlags) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(PINNED, flags.pinned)
            .putStringSet(ARCHIVED, flags.archived)
            .putStringSet(MUTED, flags.muted)
            .apply()
    }
}
