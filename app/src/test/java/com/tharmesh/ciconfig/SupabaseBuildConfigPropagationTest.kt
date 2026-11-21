package com.tharmesh.ciconfig

import com.tharmesh.account.SupabaseBuildConfig
import com.tharmesh.account.SupabaseEnvironmentGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the *live* debug-variant BuildConfig the unit test suite is compiled
 * against, and that a debug build stays fail-closed against anything but its own
 * configured project.
 *
 * Deliberately value-agnostic about the refs themselves: these tests must pass both
 * locally (no Supabase variables present) and in CI (SUPABASE_URL / ANON_KEY are
 * repository values), so they assert invariants rather than literals.
 */
class SupabaseBuildConfigPropagationTest {

    private val debugRef = SupabaseBuildConfig.debugProjectRef()
    private val stagingRef = SupabaseBuildConfig.stagingProjectRef()
    private val productionRef = SupabaseBuildConfig.productionProjectRef()

    private fun url(ref: String) = "https://$ref.supabase.co"
    private val anon = "sb_publishable_exampleexampleexample"

    @Test
    fun unitTestsRunAgainstTheUnsetBackend() {
        assertEquals(
            "unit tests must compile against the debug variant, whose backend env is 'unset'",
            SupabaseEnvironmentGuard.BACKEND_UNSET,
            SupabaseBuildConfig.backendEnv()
        )
    }

    @Test
    fun debugProjectRefIsNeverBlank() {
        // app/build.gradle floors it at "example"; a blank value here would mean the
        // floor was removed and debug builds would start accepting the staging project.
        assertTrue(
            "debug project ref must not be blank (build.gradle floors it at \"example\")",
            debugRef.isNotBlank()
        )
    }

    @Test
    fun debugBuildAcceptsOnlyItsOwnConfiguredProject() {
        assertTrue(
            "a debug build must accept its own configured debug project",
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                baseUrl = url(debugRef),
                anonKey = anon,
                backendEnv = SupabaseBuildConfig.backendEnv(),
                stagingProjectRef = stagingRef,
                productionProjectRef = productionRef,
                debugProjectRef = debugRef
            )
        )
        assertFalse(
            "a debug build must reject an unrelated project ref",
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                baseUrl = url("someotherprojectref"),
                anonKey = anon,
                backendEnv = SupabaseBuildConfig.backendEnv(),
                stagingProjectRef = stagingRef,
                productionProjectRef = productionRef,
                debugProjectRef = debugRef
            )
        )
    }

    @Test
    fun debugBuildNeverReachesProduction() {
        assertFalse(
            "a debug/unset build must never be permitted against the production project",
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                baseUrl = url("tharmeshproductionref"),
                anonKey = anon,
                backendEnv = SupabaseEnvironmentGuard.BACKEND_UNSET,
                stagingProjectRef = "tharmeshstagingref",
                productionProjectRef = "tharmeshproductionref",
                debugProjectRef = debugRef
            )
        )
    }

    @Test
    fun aNonBlankDebugRefShadowsTheStagingFallback() {
        // Documents the consequence of the "example" floor: because the debug ref is
        // never blank in a real build, the guard's "blank debug ref -> accept staging"
        // branch is unreachable from a shipped debug artifact.
        assertFalse(
            "with a non-blank debug ref, a debug build must not accept the staging project",
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                baseUrl = url("tharmeshstagingref"),
                anonKey = anon,
                backendEnv = SupabaseEnvironmentGuard.BACKEND_UNSET,
                stagingProjectRef = "tharmeshstagingref",
                productionProjectRef = "",
                debugProjectRef = "example"
            )
        )
        assertTrue(
            "that branch still exists for a genuinely blank debug ref",
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                baseUrl = url("tharmeshstagingref"),
                anonKey = anon,
                backendEnv = SupabaseEnvironmentGuard.BACKEND_UNSET,
                stagingProjectRef = "tharmeshstagingref",
                productionProjectRef = "",
                debugProjectRef = ""
            )
        )
    }

    @Test
    fun qaAndReleaseKeepTheirEnvironmentsSeparate() {
        val staging = "tharmeshstagingref"
        val production = "tharmeshproductionref"
        // QA signs for staging and must refuse production.
        assertTrue(
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                url(staging), anon, SupabaseEnvironmentGuard.BACKEND_QA, staging, production, ""
            )
        )
        assertFalse(
            "a QA build must never reach production",
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                url(production), anon, SupabaseEnvironmentGuard.BACKEND_QA, staging, production, ""
            )
        )
        // Release accepts production only and must refuse staging.
        assertTrue(
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                url(production), anon, SupabaseEnvironmentGuard.BACKEND_RELEASE, staging, production, ""
            )
        )
        assertFalse(
            "a release build must never reach staging",
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                url(staging), anon, SupabaseEnvironmentGuard.BACKEND_RELEASE, staging, production, ""
            )
        )
    }

    @Test
    fun failsClosedOnMissingOrDangerousCredentials() {
        val staging = "tharmeshstagingref"
        assertFalse(
            "blank anon key must fail closed",
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                url(staging), "", SupabaseEnvironmentGuard.BACKEND_STAGING, staging, "", ""
            )
        )
        assertFalse(
            "blank URL must fail closed",
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                "", anon, SupabaseEnvironmentGuard.BACKEND_STAGING, staging, "", ""
            )
        )
        assertFalse(
            "a service-role key must be rejected outright",
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                url(staging), "eyJservice_roleKEY", SupabaseEnvironmentGuard.BACKEND_STAGING,
                staging, "", ""
            )
        )
        assertFalse(
            "plain http must fail closed",
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                "http://$staging.supabase.co", anon, SupabaseEnvironmentGuard.BACKEND_STAGING,
                staging, "", ""
            )
        )
        assertFalse(
            "an unconfigured staging ref must fail closed even with a valid-looking URL",
            SupabaseEnvironmentGuard.cloudBackendPermitted(
                url(staging), anon, SupabaseEnvironmentGuard.BACKEND_STAGING, "", "", ""
            )
        )
    }
}
