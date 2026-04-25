package com.tharmesh.ui.contacts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tharmesh.app.R
import com.tharmesh.TharMeshApp
import com.tharmesh.data.MessageRepository
import com.tharmesh.db.entity.ContactEntity
import com.tharmesh.identity.PeerTrustStore
import com.tharmesh.ui.chat.ChatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Room-backed contacts list; tap to open chat, buttons to scan / enter invite. */
class ContactsActivity : AppCompatActivity() {

    private lateinit var adapter: ContactAdapter
    private lateinit var repository: MessageRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)
        TharMeshApp.get().ensureMeshStarted()
        repository = TharMeshApp.get().repository

        val recycler: RecyclerView = findViewById(R.id.recycler_contacts)
        val empty: TextView = findViewById(R.id.text_empty)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ContactAdapter(
            onClick = { contact ->
                val next = Intent(this, ChatActivity::class.java)
                next.putExtra(ChatActivity.EXTRA_TO_USER_ID, contact.userId)
                next.putExtra(ChatActivity.EXTRA_TITLE, contact.displayName)
                startActivity(next)
            },
            trustStateOf = { userId -> TharMeshApp.get().peerTrustStore.trustState(userId) }
        )
        recycler.adapter = adapter

        findViewById<Button>(R.id.btn_my_qr).setOnClickListener {
            startActivity(Intent(this, MyQrActivity::class.java))
        }
        findViewById<Button>(R.id.btn_scan_qr).setOnClickListener {
            startActivityForResult(Intent(this, ScanQrActivity::class.java), REQUEST_SCAN_QR)
        }
        findViewById<Button>(R.id.btn_add_invite).setOnClickListener { showAddInviteDialog() }

        lifecycleScope.launch {
            repository.observeContacts().collectLatest { list ->
                adapter.submitList(list)
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showAddInviteDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.contacts_add_by_invite)
        AlertDialog.Builder(this)
            .setTitle(R.string.contacts_add_by_invite)
            .setView(input)
            .setPositiveButton(R.string.dialog_start) { _, _ ->
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.isEmpty()) return@setPositiveButton
                addContact(code, code)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun addContact(userId: String, displayName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.addContact(userId, displayName)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCAN_QR && resultCode == Activity.RESULT_OK) {
            val code = data?.getStringExtra(ScanQrActivity.RESULT_CODE).orEmpty().trim()
            if (code.isEmpty()) return
            val displayName = data?.getStringExtra(ScanQrActivity.RESULT_DISPLAY_NAME)
                ?.takeIf { it.isNotBlank() }
                ?: code
            val pubKey = data?.getStringExtra(ScanQrActivity.RESULT_PUB_KEY)?.takeIf { it.isNotBlank() }
            addContact(code, displayName)
            if (pubKey != null) {
                // Stage 6.2 — out-of-band verify the peer's TOFU pin against the
                // QR-encoded public key. If the QR's key differs from a key we
                // already pinned for this userId, the call returns Mismatch and
                // does NOT overwrite the pin.
                lifecycleScope.launch(Dispatchers.IO) {
                    val store = TharMeshApp.get().peerTrustStore
                    val result = store.markVerified(code, pubKey)
                    withContext(Dispatchers.Main) {
                        val msg = when (result) {
                            is PeerTrustStore.VerifyResult.Verified ->
                                getString(R.string.trust_verified_toast, displayName)
                            is PeerTrustStore.VerifyResult.AlreadyVerified ->
                                getString(R.string.trust_already_verified_toast, displayName)
                            is PeerTrustStore.VerifyResult.Mismatch ->
                                getString(
                                    R.string.trust_mismatch_toast,
                                    result.storedFingerprint,
                                    result.scannedFingerprint
                                )
                        }
                        Toast.makeText(this@ContactsActivity, msg, Toast.LENGTH_LONG).show()
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    companion object {
        private const val REQUEST_SCAN_QR = 4001
    }

    private class ContactAdapter(
        private val onClick: (ContactEntity) -> Unit,
        private val trustStateOf: (String) -> PeerTrustStore.TrustState
    ) : RecyclerView.Adapter<ContactAdapter.VH>() {
        private val items: MutableList<ContactEntity> = mutableListOf()

        fun submitList(list: List<ContactEntity>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val contact = items[position]
            holder.name.text = contact.displayName
            holder.userId.text = contact.userId
            holder.avatar.text = contact.displayName.take(1).uppercase()
            holder.itemView.setOnClickListener { onClick(contact) }
            // Stage 6.2 — shield reflects the trust state of the pinned key.
            ShieldRenderer.bind(holder.shield, trustStateOf(contact.userId))
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val avatar: TextView = itemView.findViewById(R.id.text_avatar)
            val name: TextView = itemView.findViewById(R.id.text_name)
            val userId: TextView = itemView.findViewById(R.id.text_userid)
            val shield: ImageView = itemView.findViewById(R.id.icon_trust_shield)
        }
    }
}
