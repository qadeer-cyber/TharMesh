package com.tharmesh.db

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageStatusTest {

    @Test
    fun advance_climbsMonotonically() {
        assertEquals(MessageStatus.SENT, MessageStatus.advance(MessageStatus.QUEUED, MessageStatus.SENT))
        assertEquals(MessageStatus.DELIVERED, MessageStatus.advance(MessageStatus.SENT, MessageStatus.DELIVERED))
        assertEquals(MessageStatus.READ, MessageStatus.advance(MessageStatus.DELIVERED, MessageStatus.READ))
    }

    @Test
    fun advance_doesNotRegress() {
        assertEquals(MessageStatus.READ, MessageStatus.advance(MessageStatus.READ, MessageStatus.SENT))
        assertEquals(MessageStatus.DELIVERED, MessageStatus.advance(MessageStatus.DELIVERED, MessageStatus.SENT))
    }

    @Test
    fun advance_failedIsSticky() {
        assertEquals(MessageStatus.FAILED, MessageStatus.advance(MessageStatus.FAILED, MessageStatus.SENT))
    }

    @Test
    fun advance_allowsFailingFromAnyState() {
        assertEquals(MessageStatus.FAILED, MessageStatus.advance(MessageStatus.SENT, MessageStatus.FAILED))
    }
}
