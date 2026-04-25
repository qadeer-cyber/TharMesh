package com.tharmesh.db

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageStatusTest {

    @Test
    fun advance_climbsMonotonically() {
        assertEquals(MessageStatus.SENDING, MessageStatus.advance(MessageStatus.QUEUED, MessageStatus.SENDING))
        assertEquals(MessageStatus.SENT, MessageStatus.advance(MessageStatus.SENDING, MessageStatus.SENT))
        assertEquals(MessageStatus.SENT, MessageStatus.advance(MessageStatus.QUEUED, MessageStatus.SENT))
        assertEquals(MessageStatus.DELIVERED, MessageStatus.advance(MessageStatus.SENT, MessageStatus.DELIVERED))
        assertEquals(MessageStatus.READ, MessageStatus.advance(MessageStatus.DELIVERED, MessageStatus.READ))
    }

    @Test
    fun advance_doesNotRegressThroughSending() {
        // SENT → SENDING must not regress (e.g. a late BundleSending after PayloadSent).
        assertEquals(MessageStatus.SENT, MessageStatus.advance(MessageStatus.SENT, MessageStatus.SENDING))
        // DELIVERED → SENDING must not regress.
        assertEquals(MessageStatus.DELIVERED, MessageStatus.advance(MessageStatus.DELIVERED, MessageStatus.SENDING))
    }

    @Test
    fun advance_doesNotRegress() {
        assertEquals(MessageStatus.READ, MessageStatus.advance(MessageStatus.READ, MessageStatus.SENT))
        assertEquals(MessageStatus.DELIVERED, MessageStatus.advance(MessageStatus.DELIVERED, MessageStatus.SENT))
    }

    @Test
    fun advance_allowsSuccessfulRetryAfterFailed() {
        // FAILED rows are kept in the store-and-forward queue; a successful retry
        // must be able to promote them forward (FAILED → SENT → DELIVERED → READ).
        assertEquals(MessageStatus.SENT, MessageStatus.advance(MessageStatus.FAILED, MessageStatus.SENT))
        assertEquals(MessageStatus.DELIVERED, MessageStatus.advance(MessageStatus.FAILED, MessageStatus.DELIVERED))
    }

    @Test
    fun advance_allowsFailingFromAnyState() {
        assertEquals(MessageStatus.FAILED, MessageStatus.advance(MessageStatus.SENT, MessageStatus.FAILED))
        assertEquals(MessageStatus.FAILED, MessageStatus.advance(MessageStatus.DELIVERED, MessageStatus.FAILED))
    }
}
