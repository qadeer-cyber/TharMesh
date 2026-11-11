// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

/**
 * v6 → v7 keeps every table and collapses duplicate bundle ids before
 * the unique index is created. It must not wipe the database.
 */
class Migration6to7Test {

    @Test
    fun migrate_dedupesThenCreatesUniqueIndex_withoutDroppingTables() {
        val executed: MutableList<String> = mutableListOf()
        val db = mock(SupportSQLiteDatabase::class.java)
        doAnswer {
            executed += it.arguments[0] as String
            null
        }.`when`(db).execSQL(anyString())

        AppDatabase.MIGRATION_6_7.migrate(db)

        assertEquals(3, executed.size)
        val joined = executed.joinToString("\n")
        assertFalse(joined.contains("DROP TABLE", ignoreCase = true))
        assertTrue(executed[0].contains("DELETE FROM messages", ignoreCase = true))
        assertTrue(executed[0].contains("MIN(id)", ignoreCase = true))
        assertTrue(executed[1].contains("DROP INDEX", ignoreCase = true))
        assertTrue(executed[2].contains("CREATE UNIQUE INDEX", ignoreCase = true))
        assertTrue(executed[2].contains("index_messages_bundleId"))
    }

    @Test
    fun migrate_versionPair_isCorrect() {
        assertEquals(6, AppDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, AppDatabase.MIGRATION_6_7.endVersion)
    }
}
