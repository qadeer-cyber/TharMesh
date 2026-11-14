// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.identity

/**
 * Rejects the truncated nearby name `user-` that used to create a second
 * contact beside the real `user-` plus suffix id. Does not delete stored
 * messages. Full account ids and normal `user-` plus 6 or more characters stay valid.
 */
object ContactIdentity {
    fun isTruncatedMeshId(userId: String): Boolean {
        if (!userId.startsWith("user-")) return false
        return userId.removePrefix("user-").length < 6
    }
}
