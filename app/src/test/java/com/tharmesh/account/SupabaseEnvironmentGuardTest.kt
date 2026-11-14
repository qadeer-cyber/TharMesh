package com.tharmesh.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseEnvironmentGuardTest {

    @Test
    fun unsetEnv_allowsAnyHttpsUrl() {
        assertTrue(
            SupabaseEnvironmentGuard.stagingUrlAllowed(
                "https://example.supabase.co",
                SupabaseEnvironmentGuard.BACKEND_UNSET,
                ""
            )
        )
    }

    @Test
    fun stagingEnv_requiresMarker_andRejectsProdHost() {
        assertTrue(
            SupabaseEnvironmentGuard.stagingUrlAllowed(
                "https://tharmesh-staging.supabase.co",
                SupabaseEnvironmentGuard.BACKEND_STAGING,
                "tharmesh-staging"
            )
        )
        assertFalse(
            SupabaseEnvironmentGuard.stagingUrlAllowed(
                "https://tharmesh-prod.supabase.co",
                SupabaseEnvironmentGuard.BACKEND_STAGING,
                "tharmesh-staging"
            )
        )
        assertFalse(
            SupabaseEnvironmentGuard.stagingUrlAllowed(
                "https://other.supabase.co",
                SupabaseEnvironmentGuard.BACKEND_STAGING,
                "tharmesh-staging"
            )
        )
    }
}