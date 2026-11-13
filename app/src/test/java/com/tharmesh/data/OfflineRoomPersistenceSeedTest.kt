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
        val peerId = "peer-evidence-01"
        val now = 1_700_000_000_000L
        val contact = ContactEntity(peerId, "Evidence Peer", "PUB", now, now)
        val peer = PeerIdentityEntity(peerId, "PUB", "fp", now, true, now)
        val msg = MessageEntity(
            fromUserId = "offline-room-user",
            toUserId = peerId,
            peerUserId = peerId,
            body = "OFFLINE_PERSISTENCE_SEED",
            status = MessageStatus.DELIVERED,
            timestamp = now,
            bundleId = "bundle-evidence-seed"
        )
        assertEquals("OFFLINE_PERSISTENCE_SEED", msg.body)
        assertEquals(MessageStatus.DELIVERED, msg.status)
        assertEquals(true, peer.verified)
        assertEquals(peerId, contact.userId)
    }
}
