// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.data

import com.tharmesh.db.entity.ContactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Stage 11.1 — unit tests for [IdentityDedupMigration.pickCanonical],
 * the pure ranking function used by the one-shot dedup sweep to choose
 * which of N same-fingerprint contacts survives. Field-test driven:
 *
 *  - If one row has a human name ("Abdul") and another has
 *    `displayName == userId` ("user-a416834d"), the human-named row
 *    wins regardless of which was added first.
 *
 *  - Ties within the human-named subset are broken by the earliest
 *    `addedAt` (first pin wins — stable, idempotent).
 *
 *  - If every candidate has a userId-shaped displayName, the earliest
 *    `addedAt` still wins so repeated runs pick the same row.
 */
class IdentityDedupMigrationPickCanonicalTest {

    private fun c(
        userId: String,
        displayName: String,
        addedAt: Long
    ): ContactEntity = ContactEntity(
        userId = userId,
        displayName = displayName,
        publicKey = "",
        addedAt = addedAt,
        lastSeen = addedAt
    )

    @Test
    fun humanNameBeatsUserIdShaped_evenIfHumanRowIsNewer() {
        val canonical = IdentityDedupMigration.pickCanonical(
            listOf(
                c("user-a416834d", "user-a416834d", addedAt = 100L),
                c("real-id-xyz", "Abdul", addedAt = 500L)
            )
        )
        assertEquals("real-id-xyz", canonical.userId)
    }

    @Test
    fun humanNameTie_earliestAddedAtWins() {
        val canonical = IdentityDedupMigration.pickCanonical(
            listOf(
                c("u2", "Abdul", addedAt = 200L),
                c("u1", "Abdul", addedAt = 100L),
                c("u3", "Abdul", addedAt = 300L)
            )
        )
        assertEquals("u1", canonical.userId)
    }

    @Test
    fun allSyntheticNames_earliestAddedAtWins() {
        val canonical = IdentityDedupMigration.pickCanonical(
            listOf(
                c("user-a", "user-a", addedAt = 400L),
                c("user-b", "user-b", addedAt = 100L),
                c("user-c", "user-c", addedAt = 200L)
            )
        )
        assertEquals("user-b", canonical.userId)
    }

    @Test
    fun blankDisplayNameTreatedAsSynthetic() {
        // A row with a blank displayName isn't "human"; a sibling with
        // a real name beats it even if the blank row is older.
        val canonical = IdentityDedupMigration.pickCanonical(
            listOf(
                c("u-old", "", addedAt = 100L),
                c("u-new", "Abdul", addedAt = 900L)
            )
        )
        assertEquals("u-new", canonical.userId)
    }

    @Test
    fun singletonGroup_returnsItself() {
        val row = c("only", "Abdul", addedAt = 42L)
        assertEquals(row, IdentityDedupMigration.pickCanonical(listOf(row)))
    }

    @Test
    fun emptyGroup_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            IdentityDedupMigration.pickCanonical(emptyList())
        }
    }
}
