// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

/**
 * Fail-closed Supabase cloud backend validation by exact project ref (host identity).
 * Publishable anon keys only — service-role keys are rejected.
 */
object SupabaseEnvironmentGuard {

    const val BACKEND_UNSET = "unset"
    const val BACKEND_STAGING = "staging"
    const val BACKEND_QA = "qa"
    const val BACKEND_RELEASE = "release"

    /**
     * Extract `<ref>` from `https://<ref>.supabase.co`. Returns null for lookalikes
     * (wrong TLD, nested hosts, blank ref).
     */
    fun extractProjectRef(baseUrl: String): String? {
        val trimmed = baseUrl.trim().trimEnd('/').lowercase()
        if (!trimmed.startsWith("https://")) return null
        val host = try {
            java.net.URI(trimmed).host?.lowercase() ?: return null
        } catch (_: Exception) {
            return null
        }
        if (!host.endsWith(".supabase.co")) return null
        val ref = host.removeSuffix(".supabase.co")
        if (ref.isEmpty() || ref.contains('.')) return null
        return ref
    }

    /**
     * When [backendEnv] is staging/qa, [baseUrl] must match [stagingProjectRef] exactly.
     * Release builds must never target staging; unset debug builds must not point at staging.
     */
    fun cloudBackendPermitted(
        baseUrl: String,
        anonKey: String,
        backendEnv: String,
        stagingProjectRef: String,
        productionProjectRef: String
    ): Boolean {
        val key = anonKey.trim()
        if (key.isEmpty()) return false
        if (key.contains("service_role", ignoreCase = true)) return false
        val url = baseUrl.trim()
        if (url.isEmpty()) return false
        if (!url.startsWith("https://")) return false
        val ref = extractProjectRef(url) ?: return false
        val staging = stagingProjectRef.trim().lowercase()
        val production = productionProjectRef.trim().lowercase()
        return when (backendEnv.trim().lowercase()) {
            BACKEND_STAGING, BACKEND_QA -> staging.isNotEmpty() && ref == staging
            BACKEND_RELEASE -> {
                if (staging.isNotEmpty() && ref == staging) return false
                when {
                    production.isNotEmpty() -> ref == production
                    staging.isNotEmpty() -> ref != staging
                    else -> true
                }
            }
            else -> {
                if (staging.isNotEmpty() && ref == staging) return false
                true
            }
        }
    }

    @Deprecated(
        "Substring marker guard — use cloudBackendPermitted",
        ReplaceWith("cloudBackendPermitted(baseUrl, \"\", backendEnv, stagingMarker, \"\")")
    )
    fun stagingUrlAllowed(baseUrl: String, backendEnv: String, stagingMarker: String): Boolean {
        return cloudBackendPermitted(baseUrl, "eyJhbGciOiJIUzI1NiJ9.e30.placeholder", backendEnv, stagingMarker, "")
    }
}