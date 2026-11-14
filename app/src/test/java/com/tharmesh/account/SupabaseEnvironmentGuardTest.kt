package com.tharmesh.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseEnvironmentGuardTest {

    private val stagingRef = "tharmesh-staging"
    private val prodRef = "tharmesh-prod"
    private val stagingUrl = "https://tharmesh-staging.supabase.co"
    private val prodUrl = "https://tharmesh-prod.supabase.co"
    private val lookalikeUrl = "https://tharmesh-staging.evil.com"
    private val anon = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJzdXBhYmFzZSJ9.placeholder"

    @Test
    fun extractProjectRef_rejectsLookalikeHosts() {
        assertEquals(stagingRef, SupabaseEnvironmentGuard.extractProjectRef(stagingUrl))
        assertNull(SupabaseEnvironmentGuard.extractProjectRef(lookalikeUrl))
        assertNull(SupabaseEnvironmentGuard.extractProjectRef(""))
        assertNull(SupabaseEnvironmentGuard.extractProjectRef("http://$stagingRef.supabase.co"))
    }

    @Test
    fun stagingBuild_failClosedOnUnsetBlankWrongOrProdUrl() {
        val env = SupabaseEnvironmentGuard.BACKEND_STAGING
        assertFalse(
            SupabaseEnvironmentGuard.cloudBackendPermitted("", anon, env, stagingRef, prodRef)
        )
        assertFalse(
            SupabaseEnvironmentGuard.cloudBackendPermitted(prodUrl, anon, env, stagingRef, prodRef)
        )
        assertFalse(
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                "https://wrong-ref.supabase.co", anon, env, stagingRef, prodRef
            )
        )
        assertFalse(
            SupabaseEnvironmentGuard.cloudBackendPermitted(stagingUrl, anon, env, "", prodRef)
        )
        assertTrue(
            SupabaseEnvironmentGuard.cloudBackendPermitted(stagingUrl, anon, env, stagingRef, prodRef)
        )
    }

    @Test
    fun releaseBuild_neverAllowsStagingRef() {
        assertFalse(
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                stagingUrl, anon, SupabaseEnvironmentGuard.BACKEND_RELEASE, stagingRef, prodRef
            )
        )
        assertTrue(
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                prodUrl, anon, SupabaseEnvironmentGuard.BACKEND_RELEASE, stagingRef, prodRef
            )
        )
    }

    @Test
    fun unsetDebug_forbidsStagingHost_whenStagingRefConfigured() {
        assertFalse(
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                stagingUrl, anon, SupabaseEnvironmentGuard.BACKEND_UNSET, stagingRef, prodRef
            )
        )
        assertTrue(
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                prodUrl, anon, SupabaseEnvironmentGuard.BACKEND_UNSET, stagingRef, prodRef
            )
        )
    }

    @Test
    fun serviceRoleKey_rejected() {
        assertFalse(
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                stagingUrl, "service_role_secret", SupabaseEnvironmentGuard.BACKEND_STAGING, stagingRef, prodRef
            )
        )
    }
}