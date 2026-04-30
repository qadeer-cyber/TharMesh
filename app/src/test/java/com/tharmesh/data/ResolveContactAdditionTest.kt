// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 11.1 — locks down the pure decision surface of the canonical
 * add/merge path. Every branch [MessageRepository.addOrMergeContact]
 * routes through is covered here on the JVM so Android-less CI catches
 * regressions before they reach the Room tier.
 *
 * Field-test reproduction driving these cases:
 *
 *  - Device B sees Device A via nearby advertise with a truncated
 *    `endpointName = "user-"` (corrupted profile). Pre-11.1 this
 *    silently created a contact row named "user-". Post-11.1 it
 *    must return [ContactDecision.Invalid] and write nothing.
 *
 *  - Device B later sees Device A's real ID `user-a416834d` via nearby,
 *    creating contact "user-a416834d". Then B scans A's QR (userId
 *    `user-a416834d`, displayName "Abdul", publicKey → fingerprint FP).
 *    If B pins FP on `user-a416834d` (same row — no merge needed),
 *    the decision is [ContactDecision.UpdateExisting] with the display
 *    name upgraded to "Abdul".
 *
 *  - Device B originally added A under an ALIAS userId `x-old` (with
 *    FP pinned) and later sees the real `user-a416834d` with the same
 *    FP via QR. This produces a
 *    [ContactDecision.MergeInto(canonicalUserId = x-old,
 *      sourceUserId = user-a416834d, displayName = "Abdul")] — the
 *    pre-existing row is canonical, the new userId folds into it, and
 *    the human name from the QR wins over both userId-shaped names.
 */
class ResolveContactAdditionTest {

    @Test
    fun invalidUserId_returnsInvalid() {
        val d = resolveContactAddition(
            inputUserId = "user-",
            inputDisplayName = "user-",
            existingByUserId = null,
            existingByFingerprint = null
        )
        assertEquals(ContactDecision.Invalid, d)
    }

    @Test
    fun blankUserId_returnsInvalid() {
        val d = resolveContactAddition(
            inputUserId = "   ",
            inputDisplayName = "whatever",
            existingByUserId = null,
            existingByFingerprint = null
        )
        assertEquals(ContactDecision.Invalid, d)
    }

    @Test
    fun validUserId_noExisting_createsNew_nameFallsBackToUserId() {
        val d = resolveContactAddition(
            inputUserId = "user-a416834d",
            inputDisplayName = "user-a416834d",
            existingByUserId = null,
            existingByFingerprint = null
        )
        assertTrue(d is ContactDecision.CreateNew)
        d as ContactDecision.CreateNew
        assertEquals("user-a416834d", d.userId)
        assertEquals("user-a416834d", d.displayName)
    }

    @Test
    fun validUserId_noExisting_humanNameWins() {
        val d = resolveContactAddition(
            inputUserId = "user-a416834d",
            inputDisplayName = "Abdul",
            existingByUserId = null,
            existingByFingerprint = null
        )
        assertTrue(d is ContactDecision.CreateNew)
        assertEquals("Abdul", (d as ContactDecision.CreateNew).displayName)
    }

    @Test
    fun existingUnderSameUserId_updateExisting_preservesHumanName() {
        // Nearby picker then re-adds a contact we already have a human
        // name for. The nearby side passes (userId, userId) — the rule
        // is: don't overwrite "Abdul" with "user-a416834d".
        val d = resolveContactAddition(
            inputUserId = "user-a416834d",
            inputDisplayName = "user-a416834d",
            existingByUserId = ContactRef(userId = "user-a416834d", displayName = "Abdul"),
            existingByFingerprint = null
        )
        assertTrue(d is ContactDecision.UpdateExisting)
        d as ContactDecision.UpdateExisting
        assertEquals("user-a416834d", d.userId)
        assertEquals("Abdul", d.displayName)
    }

