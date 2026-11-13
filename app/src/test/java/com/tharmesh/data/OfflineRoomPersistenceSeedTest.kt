// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.data

import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.ContactEntity
import com.tharmesh.db.entity.MessageEntity
import com.tharmesh.db.entity.PeerIdentityEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors [com.tharmesh.ui.debug.OfflineRoomPersistenceSmokeActivity] row shapes
 * used on the emulator (production entity classes, no mocks).
 */
class OfflineRoomPersistenceSeedTest {

    @Test
    fun seedPayload_matchesEmulatorSmoke() {
        val now = 1_700_000_000_000L
        val contactA = ContactEntity("peer-evidence-01", "Evidence Peer A", "PUBA", now, now)
        val contactB = ContactEntity("peer-evidence-02", "Evidence Peer B", "PUBB", now, now)
        val peer = PeerIdentityEntity("peer-evidence-01", "PUBA", "fp", now, true, now)
        val msg = MessageEntity(
            fromUserId = "offline-room-user",
            toUserId = "peer-evidence-01",
            peerUserId = "peer-evidence-01",
            body = "OFFLINE_PERSISTENCE_SEED_A1",
            status = MessageStatus.DELIVERED,
            timestamp = now,
            bundleId = "bundle-evidence-a1"
        )
        assertEquals("OFFLINE_PERSISTENCE_SEED_A1", msg.body)
        assertEquals(MessageStatus.DELIVERED, msg.status)
        assertEquals(true, peer.verified)
        assertEquals("Evidence Peer A", contactA.displayName)
        assertEquals("Evidence Peer B", contactB.displayName)
    }
}
