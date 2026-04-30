// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.identity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the accept/reject contract for [IdentityValidator.isValidUserId].
 * The rejects list matches the four failure modes observed in the field
 * (see class doc on [IdentityValidator]).
 */
class IdentityValidatorTest {

    // ---------- rejects ---------- //

    @Test fun rejects_emptyString() {
        assertFalse(IdentityValidator.isValidUserId(""))
    }

    @Test fun rejects_whitespaceOnly() {
        assertFalse(IdentityValidator.isValidUserId("   "))
        assertFalse(IdentityValidator.isValidUserId("\t\n"))
    }

    @Test fun rejects_bareAnonPrefix() {
        // The exact bug: remote device advertised `endpointName = "user-"`
        // (truncated profile), banner's Add button persisted it.
        assertFalse(IdentityValidator.isValidUserId("user-"))
    }

    @Test fun rejects_anonPrefixWithWhitespaceSuffix() {
        assertFalse(IdentityValidator.isValidUserId("user-   "))
    }

    @Test fun rejects_anonPrefixWithShortSuffix() {
        // `user-a` is 6 chars but suffix is 1 — clearly truncated.
        assertFalse(IdentityValidator.isValidUserId("user-a"))
        assertFalse(IdentityValidator.isValidUserId("user-abcde"))
    }

    @Test fun rejects_shorterThanMinimum() {
        assertFalse(IdentityValidator.isValidUserId("u"))
        assertFalse(IdentityValidator.isValidUserId("user"))
        assertFalse(IdentityValidator.isValidUserId("abc123"))
    }

    @Test fun rejects_controlCharacters() {
        assertFalse(IdentityValidator.isValidUserId("user-abc\u0000def"))
        assertFalse(IdentityValidator.isValidUserId("user-abc\u0007def"))
    }

    // ---------- accepts ---------- //

    @Test fun accepts_anonUserIdFromUserPrefs() {
        // UserPrefs.ensureProfile generates exactly this shape.
        assertTrue(IdentityValidator.isValidUserId("user-a416834d"))
        assertTrue(IdentityValidator.isValidUserId("user-abcdef12"))
    }

    @Test fun accepts_googleDerivedUserId() {
        // UserPrefs.userIdFromGoogleSub generates "U" + <12 hex>.
        assertTrue(IdentityValidator.isValidUserId("U2c9f0a1b3d4e"))
    }

    @Test fun accepts_longerAnonSuffix() {
        // Forward-compat: a future 16-hex suffix still passes.
        assertTrue(IdentityValidator.isValidUserId("user-a416834d01020304"))
    }

    @Test fun accepts_opaqueUserId() {
        // Non-prefixed IDs (e.g. fork that uses UUID strings) pass as
        // long as they're long enough.
        assertTrue(IdentityValidator.isValidUserId("peer-xyz789"))
        assertTrue(IdentityValidator.isValidUserId("0123456789abcdef"))
    }

    @Test fun accepts_inputWithSurroundingWhitespace() {
        // isValidUserId trims before checking so callers can pass raw
        // user input from an EditText without pre-trimming.
        assertTrue(IdentityValidator.isValidUserId("  user-a416834d  "))
    }
}
