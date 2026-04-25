// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

/**
 * JVM-only schema-bump test for v5 → v6 (`retry_state` table creation).
 * Mirrors the pattern of [Migration4to5Test] — see that file for why we
 * can't run Room's [androidx.room.testing.MigrationTestHelper] in this
 * environment.
 */
class Migration5to6Test {

    @Test
    fun migrate_createsRetryStateTable_withExpectedColumns() {
        val executed: MutableList<String> = mutableListOf()
        val db = mock(SupportSQLiteDatabase::class.java)
        doAnswer {
            executed += it.arguments[0] as String
            null
        }.`when`(db).execSQL(anyString())

        AppDatabase.MIGRATION_5_6.migrate(db)

        assertEquals(
            "Migration must run exactly one CREATE TABLE statement: " + executed,
            1,
            executed.size
        )

        val stmt = executed.single()
        assertTrue(
            "stmt must CREATE TABLE retry_state: $stmt",
            stmt.contains("CREATE TABLE", ignoreCase = true) &&
                stmt.contains("retry_state", ignoreCase = true)
        )
        // Column coverage — each of the four columns present by name.
        for (col in listOf("bundleId", "attemptCount", "nextRetryAt", "priority")) {
            assertTrue("stmt must mention $col: $stmt", stmt.contains(col))
        }
        // Primary key on bundleId, every column is NOT NULL.
        assertTrue("bundleId must be primary key: $stmt", stmt.contains("PRIMARY KEY(`bundleId`)"))
        assertTrue("attemptCount must be NOT NULL INTEGER: $stmt", stmt.contains("`attemptCount` INTEGER NOT NULL"))
        assertTrue("nextRetryAt must be NOT NULL INTEGER: $stmt", stmt.contains("`nextRetryAt` INTEGER NOT NULL"))
        assertTrue("priority must be NOT NULL INTEGER: $stmt", stmt.contains("`priority` INTEGER NOT NULL"))
    }

    @Test
    fun migrate_versionPair_isCorrect() {
        assertEquals(5, AppDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, AppDatabase.MIGRATION_5_6.endVersion)
    }
}
