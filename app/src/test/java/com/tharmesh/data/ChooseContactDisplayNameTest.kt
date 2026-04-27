package com.tharmesh.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks down the displayName-resolution rule used by
 * [MessageRepository.addContact]. Field-test reproduction:
 *
 *  - Phone B scans Phone A's QR (which carries
 *    `userId=user-54601948, name="Abdul Qadeer"`). Contact created on
 *    B with the human name.
 *  - B then taps Phone A in the nearby device picker. The Nearby
 *    transport advertises `endpointName=userId`, so the [MeshNode]
 *    has `userId == name == "user-54601948"`. The picker called
 *    `addContact("user-54601948", "user-54601948")`, and the legacy
 *    `displayName.ifBlank { userId }` rule silently overwrote the
 *    human "Abdul Qadeer" name with the raw userId.
 *
 * The new rule preserves any existing human name when the caller
 * passes the userId-as-fallback displayName, while still letting an
 * explicit human name win when one is supplied.
 */
class ChooseContactDisplayNameTest {

    @Test
    fun blankInput_withNoExisting_fallsBackToUserId() {
        val result = chooseContactDisplayName(
            inputUserId = "user-54601948",
            inputDisplayName = "",
            existingDisplayName = null
        )
        assertEquals("user-54601948", result)
    }

    @Test
    fun blankInput_preservesExistingHumanName() {
        val result = chooseContactDisplayName(
            inputUserId = "user-54601948",
            inputDisplayName = "",
            existingDisplayName = "Abdul Qadeer"
        )
        assertEquals("Abdul Qadeer", result)
    }

    @Test
    fun humanInput_withNoExisting_writesHumanName() {
        val result = chooseContactDisplayName(
            inputUserId = "user-54601948",
            inputDisplayName = "Abdul Qadeer",
            existingDisplayName = null
        )
        assertEquals("Abdul Qadeer", result)
    }

    @Test
    fun humanInput_overridesExistingHumanName() {
        // An explicit human-supplied name wins (e.g. user-initiated rename
        // from a fresh QR scan, contact-edit screen, etc.).
        val result = chooseContactDisplayName(
            inputUserId = "user-54601948",
            inputDisplayName = "Abdul Qadeer (work)",
            existingDisplayName = "Abdul Qadeer"
        )
        assertEquals("Abdul Qadeer (work)", result)
    }

    @Test
    fun syntheticInput_equalToUserId_doesNotDowngradeExistingHuman() {
        // The bug repro: nearby picker calls
        // addContact(userId, userId) and used to clobber the human name.
        val result = chooseContactDisplayName(
            inputUserId = "user-54601948",
            inputDisplayName = "user-54601948",
            existingDisplayName = "Abdul Qadeer"
        )
        assertEquals("Abdul Qadeer", result)
    }

    @Test
    fun syntheticInput_withSyntheticExisting_writesUserId() {
        // Both sides synthetic (== userId); nothing to preserve. The
        // result is the userId either way, but the path still has to
        // be reached without throwing.
        val result = chooseContactDisplayName(
            inputUserId = "user-54601948",
            inputDisplayName = "user-54601948",
            existingDisplayName = "user-54601948"
        )
        assertEquals("user-54601948", result)
    }

    @Test
    fun syntheticInput_withNoExisting_writesUserId() {
        val result = chooseContactDisplayName(
            inputUserId = "user-54601948",
            inputDisplayName = "user-54601948",
            existingDisplayName = null
        )
        assertEquals("user-54601948", result)
    }

    @Test
    fun syntheticInput_withBlankExisting_writesUserId() {
        // Defensive: a degenerate existing row with a blank
        // displayName should not be treated as "human" — fall through
        // to userId.
        val result = chooseContactDisplayName(
            inputUserId = "user-54601948",
            inputDisplayName = "user-54601948",
            existingDisplayName = ""
        )
        assertEquals("user-54601948", result)
    }
}
