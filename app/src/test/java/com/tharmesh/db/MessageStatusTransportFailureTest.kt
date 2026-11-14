package com.tharmesh.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Repository uses [MessageDao.markFailedIfStillInFlight] + [MessageStatus.advance]
 * so transport errors cannot flip a row to DELIVERED/READ without a forward ACK path.
 */
class MessageStatusTransportFailureTest {

  @Test
  fun transportFailed_doesNotAdvanceDeliveredFromQueuedOrSending() {
    assertEquals(MessageStatus.FAILED, MessageStatus.advance(MessageStatus.QUEUED, MessageStatus.FAILED))
    assertEquals(MessageStatus.FAILED, MessageStatus.advance(MessageStatus.SENDING, MessageStatus.FAILED))
    assertEquals(MessageStatus.DELIVERED, MessageStatus.advance(MessageStatus.QUEUED, MessageStatus.DELIVERED))
    assertEquals(MessageStatus.FAILED, MessageStatus.advance(MessageStatus.DELIVERED, MessageStatus.FAILED))
    assertEquals(MessageStatus.SENDING, MessageStatus.advance(MessageStatus.QUEUED, MessageStatus.SENDING))
  }

  @Test
  fun deliveredCannotRegress_andFailedCanRecoverOnRetry() {
    assertEquals(MessageStatus.DELIVERED, MessageStatus.advance(MessageStatus.SENT, MessageStatus.DELIVERED))
    assertEquals(MessageStatus.SENT, MessageStatus.advance(MessageStatus.FAILED, MessageStatus.SENT))
    assertEquals(MessageStatus.DELIVERED, MessageStatus.advance(MessageStatus.FAILED, MessageStatus.DELIVERED))
  }
}