    @Test
    fun existingUnderSameUserId_humanInputUpgradesSyntheticExisting() {
        // QR scan arrives with the human name "Abdul" for a row the
        // nearby path created with displayName == userId. The human
        // name must win.
        val d = resolveContactAddition(
            inputUserId = "user-a416834d",
            inputDisplayName = "Abdul",
            existingByUserId = ContactRef(userId = "user-a416834d", displayName = "user-a416834d"),
            existingByFingerprint = null
        )
        assertTrue(d is ContactDecision.UpdateExisting)
        assertEquals("Abdul", (d as ContactDecision.UpdateExisting).displayName)
    }

    @Test
    fun sameFingerprintUnderSameUserId_noMerge_updateExisting() {
        // fingerprint hits the SAME row we already have under this
        // userId — that is not a cross-userId merge, it's a plain
        // update-in-place.
        val sameRow = ContactRef(userId = "user-a416834d", displayName = "Abdul")
        val d = resolveContactAddition(
            inputUserId = "user-a416834d",
            inputDisplayName = "Abdul",
            existingByUserId = sameRow,
            existingByFingerprint = sameRow
        )
        assertTrue(d is ContactDecision.UpdateExisting)
    }

    @Test
    fun fingerprintMatchAtDifferentUserId_mergesHumanNameWins() {
        // Canonical row is the pre-existing nearby one. QR scan arrives
        // with a new userId (should not happen in the field — QR and
        // nearby carry the same userId — but we test the general rule).
        val d = resolveContactAddition(
            inputUserId = "new-userid",
            inputDisplayName = "Abdul",
            existingByUserId = null,
            existingByFingerprint = ContactRef(userId = "user-a416834d", displayName = "user-a416834d")
        )
        assertTrue(d is ContactDecision.MergeInto)
        d as ContactDecision.MergeInto
        assertEquals("user-a416834d", d.canonicalUserId)
        assertEquals("new-userid", d.sourceUserId)
        assertEquals("Abdul", d.displayName)
    }

    @Test
    fun fingerprintMatchAtDifferentUserId_preservesCanonicalHumanName() {
        // Canonical already has a human name ("Abdul"); incoming carries
        // a userId-shaped name. Rule: keep the human name already on the
        // canonical row.
        val d = resolveContactAddition(
            inputUserId = "user-a416834d",
            inputDisplayName = "user-a416834d",
            existingByUserId = null,
            existingByFingerprint = ContactRef(userId = "canonical-id", displayName = "Abdul")
        )
        assertTrue(d is ContactDecision.MergeInto)
        assertEquals("Abdul", (d as ContactDecision.MergeInto).displayName)
    }

    @Test
    fun fingerprintMatchAtDifferentUserId_bothSyntheticFallsBackToCanonicalUserId() {
        // Worst case: every candidate name is userId-shaped. We must
        // not pick a synthetic userId shaped name as "display" — fall
        // back to the canonical userId so the UI shows something
        // stable. (userIds here are long enough to pass IdentityValidator.)
        val d = resolveContactAddition(
            inputUserId = "userid-two",
            inputDisplayName = "userid-two",
            existingByUserId = null,
            existingByFingerprint = ContactRef(userId = "userid-one", displayName = "userid-one")
        )
        assertTrue(d is ContactDecision.MergeInto)
        val m = d as ContactDecision.MergeInto
        assertEquals("userid-one", m.canonicalUserId)
        assertEquals("userid-two", m.sourceUserId)
        assertEquals("userid-one", m.displayName)
    }

    @Test
    fun fingerprintMatch_whenSourceAlreadyHasRow_stillMerges() {
        // Two rows exist: the fingerprint-matched canonical AND a
        // separate row under the input userId. That row is the source
        // — merge it INTO canonical.
        val d = resolveContactAddition(
            inputUserId = "user-a416834d",
            inputDisplayName = "Abdul",
            existingByUserId = ContactRef(userId = "user-a416834d", displayName = "user-a416834d"),
            existingByFingerprint = ContactRef(userId = "canonical-id", displayName = "canonical-id")
        )
        assertTrue(d is ContactDecision.MergeInto)
        val m = d as ContactDecision.MergeInto
        assertEquals("canonical-id", m.canonicalUserId)
        assertEquals("user-a416834d", m.sourceUserId)
        // Human name from the QR input wins over both userId-shaped names.
        assertEquals("Abdul", m.displayName)
    }
}
