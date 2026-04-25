package com.tharmesh.ui.messages

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tharmesh.TharMeshApp
import com.tharmesh.data.MessageRepository
import com.tharmesh.db.entity.ContactEntity
import com.tharmesh.mesh.MeshNode
import com.tharmesh.ui.chat.ChatActivity
import com.tharmesh.ui.contacts.ScanQrActivity
import com.tharmesh.ui.devices.DevicePickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tharmesh.app.R

/**
 * "New chat" bottom sheet — saved contacts at the top, then three explicit
 * paths to add a new contact (QR, nearby, manual). Saved contacts open
 * [ChatActivity] without ever going through the device picker; the picker
 * is only reachable as the "Find nearby" first-contact path, and any node
 * picked there is permanently added to [ContactEntity] before the chat
 * opens so it appears under "Saved contacts" on the next invocation.
 */
class NewChatSheet : BottomSheetDialogFragment() {

    private lateinit var repository: MessageRepository
    private lateinit var adapter: ContactsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_new_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = TharMeshApp.get().repository

        val recycler: RecyclerView = view.findViewById(R.id.recycler_new_chat_contacts)
        val emptyContacts: TextView = view.findViewById(R.id.new_chat_no_contacts)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ContactsAdapter(onClick = { contact -> openChat(contact.userId, contact.displayName) })
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repository.observeContacts().collectLatest { list ->
                adapter.submitList(list)
                val empty = list.isEmpty()
                recycler.visibility = if (empty) View.GONE else View.VISIBLE
                emptyContacts.visibility = if (empty) View.VISIBLE else View.GONE
            }
        }

        view.findViewById<View>(R.id.new_chat_action_scan).setOnClickListener {
            startActivityForResult(Intent(requireContext(), ScanQrActivity::class.java), REQUEST_SCAN_QR)
        }
        view.findViewById<View>(R.id.new_chat_action_nearby).setOnClickListener {
            val sheet = DevicePickerSheet()
            sheet.listener = DevicePickerSheet.Listener { node -> openChatWithNewContact(node) }
            sheet.show(parentFragmentManager, DevicePickerSheet.TAG)
            // Dismiss self so only one sheet is on screen at a time.
            dismissAllowingStateLoss()
        }
        view.findViewById<View>(R.id.new_chat_action_userid).setOnClickListener {
            promptUserIdAdd()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCAN_QR || resultCode != Activity.RESULT_OK) return
        val code = data?.getStringExtra(ScanQrActivity.RESULT_CODE).orEmpty().trim()
        if (code.isEmpty()) return
        val displayName = data?.getStringExtra(ScanQrActivity.RESULT_DISPLAY_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: code
        val pubKey = data?.getStringExtra(ScanQrActivity.RESULT_PUB_KEY)?.takeIf { it.isNotBlank() }
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repository.addContact(code, displayName)
                if (pubKey != null) {
                    // Verify-by-QR is additive over TOFU: a mismatch leaves the
                    // pinned key untouched. Surfacing the result in this sheet
                    // would duplicate the existing ContactsActivity toast path,
                    // so we just trigger the trust-store call here.
                    TharMeshApp.get().peerTrustStore.markVerified(code, pubKey)
                }
            }
            openChat(code, displayName)
        }
    }

    private fun promptUserIdAdd() {
        val ctx = requireContext()
        val input = EditText(ctx)
        input.hint = getString(R.string.new_chat_recipient_hint)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.new_chat_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_start) { _, _ ->
                val userId = input.text?.toString()?.trim().orEmpty()
                if (userId.isEmpty()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.addContact(userId) }
                    openChat(userId, userId)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun openChatWithNewContact(node: MeshNode) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.addContact(node.userId, node.name) }
            openChat(node.userId, node.name)
        }
    }

    private fun openChat(userId: String, title: String) {
        val intent = Intent(requireContext(), ChatActivity::class.java)
        intent.putExtra(ChatActivity.EXTRA_TO_USER_ID, userId)
        intent.putExtra(ChatActivity.EXTRA_TITLE, title)
        startActivity(intent)
        dismissAllowingStateLoss()
    }

    private class ContactsAdapter(
        private val onClick: (ContactEntity) -> Unit
    ) : RecyclerView.Adapter<ContactsAdapter.VH>() {

        private val items: MutableList<ContactEntity> = mutableListOf()

        fun submitList(list: List<ContactEntity>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = items[position]
            holder.name.text = c.displayName
            holder.userId.text = c.userId
            holder.avatar.text = c.displayName.take(1).uppercase()
            holder.itemView.setOnClickListener { onClick(c) }
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val avatar: TextView = itemView.findViewById(R.id.text_avatar)
            val name: TextView = itemView.findViewById(R.id.text_name)
            val userId: TextView = itemView.findViewById(R.id.text_userid)
        }
    }

    companion object {
        const val TAG: String = "NewChatSheet"
        private const val REQUEST_SCAN_QR = 7011
    }
}
