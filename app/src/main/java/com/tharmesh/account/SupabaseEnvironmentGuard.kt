// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

/**
 * Prevents staging-tagged builds from targeting production Supabase projects.
 * Hosted staging is a separate acceptance plane (Cat 5); offline mesh does not use this.
 */
object SupabaseEnvironmentGuard {

    const val BACKEND_UNSET = "unset"
    const val BACKEND_STAGING = "staging"

    /**
     * When [backendEnv] is [BACKEND_STAGING], [baseUrl] must include [stagingMarker]
     * (from build config / owner checklist) and must not match production markers.
     */
    fun stagingUrlAllowed(baseUrl: String, backendEnv: String, stagingMarker: String): Boolean {
        if (backendEnv != BACKEND_STAGING) return true
        val u = baseUrl.trim().lowercase()
        if (u.isEmpty()) return false
        val marker = stagingMarker.trim().lowercase()
        if (marker.isEmpty()) return false
        if (!u.contains(marker)) return false
        val prodMarkers = listOf("tharmesh-prod", "production", "prod.supabase")
        if (prodMarkers.any { u.contains(it) }) return false
        return true
    }
}