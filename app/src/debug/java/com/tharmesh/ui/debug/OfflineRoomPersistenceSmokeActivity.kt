// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.ui.debug

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.data.UserPrefs
import com.tharmesh.data.UserProfile
import com.tharmesh.db.AppDatabase
import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.ContactEntity
import com.tharmesh.db.entity.ConversationEntity
import com.tharmesh.db.entity.MessageEntity
import com.tharmesh.db.entity.PeerIdentityEntity
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Seeds contacts, trusted peer keys, conversations, and messages through the real Room DB
 * ([AppDatabase] production path). Used by emulator evidence scripts.
 */
class OfflineRoomPersistenceSmokeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runBlocking {
            seed()
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private suspend fun seed() {
        UserPrefs.saveProfile(
            this,
            UserProfile(userId = "offline-room-user", username = "Room Smoke", authProvider = UserPrefs.PROVIDER_ANONYMOUS)
        )
        UserPrefs.markOnboarded(this)
        UserPrefs.markTermsAccepted(this)
        UserPrefs.setIdentityHeld(this, false)
        val mesh = CryptoIdentity.generate()
        UserPrefs.writeIdentity(this, mesh, allowReplace = true)

        val peerA = CryptoIdentity.generate()
        val peerB = CryptoIdentity.generate()
        val idA = "peer-evidence-01"
        val idB = "peer-evidence-02"
        val titleA = "Evidence Peer A"
        val titleB = "Evidence Peer B"

        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(applicationContext)
            val now = System.currentTimeMillis()
            db.contactDao().upsert(
                ContactEntity(userId = idA, displayName = titleA, publicKey = peerA.publicKeyBase64, addedAt = now, lastSeen = now)
            )
            db.contactDao().upsert(
                ContactEntity(userId = idB, displayName = titleB, publicKey = peerB.publicKeyBase64, addedAt = now, lastSeen = now)
            )
            db.peerIdentityDao().insertIfAbsent(
                PeerIdentityEntity(
                    userId = idA,
                    publicKeyBase64 = peerA.publicKeyBase64,
                    fingerprint = peerA.fingerprint,
                    firstSeenMs = now,
                    verified = true,
                    verifiedAtMs = now
                )
            )
            db.peerIdentityDao().insertIfAbsent(
                PeerIdentityEntity(
                    userId = idB,
                    publicKeyBase64 = peerB.publicKeyBase64,
                    fingerprint = peerB.fingerprint,
                    firstSeenMs = now,
                    verified = true,
                    verifiedAtMs = now
                )
            )

            db.conversationDao().upsert(
                ConversationEntity(
                    userId = idA,
                    title = titleA,
                    lastMessage = "OFFLINE_PERSISTENCE_SEED_A2",
                    lastTimestamp = now + 2,
                    lastMessageStatus = MessageStatus.DELIVERED
                )
            )
            db.conversationDao().upsert(
                ConversationEntity(
                    userId = idB,
                    title = titleB,
                    lastMessage = "OFFLINE_PERSISTENCE_SEED_B1",
                    lastTimestamp = now + 1,
                    lastMessageStatus = MessageStatus.DELIVERED
                )
            )

            db.messageDao().insert(
                MessageEntity(
                    fromUserId = "offline-room-user",
                    toUserId = idA,
                    peerUserId = idA,
                    body = "OFFLINE_PERSISTENCE_SEED_A1",
                    status = MessageStatus.DELIVERED,
                    timestamp = now,
                    bundleId = "bundle-evidence-a1"
                )
            )
            db.messageDao().insert(
                MessageEntity(
                    fromUserId = "offline-room-user",
                    toUserId = idA,
                    peerUserId = idA,
                    body = "OFFLINE_PERSISTENCE_SEED_A2",
                    status = MessageStatus.DELIVERED,
                    timestamp = now + 2,
                    bundleId = "bundle-evidence-a2"
                )
            )
            db.messageDao().insert(
                MessageEntity(
                    fromUserId = "offline-room-user",
                    toUserId = idB,
                    peerUserId = idB,
                    body = "OFFLINE_PERSISTENCE_SEED_B1",
                    status = MessageStatus.DELIVERED,
                    timestamp = now + 1,
                    bundleId = "bundle-evidence-b1"
                )
            )
        }
    }
}
