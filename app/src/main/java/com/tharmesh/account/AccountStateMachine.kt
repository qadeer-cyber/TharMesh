// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

/**
 * Named phases for identity work. Transitions are enforced in [LegacyReconciliation]
 * and [IdentityProvisioning]; this type documents allowed outcomes only.
 */
enum class AccountStateMachine {
    NEW_REGISTRATION,
    FIRST_DEVICE_CANONICAL,
    EXISTING_DEVICE_AUTH,
    ADDITIONAL_DEVICE_ENROLLMENT,
    LEGACY_RECONCILIATION,
    RECOVERY_ACTIVATION,
    RECOVERY_ROTATION,
    REVOCATION_PENDING
}
