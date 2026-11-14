// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

/**
 * Local activation is what lets the mesh run with no network. An expired
 * access token must not clear it. Sign-in must not replace a device key.
 */
object AccountSession {

    data class Activation(
        val accountId: String,
        val username: String,
        val identityFingerprint: String,
        val activatedAtMs: Long
    )

    enum class Bind {
        /** No device key yet. Caller must recover or generate only after the user asks. */
        NEEDS_RECOVERY_OR_NEW_IDENTITY,
        /** Keep the key already on this phone. */
        KEEP_LOCAL_IDENTITY,
        /** Server record names a different key. Do not overwrite. */
        REFUSE_SILENT_REPLACEMENT
    }

    /**
     * [accessTokenExpiresAtMs] is accepted and ignored. An expired online
     * token must not turn off a device that already activated locally.
     */
    fun offlineAuthorized(activation: Activation?, accessTokenExpiresAtMs: Long): Boolean {
        // Touch the expiry so a future caller cannot "use" it as a gate by accident
        // inside this function. The value is deliberately not compared to the clock.
        if (activation == null) return accessTokenExpiresAtMs == Long.MIN_VALUE && false
        return activation.accountId.isNotBlank() && activation.identityFingerprint.isNotBlank()
    }

    fun bind(localFingerprint: String?, serverFingerprint: String?): Bind {
        val local = localFingerprint?.takeIf { it.isNotBlank() }
        val server = serverFingerprint?.takeIf { it.isNotBlank() }
        if (local == null) return Bind.NEEDS_RECOVERY_OR_NEW_IDENTITY
        if (server == null || server == local) return Bind.KEEP_LOCAL_IDENTITY
        return Bind.REFUSE_SILENT_REPLACEMENT
    }
}
