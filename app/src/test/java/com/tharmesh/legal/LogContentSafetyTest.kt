package com.tharmesh.legal

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Stage 8.4 \u2014 Pakistan compliance defense: prove the log surface is
 * free of message-content leaks. Per user spec, the audit is
 * intentionally narrow \u2014 it scans for the four identifiers that
 * actually carry plaintext / ciphertext / payload bytes inside the
 * project, and ignores benign references (variable declarations,
 * imports, comments).
 *
 * The scan walks every production .kt file under `app/src/main/java`
 * and looks for `Log.<level>(` calls whose argument list mentions any
 * of the critical fields below by name. If the scan ever fires, the
 * fix is to log a redacted descriptor (id / length / hash) instead of
 * the raw content.
 *
 * Critical fields (deliberate, narrow set):
 *   - `.body`        \u2014 [com.tharmesh.db.entity.MessageEntity.body],
 *                       the plaintext message body field
 *   - `.payload`     \u2014 [com.tharmesh.dtn.MeshBundle.payload], the
 *                       wire-bytes of the bundle (encrypted or not)
 *   - `plaintext`    \u2014 any decrypted body referenced by name
 *   - `ciphertext`   \u2014 any sealed envelope payload referenced by name
 *
 * The audit is fragile-by-design only at the `Log.` boundary \u2014 it
 * does NOT do a global scan of the source tree (which would flag every
 * harmless variable named `body`).
 */
class LogContentSafetyTest {

    private val criticalFields = listOf(".body", ".payload", "plaintext", "ciphertext")

    /**
     * Matches `Log.d(`, `Log.w(`, `Log.e(`, `Log.i(`, `Log.v(` and
     * captures the argument list up to the first balanced close. We
     * scan a sliding window after each match so multi-line argument
     * lists are covered.
     */
    private val logCallRegex = Regex("""\bLog\.[dweiv]\s*\(""")

    @Test
    fun `no production Log call references a message content field`() {
        val sourceRoot = locateSourceRoot()
        val violations = mutableListOf<String>()

        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file -> scanFile(file, violations) }

        if (violations.isNotEmpty()) {
            fail(
                "Found ${violations.size} Log call(s) referencing a critical " +
                    "message content field. Replace the raw value with a " +
                    "redacted descriptor (id / length / hash):\n\n" +
                    violations.joinToString("\n")
            )
        }
    }

    private fun scanFile(file: File, violations: MutableList<String>) {
        val text = file.readText()
        for (match in logCallRegex.findAll(text)) {
            val argEnd = findMatchingClose(text, match.range.last)
            if (argEnd < 0) continue
            val callText = text.substring(match.range.first, argEnd + 1)
            if (criticalFields.any { needle -> callText.contains(needle) }) {
                val lineNumber = text.substring(0, match.range.first).count { it == '\n' } + 1
                violations.add("  ${file.path}:$lineNumber  ${callText.replace("\n", " \\n ")}")
            }
        }
    }

    /**
     * Given an open-paren index, return the index of the matching
     * close paren, ignoring parens inside string literals. Returns -1
     * if no balanced close is found within 4 KB (defensive bound).
     */
    private fun findMatchingClose(text: String, openIndex: Int): Int {
        var depth = 1
        var i = openIndex + 1
        var inString = false
        val limit = minOf(text.length, openIndex + 4096)
        while (i < limit) {
            val c = text[i]
            if (inString) {
                if (c == '"' && text[i - 1] != '\\') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun locateSourceRoot(): File {
        // The test runs from the `app/` module's working directory, but
        // CI sometimes runs from the repo root. Walk up until we find
        // the production source tree.
        val candidates = listOf(
            File("src/main/java/com/tharmesh"),
            File("app/src/main/java/com/tharmesh"),
            File("../app/src/main/java/com/tharmesh")
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "Could not locate production source root from cwd=${File(".").absolutePath}",
            found != null
        )
        return found!!
    }
}
