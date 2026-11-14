package com.tharmesh.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseEnvironmentGuardTest {

    private val stagingRef = "tharmesh-staging"
    private val prodRef = "tharmesh-prod"
    private val debugRef = "tharmesh-dev"
    private val stagingUrl = "https://tharmesh-staging.supabase.co"
    private val prodUrl = "https://tharmesh-prod.supabase.co"
    private val debugUrl = "https://tharmesh-dev.supabase.co"
    private val anon = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJzdXBhYmFzZSJ9.placeholder"

    @Test
    fun extractProjectRef_rejectsLookalikesAndHttp() {
        assertEquals(stagingRef, SupabaseEnvironmentGuard.extractProjectRef(stagingUrl))
        assertNull(SupabaseEnvironmentGuard.extractProjectRef("https://$stagingRef.supabase.co.evil.com"))
        assertNull(SupabaseEnvironmentGuard.extractProjectRef("https://$stagingRef-supabase.co"))
        assertNull(SupabaseEnvironmentGuard.extractProjectRef("http://$stagingRef.supabase.co"))
        assertNull(SupabaseEnvironmentGuard.extractProjectRef(""))
    }

    @Test
    fun stagingBuild_failClosedOnBlankStagingRefWrongHostOrProd() {
        val env = SupabaseEnvironmentGuard.BACKEND_STAGING
        assertFalse(SupabaseEnvironmentGuard.cloudBackendPermitted("", anon, env, stagingRef, prodRef))
        assertFalse(SupabaseEnvironmentGuard.cloudBackendPermitted(prodUrl, anon, env, stagingRef, prodRef))
        assertFalse(SupabaseEnvironmentGuard.cloudBackendPermitted(
            "https://wrong.supabase.co", anon, env, stagingRef, prodRef
        ))
        assertFalse(SupabaseEnvironmentGuard.cloudBackendPermitted(stagingUrl, anon, env, "", prodRef))
        assertTrue(SupabaseEnvironmentGuard.cloudBackendPermitted(stagingUrl, anon, env, stagingRef, prodRef))
    }

    @Test
    fun releaseBuild_failClosedWhenProductionRefBlank() {
        assertFalse(SupabaseEnvironmentGuard.cloudBackendPermitted(
            prodUrl, anon, SupabaseEnvironmentGuard.BACKEND_RELEASE, stagingRef, "", debugRef
        ))
        assertFalse(SupabaseEnvironmentGuard.cloudBackendPermitted(
            stagingUrl, anon, SupabaseEnvironmentGuard.BACKEND_RELEASE, stagingRef, prodRef, debugRef
        ))
        assertTrue(SupabaseEnvironmentGuard.cloudBackendPermitted(
            prodUrl, anon, SupabaseEnvironmentGuard.BACKEND_RELEASE, stagingRef, prodRef, debugRef
        ))
    }

    @Test
    fun debugUnset_failClosedOnBlankRefs_andRejectsProductionHost() {
        assertFalse(SupabaseEnvironmentGuard.cloudBackendPermitted(
            stagingUrl, anon, SupabaseEnvironmentGuard.BACKEND_UNSET, "", "", ""
        ))
        assertFalse(SupabaseEnvironmentGuard.cloudBackendPermitted(
            prodUrl, anon, SupabaseEnvironmentGuard.BACKEND_UNSET, stagingRef, prodRef, debugRef
        ))
        assertTrue(SupabaseEnvironmentGuard.cloudBackendPermitted(
            debugUrl, anon, SupabaseEnvironmentGuard.BACKEND_UNSET, stagingRef, prodRef, debugRef
        ))
        assertTrue(SupabaseEnvironmentGuard.cloudBackendPermitted(
            stagingUrl, anon, SupabaseEnvironmentGuard.BACKEND_UNSET, stagingRef, prodRef, ""
        ))
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