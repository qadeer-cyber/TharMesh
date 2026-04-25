package com.tharmesh.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

/**
 * Stage 6.2 — schema-bump unit test.
 *
 * Room's [androidx.room.testing.MigrationTestHelper] is an AndroidTest
 * dependency that needs an emulator/device to actually exercise the SQL on a
 * real SQLite engine; this VM has no /dev/kvm so we cannot run instrumented
 * tests here. Instead this JVM-only test asserts that
 * [AppDatabase.MIGRATION_4_5] emits the two ALTER TABLE statements the v4→v5
 * upgrade contract requires, in the right order, by capturing every
 * [SupportSQLiteDatabase.execSQL] call on a Mockito-mocked database.
 *
 * The shape of each statement is verified by content (table + column) rather
 * than full equality, so additive whitespace tweaks won't break the test.
 */
class Migration4to5Test {

    @Test
    fun migrate_addsVerifiedAndVerifiedAtMsColumns_toPeerIdentity() {
        val executed: MutableList<String> = mutableListOf()
        val db = mock(SupportSQLiteDatabase::class.java)
        doAnswer {
            executed += it.arguments[0] as String
            null
        }.`when`(db).execSQL(anyString())

        AppDatabase.MIGRATION_4_5.migrate(db)

        // Two statements, in order.
        assertEquals(
            "Migration must run exactly two ALTER TABLE statements: " + executed,
            2,
            executed.size
        )

        val first = executed[0]
        assertTrue(
            "first stmt should ALTER peer_identity to add `verified`: $first",
            first.contains("ALTER TABLE peer_identity", ignoreCase = true) &&
                first.contains("ADD COLUMN verified INTEGER NOT NULL DEFAULT 0", ignoreCase = true)
        )

        val second = executed[1]
        assertTrue(
            "second stmt should ALTER peer_identity to add `verifiedAtMs`: $second",
            second.contains("ALTER TABLE peer_identity", ignoreCase = true) &&
                second.contains("ADD COLUMN verifiedAtMs INTEGER", ignoreCase = true)
        )
    }

    @Test
    fun migrate_versionPair_isCorrect() {
        assertEquals(4, AppDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, AppDatabase.MIGRATION_4_5.endVersion)
    }
}
