// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

/**
 * Reads `supabase/schema.sql` the same way a reviewer would.
 * GoTrue's own rate limits are not SQL. The file must say that, and the
 * client limiter is [AuthRateLimiter].
 */
object SchemaReview {
    val recoveryColumns: Set<String> = setOf(
        "account_id",
        "kdf",
        "n",
        "r",
        "p",
        "salt_b64",
        "iv_b64",
        "ciphertext_b64",
        "updated_at"
    )

    fun tableBody(sql: String, table: String): String? {
        val pattern = Regex(
            "create table if not exists public\\.$table \\((.*?)\\);",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return pattern.find(sql)?.groupValues?.get(1)
    }

    fun columnNames(body: String): Set<String> {
        val names = LinkedHashSet<String>()
        for (raw in body.split('\n')) {
            val line = raw.trim().trimEnd(',')
            if (line.isEmpty() || line.startsWith("--") || line.startsWith("constraint ")) continue
            val name = line.substringBefore(' ').trim()
            if (name.isNotEmpty() && name[0].isLetter()) names += name
        }
        return names
    }

    fun handleUniquenessDeclared(sql: String): Boolean {
        val handles = tableBody(sql, "handles") ?: return false
        val profiles = tableBody(sql, "profiles") ?: return false
        val handlePk = handles.contains("handle text primary key")
        val profileUnique = profiles.contains("profiles_handle_unique") &&
            profiles.contains("unique (handle)")
        val noHandleUpdate = !sql.contains("policy handles_update")
        return handlePk && profileUnique && noHandleUpdate
    }

    fun rateLimitNotePresent(sql: String): Boolean {
        return sql.contains("GoTrue rate limits") && sql.contains("AuthRateLimiter")
    }
}
