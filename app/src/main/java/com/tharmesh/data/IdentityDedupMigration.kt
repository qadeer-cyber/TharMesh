// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.data

import android.content.Context
import com.tharmesh.db.AppDatabase
import com.tharmesh.db.entity.ContactEntity
import com.tharmesh.identity.IdentityValidator

/**
 * Stage 11.1 — one-shot cleanup that collapses pre-migration duplicate
 * identities created by the Stage ≤ 11.0 add-contact paths. Runs on
 * every fresh install, returns immediately on subsequent launches
 * (gated by [PREF_HAS_RUN] in SharedPreferences).
 *
 * Why this exists:
 *
 *  The field-observed bug ([Stage 11.1 bug report]) on an Android 9
 *  device showed three contacts/chats for one physical peer:
 *    - `user-`           (truncated endpoint name from a corrupted
 *                         nearby advertise)
 *    - `user-a416834d`   (the peer's real anonymous userId)
 *    - `Abdul`           (display name learned via QR for the same
 *                         peer; the row has the real userId but the
 *                         UI showed the name)
 *
 *  Pre-11.1, every `addContact` call blindly wrote whatever userId the
 *  caller passed, with no dedup-by-fingerprint. The Stage 11.1 write
 *  path (see [MessageRepository.addOrMergeContact]) prevents new
 *  duplicates; this migration cleans up the ones the old paths left
 *  behind.
 *
 * Algorithm (one pass, IO-only):
 *
 *  1. **Group by fingerprint.** Walk every row in `peer_identity` and
 *     bucket `contacts` by the fingerprint pinned to their userId.
 *     Any group with 2+ contacts is a dedup candidate. Pick the
 *     canonical row: human display name > userId-shaped display name;
 *     break ties by earliest `addedAt`. Merge every other row in the
 *     group INTO the canonical userId via
 *     [MessageRepository.mergeConversationsBlocking] (re-parents
 *     messages, drops source `conversations` / `contacts` /
 *     `peer_identity`).
 *
 *  2. **Drop invalid userIds.** Any `contacts` row whose userId fails
 *     [IdentityValidator.isValidUserId] (typically the stuck `"user-"`
 *     row) is deleted outright. Its `conversations` row is also
 *     dropped; its `messages` are orphaned by design — there is no
 *     real peer to address them to, so keeping them would just leak
 *     one-sided history into the canonical contact's chat.
 *
 *  3. **Flip [PREF_HAS_RUN] = true.** Subsequent calls short-circuit.
 *
 * Idempotent: a second run of the same algorithm against a clean DB
 * finds no groups and no invalid rows, and writes nothing. Safe to run
 * concurrently with the UI — Room serializes per-table writes — but
 * we launch it on `appScope` before [MessageRepository] starts any
 * retry work to minimize user-visible churn.
 *
 * All DAO access here is synchronous / blocking — call from an IO
 * dispatcher. No Android context use beyond the SharedPreferences flag.
 */
object IdentityDedupMigration {

    private const val PREFS_NAME = "identity_dedup_prefs"
    internal const val PREF_HAS_RUN = "dedup_v1_run"

    /**
     * Outcome summary, returned for diagnostics / logging. Not
     * surfaced to the UI.
     */
    data class Report(
        val mergedGroups: Int,
        val contactsRemoved: Int,
        val invalidRowsRemoved: Int,
        val skippedAlreadyRun: Boolean
    )

    /**
     * Run once per install. The first non-zero group merged OR invalid
     * row dropped writes the has-run flag before any DB work
     * commits — if we crash halfway through, a subsequent launch just
     * picks up wherever we left off (the algorithm is idempotent).
     */
    @JvmStatic
    fun runIfNeeded(context: Context, repository: MessageRepository): Report {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_HAS_RUN, false)) {
            return Report(
                mergedGroups = 0,
                contactsRemoved = 0,
                invalidRowsRemoved = 0,
                skippedAlreadyRun = true
            )
        }
        val report = runMigration(repository)
        prefs.edit().putBoolean(PREF_HAS_RUN, true).apply()
        return report
    }

    /**
     * Pure execution entry point — does NOT touch SharedPreferences.
     * Exposed for unit tests; production callers use [runIfNeeded].
     */
    internal fun runMigration(repository: MessageRepository): Report {
        val db = repository.database
        val contactDao = db.contactDao()
        val peerIdentityDao = db.peerIdentityDao()

        val contacts = contactDao.getAll()
        val identities = peerIdentityDao.getAll().associateBy { it.userId }

        // ---------------- Phase 1: fingerprint-based merge ---------------- //

        var mergedGroups = 0
        var contactsRemoved = 0

        // fingerprint -> list of contacts whose userId has a peer_identity row
        // pinning that fingerprint. Contacts with no pinned identity are
        // skipped (we have no cross-userId evidence they're duplicates).
        val byFingerprint: Map<String, List<ContactEntity>> = contacts
            .mapNotNull { c ->
                val fp = identities[c.userId]?.fingerprint?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                fp to c
            }
            .groupBy({ it.first }, { it.second })
            .filter { it.value.size >= 2 }

        for ((_, group) in byFingerprint) {
            val canonical = pickCanonical(group)
            for (source in group) {
                if (source.userId == canonical.userId) continue
                repository.mergeConversationsBlockingForMigration(
                    sourceUserId = source.userId,
                    destUserId = canonical.userId
                )
                contactsRemoved++
            }
            mergedGroups++
        }

        // ---------------- Phase 2: drop invalid userIds ---------------- //

        var invalidRowsRemoved = 0
        val convDao = db.conversationDao()
        // Re-read after phase 1 merges so we don't double-delete a row that
        // the merge already dropped.
        val remaining = contactDao.getAll()
        for (c in remaining) {
            if (IdentityValidator.isValidUserId(c.userId)) continue
            // The row is bad. No fingerprint merge target (otherwise phase 1
            // would have removed it). Drop contact + conversation.
            convDao.deleteByUserId(c.userId)
            contactDao.deleteByUserId(c.userId)
            peerIdentityDao.deleteByUserId(c.userId)
            invalidRowsRemoved++
        }

        return Report(
            mergedGroups = mergedGroups,
            contactsRemoved = contactsRemoved,
            invalidRowsRemoved = invalidRowsRemoved,
            skippedAlreadyRun = false
        )
    }

    /**
     * Pick the canonical contact from a same-fingerprint group:
     *  1. Prefer rows with a HUMAN display name (non-blank and !=
     *     userId).
     *  2. Among those, the earliest `addedAt` wins (preserves the
     *     original pin).
     *  3. If every row has a userId-shaped display name, fall back to
     *     earliest `addedAt`.
     *
     * Pure / referentially transparent — exposed `internal` for tests.
     */
    internal fun pickCanonical(group: List<ContactEntity>): ContactEntity {
        require(group.isNotEmpty()) { "group must be non-empty" }
        val human = group.filter {
            it.displayName.isNotBlank() && it.displayName != it.userId
        }
        val pool = if (human.isNotEmpty()) human else group
        return pool.minByOrNull { it.addedAt } ?: pool.first()
    }
}
