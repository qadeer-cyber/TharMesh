package com.tharmesh.ui.contacts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tharmesh.TharMeshApp
import com.tharmesh.db.entity.ContactEntity
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.identity.PeerTrustStore
import com.tharmesh.ui.chat.ChatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tharmesh.app.R

/**
 * PR B — full Contact Profile screen reachable via a long-press on a row in
 * [ContactsFragment]. Shows avatar, name, online dot, trust shield, full
 * userId, full SHA-256 fingerprint of the pinned key, and the "added" date.
 *
 * Three actions:
 *  - Message: opens [ChatActivity] for the userId.
 *  - Verify by QR: opens [ScanQrActivity] and routes the result through
 *    [PeerTrustStore.markVerified]. Mismatch surfaces a toast and never
 *    overwrites the pinned key.
 *  - Remove contact: confirms with an [AlertDialog], then calls
 *    [com.tharmesh.data.MessageRepository.removeContact]. Conversation rows,
 *    message history, and the TOFU pin in `peer_identity` are intentionally
 *    preserved so the user can re-add the contact later and resume the same
 *    chat thread + verified shield.
 */
class ContactProfileActivity : AppCompatActivity() {

    private lateinit var userId: String
    private var contact: ContactEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userId = intent.getStringExtra(EXTRA_USER_ID).orEmpty()
        if (userId.isBlank()) { finish(); return }
        setContentView(R.layout.activity_contact_profile)

