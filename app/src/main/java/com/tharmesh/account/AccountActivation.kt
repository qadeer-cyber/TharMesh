// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

/**
 * A fresh install may create a mesh identity only after the cloud account
 * is confirmed, or when this phone already has a profile or mesh key.
 */
object AccountActivation {
    fun mayOpenSecureIdentity(
        emailVerified: Boolean,
        hasLegacyProfile: Boolean,
        hasMeshIdentity: Boolean
    ): Boolean {
        return emailVerified || hasLegacyProfile || hasMeshIdentity
    }

    enum class AfterVerifiedSignIn { HOME, SECURE_IDENTITY, RECOVER }

    fun afterVerifiedSignIn(hasMeshIdentity: Boolean, pendingSignupOnThisPhone: Boolean): AfterVerifiedSignIn {
        if (hasMeshIdentity) return AfterVerifiedSignIn.HOME
        if (pendingSignupOnThisPhone) return AfterVerifiedSignIn.SECURE_IDENTITY
        return AfterVerifiedSignIn.RECOVER
    }
}
