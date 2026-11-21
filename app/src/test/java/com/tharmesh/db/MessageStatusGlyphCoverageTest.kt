package com.tharmesh.db

import com.tharmesh.account.DeliveryGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the 0.9 "silent tick" bug.
 *
 * [MessageStatus.RELAYING] was added without being listed in the chat-list,
 * bubble and [DeliveryGlyph] renderers, so every message that went out through a
 * relay fell into their `else -> ""` branch and lost its status indicator
 * entirely — strictly worse than 0.8, where the same message showed SENT's ✓.
 *
 * These tests pin the two invariants the renderers rely on:
 *  1. every declared status is ranked (no status silently ranks -1 like FAILED),
 *  2. RELAYING is in-flight and is NOT a signed receipt, so it must render the
 *     pending glyph and never a checkmark.
 */
class MessageStatusGlyphCoverageTest {

    @Test
    fun everyDeclaredStatusIsRankedExceptFailed() {
        for (status in MessageStatus.allStatuses()) {
            val rank = MessageStatus.rank(status)
            if (status == MessageStatus.FAILED) {
                assertEquals("FAILED is the terminal error rank", -1, rank)
            } else {
                assertTrue("$status must be ranked (unranked statuses never advance)", rank >= 0)
            }
        }
        assertEquals(
            "an unknown status must not be ranked above QUEUED",
            -1,
            MessageStatus.rank("NOT_A_STATUS")
        )
    }

    @Test
    fun rankOrderingPlacesRelayingBetweenSendingAndSent() {
        assertTrue(MessageStatus.rank(MessageStatus.QUEUED) < MessageStatus.rank(MessageStatus.SENDING))
        assertTrue(MessageStatus.rank(MessageStatus.SENDING) < MessageStatus.rank(MessageStatus.RELAYING))
        assertTrue(MessageStatus.rank(MessageStatus.RELAYING) < MessageStatus.rank(MessageStatus.SENT))
        assertTrue(MessageStatus.rank(MessageStatus.SENT) < MessageStatus.rank(MessageStatus.DELIVERED))
        assertTrue(MessageStatus.rank(MessageStatus.DELIVERED) < MessageStatus.rank(MessageStatus.READ))
    }

    @Test
    fun advanceNeverRegressesAcrossEveryStatusPair() {
        val statuses = MessageStatus.allStatuses().filter { it != MessageStatus.FAILED }
        for (from in statuses) {
            for (to in statuses) {
                val result = MessageStatus.advance(from, to)
                val expected = if (MessageStatus.rank(to) > MessageStatus.rank(from)) to else from
                assertEquals("advance($from -> $to)", expected, result)
            }
        }
    }

    @Test
    fun relayingIsInFlightAndCarriesNoSignedReceipt() {
        assertTrue(MessageStatus.isInFlight(MessageStatus.RELAYING))
        assertTrue(MessageStatus.isInFlight(MessageStatus.QUEUED))
        assertTrue(MessageStatus.isInFlight(MessageStatus.SENDING))
        assertFalse(MessageStatus.isInFlight(MessageStatus.SENT))
        assertFalse(MessageStatus.isInFlight(MessageStatus.DELIVERED))
        assertFalse(MessageStatus.isInFlight(MessageStatus.AWAITING_KEY))
        assertFalse(MessageStatus.isInFlight(MessageStatus.FAILED))

        assertFalse(
            "a relayed bundle has no signed receipt — it must not claim delivery",
            DeliveryGlyph.isSignedReceipt(MessageStatus.RELAYING)
        )
        assertEquals(
            "RELAYING must not borrow SENT's single tick",
            "",
            DeliveryGlyph.prefix(MessageStatus.RELAYING)
        )
        assertEquals("✓ ", DeliveryGlyph.prefix(MessageStatus.SENT))
        assertEquals("✓✓ ", DeliveryGlyph.prefix(MessageStatus.DELIVERED))
        assertEquals("✓✓ ", DeliveryGlyph.prefix(MessageStatus.READ))
    }

    @Test
    fun noInFlightStatusIsEverRenderedAsDelivered() {
        for (status in MessageStatus.allStatuses().filter { MessageStatus.isInFlight(it) }) {
            assertFalse("$status must not be a signed receipt", DeliveryGlyph.isSignedReceipt(status))
            assertEquals("$status must not render a checkmark", "", DeliveryGlyph.prefix(status))
        }
    }
}