        findViewById<Button>(R.id.btn_message).setOnClickListener { onMessage() }
        findViewById<Button>(R.id.btn_verify_qr).setOnClickListener { onVerifyByQr() }
        findViewById<Button>(R.id.btn_block).setOnClickListener { onBlockToggle() }
        findViewById<Button>(R.id.btn_remove).setOnClickListener { onRemove() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val app = TharMeshApp.get()
            val data = withContext(Dispatchers.IO) {
                val c = app.database.contactDao().getByUserId(userId)
                val key = app.peerTrustStore.storedKey(userId)
                val fp = key?.let { CryptoIdentity.fingerprintOf(it) }
                val state = app.peerTrustStore.trustState(userId)
                Triple(c, fp, state)
            }
            val c = data.first
            contact = c
            val fp = data.second
            val state = data.third
            val nodes = TharMeshApp.get().directory.nodes.value
            val online = nodes.any { it.userId == userId && it.online }

            val displayName = c?.displayName ?: userId
            findViewById<TextView>(R.id.profile_name).text = displayName
            findViewById<TextView>(R.id.profile_avatar).text = displayName.take(1).uppercase()
            findViewById<TextView>(R.id.profile_userid).text = userId
            findViewById<View>(R.id.profile_dot_online).visibility =
                if (online) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.profile_status).text = when {
                online -> getString(R.string.contacts_status_online)
                c?.lastSeen != null && c.lastSeen > 0L -> getString(
                    R.string.contacts_status_last_seen_fmt,
                    DateUtils.getRelativeTimeSpanString(c.lastSeen).toString()
                )
                else -> getString(R.string.contacts_status_offline)
            }
            findViewById<TextView>(R.id.profile_fingerprint).text =
                fp ?: getString(R.string.contact_profile_no_fingerprint)
            findViewById<TextView>(R.id.profile_added).text =
                if (c?.addedAt != null && c.addedAt > 0L) {
                    DateUtils.getRelativeTimeSpanString(c.addedAt).toString()
                } else "—"
            ShieldRenderer.bind(findViewById(R.id.profile_shield), state)
            // Stage 8.4 — Block button label reflects current state. We
            // re-read the block list on every refresh so the label
            // stays correct after onBlockToggle() and after any
            // BlockedContactsActivity round-trip.
            val blocked = com.tharmesh.data.BlockedContacts.isBlocked(
                this@ContactProfileActivity, userId
            )
            findViewById<Button>(R.id.btn_block).setText(
                if (blocked) R.string.contact_unblock else R.string.contact_block
            )
        }
    }

    private fun onMessage() {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra(ChatActivity.EXTRA_TO_USER_ID, userId)
        intent.putExtra(ChatActivity.EXTRA_TITLE, contact?.displayName ?: userId)
        startActivity(intent)
    }

    private fun onVerifyByQr() {
        startActivityForResult(Intent(this, ScanQrActivity::class.java), REQUEST_SCAN_QR)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCAN_QR || resultCode != Activity.RESULT_OK) return
        val scannedUserId = data?.getStringExtra(ScanQrActivity.RESULT_CODE).orEmpty().trim()
        val pubKey = data?.getStringExtra(ScanQrActivity.RESULT_PUB_KEY)?.takeIf { it.isNotBlank() }
        if (scannedUserId.isEmpty() || pubKey == null) return
        if (scannedUserId != userId) {
            Toast.makeText(this, getString(R.string.trust_mismatch_toast, userId, scannedUserId), Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                TharMeshApp.get().peerTrustStore.markVerified(scannedUserId, pubKey)
            }
            val displayName = contact?.displayName ?: scannedUserId
            val msg = when (res) {
                is PeerTrustStore.VerifyResult.Verified ->
                    getString(R.string.trust_verified_toast, displayName)
                is PeerTrustStore.VerifyResult.AlreadyVerified ->
                    getString(R.string.trust_already_verified_toast, displayName)
                is PeerTrustStore.VerifyResult.Mismatch ->
                    getString(R.string.trust_mismatch_toast, res.storedFingerprint, res.scannedFingerprint)
            }
            Toast.makeText(this@ContactProfileActivity, msg, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    /**
     * Stage 8.4 \u2014 Pakistan compliance: Block / Unblock toggle.
     *
     * Block: confirms with an [AlertDialog] before adding the userId
     * to the local block list. Existing chat history is preserved (we
     * do NOT auto-remove the contact) so the user can still see what
     * was said \u2014 future inbound bundles from this peer are silently
     * dropped by [com.tharmesh.data.MessageRepository.handleIncomingBundle]
     * before any Room write, and any future re-add via QR / nearby /
     * invite is silently rejected by addContact.
     *
     * Unblock: a lighter confirm and a removal from the block list.
     * Does NOT auto-restore a contact row; the user can re-add the
     * contact through normal flows.
     */
    private fun onBlockToggle() {
        val blocked = com.tharmesh.data.BlockedContacts.isBlocked(this, userId)
        if (blocked) {
            AlertDialog.Builder(this)
                .setTitle(R.string.contact_unblock_confirm_title)
                .setMessage(R.string.contact_unblock_confirm_message)
                .setPositiveButton(R.string.contact_unblock_confirm_button) { _, _ ->
                    com.tharmesh.data.BlockedContacts.unblock(this, userId)
                    Toast.makeText(this, R.string.contact_unblocked_toast, Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.contact_block_confirm_title)
                .setMessage(R.string.contact_block_confirm_message)
                .setPositiveButton(R.string.contact_block_confirm_button) { _, _ ->
                    com.tharmesh.data.BlockedContacts.block(this, userId)
                    Toast.makeText(this, R.string.contact_blocked_toast, Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun onRemove() {
        val displayName = contact?.displayName ?: userId
        AlertDialog.Builder(this)
            .setTitle(R.string.contact_profile_remove_confirm_title)
            .setMessage(getString(R.string.contact_profile_remove_confirm_msg, displayName))
            .setPositiveButton(R.string.contact_profile_remove) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        TharMeshApp.get().repository.removeContact(userId)
                    }
                    Toast.makeText(
                        this@ContactProfileActivity,
                        getString(R.string.contact_profile_removed_toast, displayName),
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_USER_ID = "extra_user_id"
        private const val REQUEST_SCAN_QR = 7013
    }
}
