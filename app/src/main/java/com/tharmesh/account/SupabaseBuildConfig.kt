// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import tharmesh.app.BuildConfig

/** Central read of Supabase BuildConfig fields for fail-closed cloud gating. */
object SupabaseBuildConfig {
    fun backendEnv(): String = BuildConfig.SUPABASE_BACKEND_ENV
    fun stagingProjectRef(): String = BuildConfig.SUPABASE_STAGING_PROJECT_REF
    fun productionProjectRef(): String = BuildConfig.SUPABASE_PRODUCTION_PROJECT_REF
}