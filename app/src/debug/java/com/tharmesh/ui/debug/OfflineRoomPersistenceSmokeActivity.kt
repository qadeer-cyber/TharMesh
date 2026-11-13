// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.ui.debug

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tharmesh.data.UserPrefs
import com.tharmesh.data.UserProfile
import com.tharmesh.db.AppDatabase
import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.ContactEntity
import com.tharmesh.db.entity.MessageEntity
import com.tharmesh.db.entity.PeerIdentityEntity
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Seeds contacts, trusted peer keys, and messages through the real Room DB
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

        val peer = CryptoIdentity.generate()
        val peerId = "peer-evidence-01"
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(applicationContext)
            val now = System.currentTimeMillis()
            db.contactDao().upsert(
                ContactEntity(
                    userId = peerId,
                    displayName = "Evidence Peer",
                    publicKey = peer.publicKeyBase64,
                    addedAt = now,
                    lastSeen = now
                )
            )
            db.peerIdentityDao().insertIfAbsent(
                PeerIdentityEntity(
                    userId = peerId,
                    publicKeyBase64 = peer.publicKeyBase64,
                    fingerprint = peer.fingerprint,
                    firstSeenMs = now,
                    verified = true,
                    verifiedAtMs = now
                )
            )
            db.messageDao().insert(
                MessageEntity(
                    fromUserId = "offline-room-user",
                    toUserId = peerId,
                    peerUserId = peerId,
                    body = "OFFLINE_PERSISTENCE_SEED",
                    status = MessageStatus.DELIVERED,
                    timestamp = System.currentTimeMillis(),
                    bundleId = "bundle-evidence-seed"
                )
            )
        }
    }
}
