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
     * (wrong TLD, nested hosts, blank ref, non-https).
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
     * Staging/qa: exact staging ref only.
     * Release: exact production ref only (fail closed when production ref blank).
     * Debug/unset: exact debug ref, or staging ref when debug ref blank but staging set;
     * never production; fail closed when all configured refs blank.
     */
    fun cloudBackendPermitted(
        baseUrl: String,
        anonKey: String,
        backendEnv: String,
        stagingProjectRef: String,
        productionProjectRef: String,
        debugProjectRef: String = ""
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
        val debug = debugProjectRef.trim().lowercase()
        return when (backendEnv.trim().lowercase()) {
            BACKEND_STAGING, BACKEND_QA ->
                staging.isNotEmpty() && ref == staging
            BACKEND_RELEASE -> {
                if (staging.isNotEmpty() && ref == staging) return false
                production.isNotEmpty() && ref == production
            }
            else -> {
                if (production.isNotEmpty() && ref == production) return false
                when {
                    debug.isNotEmpty() -> ref == debug
                    staging.isNotEmpty() -> ref == staging
                    else -> false
                }
            }
        }
    }
}