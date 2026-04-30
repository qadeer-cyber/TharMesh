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
            sheet.listener = DevicePickerSheet.Listener { node ->
                // Do NOT dismiss the parent sheet here. The picker
                // listener fires after the user picks a device, often
                // seconds after this click. If we dismissed [this]
                // sheet up front, [viewLifecycleOwner] would be
                // destroyed by the time the listener fires, and the
                // coroutine launched in [openChatWithNewContact] would
                // run on a cancelled scope (or worse, hit a
                // [requireContext] / [requireActivity] after detach
                // and crash with IllegalStateException).
                //
                // Self-dismiss happens at the end of [openChat] once
                // [ChatActivity] has been launched.
                openChatWithNewContact(node)
            }
            sheet.show(parentFragmentManager, DevicePickerSheet.TAG)
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
        // Stage 7 PR E — QR-scan-and-added is the canonical
        // "invite accepted" event (vs. addContact alone, which also
        // covers manual userId entry and nearby-tap paths).
        com.tharmesh.data.GrowthMetrics.recordInviteAccepted(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            // Stage 11.1 — QR scan funnels through addOrMergeContact with
            // the scanned publicKey, enabling fingerprint-based merge with
            // any pre-existing nearby-discovered contact for this device.
            val result = withContext(Dispatchers.IO) {
                repository.addOrMergeContact(code, displayName, publicKeyBase64 = pubKey)
            }
            if (pubKey != null) {
                // Trust-verify runs independently — merge may have moved the
                // canonical userId, so pin against the user the repository
                // resolved (falls back to the input code for Invalid/Blocked
                // paths, which won't reach markVerified anyway).
                val verifyUserId = com.tharmesh.ui.common.AddContactUx
                    .canonicalUserIdOrNull(result) ?: code
                withContext(Dispatchers.IO) {
                    TharMeshApp.get().peerTrustStore.markVerified(verifyUserId, pubKey)
                }
            }
            val ctx = context ?: return@launch
            if (!com.tharmesh.ui.common.AddContactUx.shouldOpenChat(ctx, result)) return@launch
            val canonicalUserId = com.tharmesh.ui.common.AddContactUx
                .canonicalUserIdOrNull(result) ?: return@launch
            val canonicalName = com.tharmesh.ui.common.AddContactUx
                .canonicalDisplayNameOrNull(result) ?: displayName
            openChat(canonicalUserId, canonicalName)
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
                    // Stage 11.1 — manual invite entry also goes through
                    // the canonical path so `user-` or typos get rejected
                    // with a toast instead of silently creating a bogus row.
                    val result = withContext(Dispatchers.IO) {
                        repository.addOrMergeContact(userId, userId)
                    }
                    val dialogCtx = context ?: return@launch
                    if (!com.tharmesh.ui.common.AddContactUx.shouldOpenChat(dialogCtx, result)) {
                        return@launch
                    }
                    val canonicalUserId = com.tharmesh.ui.common.AddContactUx
                        .canonicalUserIdOrNull(result) ?: return@launch
                    val canonicalName = com.tharmesh.ui.common.AddContactUx
                        .canonicalDisplayNameOrNull(result) ?: userId
                    openChat(canonicalUserId, canonicalName)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun openChatWithNewContact(node: MeshNode) {
        // Capture activity/context BEFORE the suspending Dispatchers.IO
        // hop. If the user dismisses the sheet (or kills the device
        // picker) while addContact() is in flight, the fragment may
        // detach by the time we get back to the main thread; using a
        // captured Activity reference (whose lifecycle outlives a
        // single sheet) keeps us crash-free.
        val activity = activity ?: return
        val app = TharMeshApp.get()
        app.appScope.launch {
            // Stage 11.1 — nearby picker → addOrMergeContact. Invalid
            // advertised IDs (`user-`, blank) rejected with a toast.
            val result = withContext(Dispatchers.IO) {
                app.repository.addOrMergeContact(node.userId, node.name)
            }
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                if (!com.tharmesh.ui.common.AddContactUx.shouldOpenChat(activity, result)) {
                    if (isAdded) dismissAllowingStateLoss()
                    return@withContext
                }
                val canonicalUserId = com.tharmesh.ui.common.AddContactUx
                    .canonicalUserIdOrNull(result) ?: return@withContext
                val canonicalName = com.tharmesh.ui.common.AddContactUx
                    .canonicalDisplayNameOrNull(result) ?: node.name
                val intent = Intent(activity, ChatActivity::class.java).apply {
                    putExtra(ChatActivity.EXTRA_TO_USER_ID, canonicalUserId)
                    putExtra(ChatActivity.EXTRA_TITLE, canonicalName)
                }
                activity.startActivity(intent)
                if (isAdded) dismissAllowingStateLoss()
            }
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
