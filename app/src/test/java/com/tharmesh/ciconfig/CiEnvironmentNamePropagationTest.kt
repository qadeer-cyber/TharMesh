package com.tharmesh.ciconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Permanent guard for the `THERMESH_` / `THARMESH_` typo class.
 *
 * `.github/workflows/android.yml` set `THERMESH_DEBUG_PROJECT_REF: example` while
 * `app/build.gradle` reads `THARMESH_DEBUG_PROJECT_REF`. Gradle therefore never saw
 * the variable and fell back to the hardcoded `"example"` floor in `build.gradle`,
 * so the misconfiguration was invisible: the generated BuildConfig value was the
 * same string either way. Had the owner set a real project ref there, it would have
 * been silently dropped and the build would have kept using `"example"`.
 *
 * Two independent checks, so neither side can drift again:
 *  1. the misspelling appears in no executable line anywhere in the build config,
 *  2. every project-scoped variable CI sets is read by something in the repository.
 */
class CiEnvironmentNamePropagationTest {

    /** Variable families owned by this project; runner/toolchain vars are not ours. */
    private val projectScoped = Regex("^(THARMESH_|THERMESH_|SUPABASE_)|^GOOGLE_WEB_CLIENT_ID$")

    private lateinit var repoRoot: File
    private lateinit var workflows: List<File>

    @Before
    fun locateSources() {
        // Unit tests run with the Gradle module directory as the working directory.
        repoRoot = listOf(File("."), File(".."), File("../.."))
            .map { it.absoluteFile.normalize() }
            .firstOrNull { File(it, "app/build.gradle").isFile && File(it, ".github").isDirectory }
            ?: throw AssertionError("repo root not found from ${File(".").absolutePath}")
        workflows = File(repoRoot, ".github/workflows").walkTopDown()
            .filter { it.isFile && (it.extension == "yml" || it.extension == "yaml") }
            .toList()
        assertTrue("no workflow files found", workflows.isNotEmpty())
    }

    /** Drop `#` comments so prose documenting the old name is not flagged. */
    private fun executableLines(file: File): List<IndexedValue<String>> =
        file.readText().lineSequence().withIndex()
            .map { (i, line) -> IndexedValue(i, line.substringBefore('#')) }
            .filter { it.value.isNotBlank() }
            .toList()

    private fun filesUnder(vararg paths: String): List<File> =
        paths.map { File(repoRoot, it) }
            .filter { it.exists() }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.length() < 512 * 1024 && !it.path.contains("/build/") }
                    .toList()
            }

    private fun namesReadByGradle(): Set<String> =
        Regex("""(?:firstNonBlank|System\.getenv)\(\s*"([A-Z0-9_]+)"\s*\)""")
            .findAll(File(repoRoot, "app/build.gradle").readText())
            .map { it.groupValues[1] }
            .toSet()

    /** `NAME: value` keys declared in a workflow (env blocks and job-level env). */
    private fun namesSetByWorkflow(file: File): Set<String> =
        Regex("""(?m)^\s{2,}([A-Z][A-Z0-9_]{2,}):\s""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun theMisspellingAppearsInNoExecutableBuildConfigLine() {
        val scanned = filesUnder(".github", "app/build.gradle", "build.gradle", "scripts", "supabase")
        val offenders = scanned.flatMap { file ->
            executableLines(file)
                .filter { it.value.contains("THERMESH") }
                .map { "${file.relativeTo(repoRoot)}:${it.index + 1}: ${it.value.trim()}" }
        }.sorted()
        assertEquals(
            "THERMESH_ is a misspelling of THARMESH_ and is silently ignored by " +
                "app/build.gradle:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun theDebugProjectRefNameCiSetsIsTheNameGradleReads() {
        assertTrue(
            "app/build.gradle must read THARMESH_DEBUG_PROJECT_REF",
            namesReadByGradle().contains("THARMESH_DEBUG_PROJECT_REF")
        )
        val android = File(repoRoot, ".github/workflows/android.yml")
        assertTrue("android.yml missing", android.isFile)
        assertTrue(
            "android.yml must set THARMESH_DEBUG_PROJECT_REF, not a near-miss spelling",
            namesSetByWorkflow(android).contains("THARMESH_DEBUG_PROJECT_REF")
        )
    }

    @Test
    fun everyProjectScopedVariableCiSetsIsReadSomewhere() {
        // A variable may be consumed by Gradle or by a shell script; it may not be
        // consumed by nothing at all, which is what the typo produced.
        val consumers = filesUnder("app/build.gradle", "build.gradle", ".github/scripts", "scripts", "supabase")
        val consumerText = consumers.joinToString("\n") { it.readText() }
        val orphans = workflows.flatMap { wf ->
            namesSetByWorkflow(wf)
                .filter { projectScoped.containsMatchIn(it) }
                .filterNot { consumerText.contains(it) }
                .map { "${wf.relativeTo(repoRoot)} sets $it, which nothing reads" }
        }.distinct().sorted()
        assertEquals(
            "dead CI configuration - these are usually typos in the variable name:\n" +
                orphans.joinToString("\n"),
            emptyList<String>(),
            orphans
        )
    }

    @Test
    fun gradleStillReadsTheFullSupabaseNameSet() {
        val read = namesReadByGradle()
        for (name in listOf(
            "SUPABASE_URL",
            "SUPABASE_ANON_KEY",
            "GOOGLE_WEB_CLIENT_ID",
            "THARMESH_STAGING_PROJECT_REF",
            "THARMESH_PRODUCTION_PROJECT_REF",
            "THARMESH_DEBUG_PROJECT_REF"
        )) {
            assertTrue("app/build.gradle no longer reads $name", read.contains(name))
        }
    }

    @Test
    fun noWorkflowPutsAProjectRefInAPlaceGradleCannotSee() {
        // The debug ref must be exported as a job/workflow `env:` entry, not only as a
        // `with:` input or a step-local shell variable, or Gradle's System.getenv misses it.
        val android = File(repoRoot, ".github/workflows/android.yml").readText()
        val envBlockDeclaration = Regex(
            """(?m)^\s{2,}THARMESH_DEBUG_PROJECT_REF:\s*\S"""
        )
        assertTrue(
            "THARMESH_DEBUG_PROJECT_REF must be declared as an env entry with a value",
            envBlockDeclaration.containsMatchIn(android)
        )
    }
}
