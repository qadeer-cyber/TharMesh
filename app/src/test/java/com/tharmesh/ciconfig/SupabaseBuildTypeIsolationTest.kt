package com.tharmesh.ciconfig

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Locks the debug / qa / release environment isolation declared in
 * `app/build.gradle`, so a configuration edit cannot quietly let a debug project
 * ref survive into a signed QA or release artifact.
 *
 * This is a static contract check on the build script: unit tests only ever see the
 * debug variant's generated BuildConfig, so the qa and release overrides cannot be
 * asserted at runtime from here. `SupabaseBuildConfigPropagationTest` covers the
 * debug variant's live values.
 */
class SupabaseBuildTypeIsolationTest {

    private lateinit var gradle: String

    @Before
    fun readBuildScript() {
        val root = listOf(File("."), File(".."), File("../.."))
            .map { it.absoluteFile.normalize() }
            .firstOrNull { File(it, "app/build.gradle").isFile }
            ?: throw AssertionError("app/build.gradle not found from ${File(".").absolutePath}")
        gradle = File(root, "app/build.gradle").readText()
    }

    /** Body of a `buildTypes { <name> { ... } }` block, brace-matched. */
    private fun buildTypeBlock(name: String): String {
        val types = gradle.indexOf("buildTypes {")
        assertTrue("buildTypes block not found", types >= 0)
        val start = gradle.indexOf("$name {", types)
        assertTrue("build type '$name' not found", start >= 0)
        var depth = 0
        var i = gradle.indexOf('{', start)
        val open = i
        while (i < gradle.length) {
            when (gradle[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return gradle.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces for build type '$name'")
    }

    private fun assertField(block: String, field: String, value: String, label: String) {
        val needle = Regex(
            """buildConfigField\s+"String",\s*"$field",\s*"\\"${Regex.escape(value)}\\""""
        )
        assertTrue(
            "$label must set $field to \"$value\"; block was:\n$block",
            needle.containsMatchIn(block)
        )
    }

    @Test
    fun debugBuildIsBackendUnset() {
        assertField(buildTypeBlock("debug"), "SUPABASE_BACKEND_ENV", "unset", "debug build type")
    }

    @Test
    fun releaseBuildIsBackendReleaseAndDropsTheDebugProjectRef() {
        val block = buildTypeBlock("release")
        assertField(block, "SUPABASE_BACKEND_ENV", "release", "release build type")
        assertField(block, "SUPABASE_DEBUG_PROJECT_REF", "", "release build type")
    }

    @Test
    fun qaBuildIsBackendStagingAndDropsTheDebugProjectRef() {
        val block = buildTypeBlock("qa")
        assertField(block, "SUPABASE_BACKEND_ENV", "staging", "qa build type")
        assertField(block, "SUPABASE_DEBUG_PROJECT_REF", "", "qa build type")
    }

    @Test
    fun qaInheritsReleaseHardeningAndItsOwnSigningConfig() {
        val block = buildTypeBlock("qa")
        assertTrue("qa must initWith release so it inherits release hardening", block.contains("initWith release"))
        assertTrue("qa must use signingConfigs.qa, not the public debug key", block.contains("signingConfigs.qa"))
    }

    @Test
    fun debugProjectRefNeverReachesAnySignedBuildType() {
        // The only build type permitted to carry a non-empty debug project ref is
        // `debug`. Both hardened types must blank it, or a debug-only backend could
        // be reachable from an artifact handed to a tester.
        for (type in listOf("release", "qa")) {
            assertField(buildTypeBlock(type), "SUPABASE_DEBUG_PROJECT_REF", "", "$type build type")
        }
    }

    @Test
    fun debugProjectRefHasANonBlankFloorAndItIsDocumented() {
        // `supabaseDebugProjectRef ?: "example"` — Groovy treats "" as falsy, so an
        // unset THARMESH_DEBUG_PROJECT_REF yields the literal "example", NOT "".
        // That is load-bearing: it keeps a debug build from falling through to
        // SupabaseEnvironmentGuard's "blank debug ref -> accept staging" branch.
        assertTrue(
            "the documented debug-ref floor literal is gone; re-check the debug/unset " +
                "branch of SupabaseEnvironmentGuard before changing this",
            Regex("""supabaseDebugProjectRef\s*\?:\s*"example"""").containsMatchIn(gradle)
        )
    }

    @Test
    fun qaSigningIsGatedOnRealKeystoreMaterial() {
        assertTrue(
            "qa signing must stay gated on a present keystore file plus all three secrets",
            gradle.contains("qaSigningReady") &&
                Regex("""file\(qaKeyPath\)\.isFile\(\)""").containsMatchIn(gradle)
        )
    }
}
