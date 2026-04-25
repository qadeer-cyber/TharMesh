// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent mirror of [com.tharmesh.dtn.RetryPolicy]'s per-bundle backoff
 * state plus the [com.tharmesh.data.MessageRepository] priority flag. Row
 * lifecycle is 1:1 with the retry pool:
 *
 *  - Inserted on the first [com.tharmesh.dtn.RetryPolicy.markOriginated]
 *    or [com.tharmesh.dtn.RetryPolicy.recordAttempt] call for a bundle.
 *  - Updated on every subsequent `recordAttempt`.
 *  - Deleted on `onDelivered` / `onTtlExpired` / `reset`.
 *
 * Without this table, a forced app kill mid-retry loses the curve position
 * and (more importantly) loses the SOS priority bit, so disaster-mode
 * bundles fall back to the default 5s→60s curve on restart until the user
 * manually re-fires them. The README has been flagging this as a Known
 * Limitation under "SOS priority is in-memory".
 *
 * `priority` is an INTEGER / 0|1 flag because Room does not auto-map Kotlin
 * Boolean to TEXT "true"/"false" the same way on older AGPs.
 */
@Entity(tableName = "retry_state")
data class RetryStateEntity(
    @PrimaryKey val bundleId: String,
    val attemptCount: Int,
    val nextRetryAt: Long,
    val priority: Int
)
