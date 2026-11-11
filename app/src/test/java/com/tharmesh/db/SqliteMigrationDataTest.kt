// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

/**
 * Runs the real migration SQL against an in-memory SQLite database.
 * This is not an instrumented Android test and not a device test.
 * It proves v4 → v7 keeps user rows and collapses duplicate bundle ids.
 */
class SqliteMigrationDataTest {

    @Test
    fun v4_to_v7_preservesRows_andKeepsEarliestDuplicate() {
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.autoCommit = true
        conn.createStatement().execute(
            "CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "bundleId TEXT, body TEXT NOT NULL, fromUserId TEXT NOT NULL)"
        )
        conn.createStatement().execute(
            "CREATE TABLE contacts (userId TEXT PRIMARY KEY NOT NULL, displayName TEXT NOT NULL)"
        )
        conn.createStatement().execute(
            "CREATE TABLE peer_identity (" +
                "userId TEXT PRIMARY KEY NOT NULL, publicKeyBase64 TEXT NOT NULL, " +
                "fingerprint TEXT NOT NULL, firstSeenMs INTEGER NOT NULL)"
        )
        conn.createStatement().execute(
            "INSERT INTO messages (bundleId, body, fromUserId) VALUES " +
                "('dup', 'first-body', 'me'), ('dup', 'second-body', 'me'), " +
                "('unique', 'kept', 'me'), (NULL, 'no-bundle', 'me')"
        )
        conn.createStatement().execute("INSERT INTO contacts VALUES ('bob', 'Bob')")
        conn.createStatement().execute(
            "INSERT INTO peer_identity VALUES ('bob', 'PUBKEY', 'fp', 42)"
        )

        val db = sqliteDatabase(conn)
        AppDatabase.MIGRATION_4_5.migrate(db)
        AppDatabase.MIGRATION_5_6.migrate(db)
        // A retry row written after v6 must survive the v7 index change.
        conn.createStatement().execute(
            "INSERT INTO retry_state (bundleId, attemptCount, nextRetryAt, priority) " +
                "VALUES ('unique', 2, 99, 1)"
        )
        AppDatabase.MIGRATION_6_7.migrate(db)

        assertEquals("Bob", scalar(conn, "SELECT displayName FROM contacts WHERE userId = 'bob'"))
        assertEquals("PUBKEY", scalar(conn, "SELECT publicKeyBase64 FROM peer_identity WHERE userId = 'bob'"))
        assertEquals("0", scalar(conn, "SELECT verified FROM peer_identity WHERE userId = 'bob'"))
        assertEquals(1, count(conn, "SELECT COUNT(*) FROM messages WHERE bundleId = 'dup'"))
        assertEquals(
            "first-body",
            scalar(conn, "SELECT body FROM messages WHERE bundleId = 'dup'")
        )
        assertEquals("kept", scalar(conn, "SELECT body FROM messages WHERE bundleId = 'unique'"))
        assertEquals("no-bundle", scalar(conn, "SELECT body FROM messages WHERE bundleId IS NULL"))
        assertEquals("2", scalar(conn, "SELECT attemptCount FROM retry_state WHERE bundleId = 'unique'"))
        val tables = scalars(conn, "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
        assertTrue(tables.contains("messages"))
        assertTrue(tables.contains("contacts"))
        assertTrue(tables.contains("peer_identity"))
        assertTrue(tables.contains("retry_state"))
        conn.close()
    }

    private fun sqliteDatabase(conn: Connection): SupportSQLiteDatabase {
        val db = mock(SupportSQLiteDatabase::class.java)
        doAnswer { invocation ->
            conn.createStatement().execute(invocation.arguments[0] as String)
            null
        }.`when`(db).execSQL(anyString())
        return db
    }

    private fun scalar(conn: Connection, sql: String): String {
        val rs = conn.createStatement().executeQuery(sql)
        rs.next()
        val value = rs.getString(1)
        rs.close()
        return value
    }

    private fun count(conn: Connection, sql: String): Int = scalar(conn, sql).toInt()

    private fun scalars(conn: Connection, sql: String): List<String> {
        val rs = conn.createStatement().executeQuery(sql)
        val out = ArrayList<String>()
        while (rs.next()) out.add(rs.getString(1))
        rs.close()
        return out
    }
}
