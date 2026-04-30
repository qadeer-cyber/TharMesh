package com.tharmesh.ui.contacts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.tharmesh.TharMeshApp
import com.tharmesh.data.MessageRepository
import com.tharmesh.db.entity.ContactEntity
import com.tharmesh.identity.PeerTrustStore
import com.tharmesh.mesh.MeshNode
import com.tharmesh.ui.chat.ChatActivity
import com.tharmesh.ui.devices.DevicePickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tharmesh.app.R

/**
 * PR B — WhatsApp-style Contacts tab. Replaces the Devices bottom-nav slot.
 *
 * Each row renders the saved [ContactEntity] plus three live signals:
 *   - online dot derived from [com.tharmesh.mesh.NearbyDirectory.nodes] (true
 *     iff a directly-reachable [MeshNode] currently advertises the same
 *     userId; multi-hop reachable peers render as offline)
 *   - short fingerprint from the TOFU pin in `peer_identity` (first 4 + last
 *     4 hex chars of [com.tharmesh.identity.CryptoIdentity.fingerprintOf];
 *     hidden when the peer has not been seen on the mesh yet)
 *   - trust shield via [ShieldRenderer] (Verified / TofuOnly / Mismatch)
 *
 * Filtering: a single `combine` over contacts + nearby nodes + verified
 * userIds repaints the list whenever any of the three sources change. The
 * filter chip + search box state lives in this Fragment and is re-applied
 * locally on every emit.
 */
class ContactsFragment : Fragment() {

    enum class Filter { ALL, ONLINE, VERIFIED, UNVERIFIED }

    private lateinit var repository: MessageRepository
    private lateinit var adapter: ContactsAdapter
    private lateinit var empty: View
    private lateinit var search: EditText

    private var lastContacts: List<ContactEntity> = emptyList()
    private var lastNearby: Set<String> = emptySet()
    private var lastVerified: Set<String> = emptySet()
    private var lastTrustStates: Map<String, PeerTrustStore.TrustState> = emptyMap()
    private var lastFingerprints: Map<String, String?> = emptyMap()
    private var query: String = ""
    private var activeFilter: Filter = Filter.ALL

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_contacts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = TharMeshApp.get().repository

        val recycler: RecyclerView = view.findViewById(R.id.recycler_contacts)
        empty = view.findViewById(R.id.empty_contacts)
        search = view.findViewById(R.id.edit_search_contacts)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ContactsAdapter(
            onClick = { c -> openChat(c) },
            onLongPress = { c -> openProfile(c) }
        )
        recycler.adapter = adapter

        view.findViewById<Button>(R.id.btn_my_qr).setOnClickListener {
            startActivity(Intent(requireContext(), MyQrActivity::class.java))
        }
        view.findViewById<Button>(R.id.btn_scan_qr).setOnClickListener {
            launchScanQr()
        }
        view.findViewById<Button>(R.id.btn_add_invite).setOnClickListener {
            promptManualAdd()
        }
        // Stage 8.0 — empty-state CTAs. Reuse the same handlers as the
        // top action bar so the buttons in the empty state and the buttons
        // in the header are visually distinct entry points but logically
        // identical paths.
        view.findViewById<Button>(R.id.empty_btn_scan_qr).setOnClickListener {
            launchScanQr()
        }
        view.findViewById<Button>(R.id.empty_btn_find_nearby).setOnClickListener {
            showFindNearbySheet()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty().trim()
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        view.findViewById<ChipGroup>(R.id.contacts_filter_chips)
            .setOnCheckedChangeListener { _, checkedId ->
                activeFilter = when (checkedId) {
                    R.id.chip_online -> Filter.ONLINE
                    R.id.chip_verified -> Filter.VERIFIED
                    R.id.chip_unverified -> Filter.UNVERIFIED
                    else -> Filter.ALL
                }
                applyFilter()
            }

        // Stage 9.2 — brand-header status dot. Same flow as the alerts/
        // devices fragments — Connected when at least one online peer,
        // Searching when transport is up but the directory is empty,
        // Offline otherwise. Pure perception layer, no behaviour change.
        val brandDot = view.findViewById<com.tharmesh.ui.widget.PulsingDot>(R.id.dot_brand_status)
        if (brandDot != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                TharMeshApp.get().directory.nodes.collectLatest { nodes ->
                    val online = nodes.count { it.online }
                    // Go through SystemStatus.resolveForUi so a perms-revoked-
                    // at-runtime state correctly flips the dot to Offline.
                    brandDot.setStatus(
                        com.tharmesh.ui.system.SystemStatus.resolveForUi(
                            requireContext(),
                            peerCount = online
                        )
                    )
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Combine the two flows on the default dispatcher (no DAO reads),
            // then hop to IO once per emission to pre-compute the trust
            // shield + short-fingerprint snapshots so the adapter can render
            // each row from a Map lookup on the main thread. The Room DAO
            // (PeerIdentityDao.findByUserId) is NOT main-thread-safe — see
            // AppDatabase.kt where allowMainThreadQueries() is intentionally
            // not set — so every trustState / storedKey call must happen
            // here, before the adapter binds.
            combine(
                repository.observeContacts(),
                TharMeshApp.get().directory.nodes
            ) { contacts, nodes ->
                contacts to nodes.filter { it.online }.map { it.userId }.toSet()
            }.collectLatest { (contacts, online) ->
                val snapshot = withContext(Dispatchers.IO) { computeSnapshot(contacts) }
                lastContacts = contacts
                lastNearby = online
                lastVerified = snapshot.verified
                lastTrustStates = snapshot.trustStates
                lastFingerprints = snapshot.fingerprints
                applyFilter()
            }
        }
    }

    private data class Snapshot(
        val verified: Set<String>,
        val trustStates: Map<String, PeerTrustStore.TrustState>,
        val fingerprints: Map<String, String?>
    )

    /**
     * IO-bound snapshot of the trust + fingerprint columns for every contact
     * in the current emission. Called from a [Dispatchers.IO] context so the
     * underlying [com.tharmesh.identity.PeerTrustStore] DAO calls
     * (findByUserId, getVerifiedUserIds) never touch the main thread.
     */
    private fun computeSnapshot(contacts: List<ContactEntity>): Snapshot {
        val app = TharMeshApp.get()
        val store = app.peerTrustStore
        val verified = app.database.peerIdentityDao().getVerifiedUserIds().toSet()
        val trustStates = HashMap<String, PeerTrustStore.TrustState>(contacts.size)
        val fingerprints = HashMap<String, String?>(contacts.size)
        for (c in contacts) {
            trustStates[c.userId] = store.trustState(c.userId)
            val full = store.storedKey(c.userId)?.let { com.tharmesh.identity.CryptoIdentity.fingerprintOf(it) }
            fingerprints[c.userId] = full?.let(Companion::shortFingerprint)
        }
        return Snapshot(verified, trustStates, fingerprints)
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
        // Stage 7 PR E — QR scan from Contacts tab counts as accepting an invite.
        com.tharmesh.data.GrowthMetrics.recordInviteAccepted(requireContext())
        // Stage 8.0 — auto-open the chat after a successful add. Pre-8.0 the
        // user landed back on the Contacts list and had to tap the new row
        // to start chatting; the spec is "QR scan → auto add → auto open
        // chat". The trust verify runs INDEPENDENTLY in the background and
        // toasts its result; landing on the chat does not block on it.
        //
        // Stage 8.3 — Devin Review on PR #43 noted that the previous
        // implementation chained addContact + markVerified + openChatById
        // inside a single withContext(Dispatchers.IO) block, which forced
        // the chat open to wait for the trust-verify DB/crypto round-trip
        // to finish. Split into two independent coroutines so the
        // navigation lands as soon as addContact returns and the verify
        // runs in parallel.
        // Stage 11.1 — QR scan on the Contacts tab goes through the
        // canonical add/merge path with the scanned publicKey so a QR for
        // a device already present (from a prior nearby discovery) merges
        // into the existing row instead of creating a duplicate.
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.addOrMergeContact(code, displayName, publicKeyBase64 = pubKey)
            }
            val ctx = context ?: return@launch
            if (!com.tharmesh.ui.common.AddContactUx.shouldOpenChat(ctx, result)) return@launch
            val canonicalUserId = com.tharmesh.ui.common.AddContactUx
                .canonicalUserIdOrNull(result) ?: return@launch
            val canonicalName = com.tharmesh.ui.common.AddContactUx
                .canonicalDisplayNameOrNull(result) ?: displayName
            openChatById(canonicalUserId, canonicalName)
        }
        if (pubKey != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) {
                    TharMeshApp.get().peerTrustStore.markVerified(code, pubKey)
                }
                surfaceVerifyResult(displayName, res)
            }
        }
    }

    private fun surfaceVerifyResult(displayName: String, res: PeerTrustStore.VerifyResult) {
        val msg = when (res) {
            is PeerTrustStore.VerifyResult.Verified ->
                getString(R.string.trust_verified_toast, displayName)
            is PeerTrustStore.VerifyResult.AlreadyVerified ->
                getString(R.string.trust_already_verified_toast, displayName)
            is PeerTrustStore.VerifyResult.Mismatch ->
                getString(R.string.trust_mismatch_toast, res.storedFingerprint, res.scannedFingerprint)
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    private fun promptManualAdd() {
        val ctx = requireContext()
        val input = EditText(ctx)
        input.hint = getString(R.string.new_chat_recipient_hint)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.contacts_add_by_invite)
            .setView(input)
            .setPositiveButton(R.string.dialog_start) { _, _ ->
                val userId = input.text?.toString()?.trim().orEmpty()
                if (userId.isEmpty()) return@setPositiveButton
                // Stage 8.0 — auto-open chat after manual add for parity
                // with QR + nearby paths. The repository.addContact call is
                // idempotent on userId so re-adds are safe.
                // Stage 11.1 — routed through addOrMergeContact so invalid /
                // truncated entries get rejected with the standard toast.
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        repository.addOrMergeContact(userId, userId)
                    }
                    val ctx = context ?: return@launch
                    if (!com.tharmesh.ui.common.AddContactUx.shouldOpenChat(ctx, result)) {
                        return@launch
                    }
                    val canonicalUserId = com.tharmesh.ui.common.AddContactUx
                        .canonicalUserIdOrNull(result) ?: return@launch
                    val canonicalName = com.tharmesh.ui.common.AddContactUx
                        .canonicalDisplayNameOrNull(result) ?: userId
                    openChatById(canonicalUserId, canonicalName)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun launchScanQr() {
        startActivityForResult(Intent(requireContext(), ScanQrActivity::class.java), REQUEST_SCAN_QR)
    }

    /**
     * Stage 8.0 — open [DevicePickerSheet] from the empty-state "Find
     * nearby contact" CTA. Tapping a node adds it as a contact and opens
     * the chat in one shot — same flow [com.tharmesh.ui.messages.NewChatSheet]
     * uses for its own nearby button.
     */
    private fun showFindNearbySheet() {
        val sheet = DevicePickerSheet()
        sheet.listener = DevicePickerSheet.Listener { node -> addNearbyAndOpenChat(node) }
        sheet.show(parentFragmentManager, DevicePickerSheet.TAG)
    }

    private fun addNearbyAndOpenChat(node: MeshNode) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Stage 11.1 — nearby picker on Contacts tab → canonical path.
            val result = withContext(Dispatchers.IO) {
                repository.addOrMergeContact(node.userId, node.name)
            }
            val ctx = context ?: return@launch
            if (!com.tharmesh.ui.common.AddContactUx.shouldOpenChat(ctx, result)) return@launch
            val canonicalUserId = com.tharmesh.ui.common.AddContactUx
                .canonicalUserIdOrNull(result) ?: return@launch
            val canonicalName = com.tharmesh.ui.common.AddContactUx
                .canonicalDisplayNameOrNull(result) ?: node.name
            openChatById(canonicalUserId, canonicalName)
        }
    }

    private fun openChat(c: ContactEntity) {
        openChatById(c.userId, c.displayName)
    }

    private fun openChatById(userId: String, title: String) {
        val activity = activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val intent = Intent(activity, ChatActivity::class.java)
        intent.putExtra(ChatActivity.EXTRA_TO_USER_ID, userId)
        intent.putExtra(ChatActivity.EXTRA_TITLE, title)
        startActivity(intent)
    }

    private fun openProfile(c: ContactEntity) {
        val intent = Intent(requireContext(), ContactProfileActivity::class.java)
        intent.putExtra(ContactProfileActivity.EXTRA_USER_ID, c.userId)
        startActivity(intent)
    }

    private fun applyFilter() {
        val filtered = filterContacts(lastContacts, query, activeFilter, lastNearby, lastVerified)
        adapter.submit(
            items = filtered,
            online = lastNearby,
            trustStates = lastTrustStates,
            fingerprints = lastFingerprints
        )
        empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        private const val REQUEST_SCAN_QR = 7012

        /**
         * Pure filter applied on every emit. Extracted from [applyFilter] so
         * it can be unit-tested without inflating the fragment.
         *
         * `query` matches case-insensitively against `displayName` OR `userId`.
         * `filter` narrows the result by mesh-online state ([online]) or by
         * QR-verified state ([verified]). Both [online] and [verified] are
         * sets of contact userIds, populated once per emit by the caller.
         */
        @JvmStatic
        fun filterContacts(
            contacts: List<ContactEntity>,
            query: String,
            filter: Filter,
            online: Set<String>,
            verified: Set<String>
        ): List<ContactEntity> {
            val q = query.trim().lowercase()
            return contacts.filter { c ->
                val matchesQ = q.isEmpty() ||
                    c.displayName.lowercase().contains(q) ||
                    c.userId.lowercase().contains(q)
                val matchesFilter = when (filter) {
                    Filter.ALL -> true
                    Filter.ONLINE -> c.userId in online
                    Filter.VERIFIED -> c.userId in verified
                    Filter.UNVERIFIED -> c.userId !in verified
                }
                matchesQ && matchesFilter
            }
        }

        /** First 4 + last 4 hex chars of a SHA-256 fingerprint, e.g. `1A2B…3C4D`. */
        @JvmStatic
        fun shortFingerprint(full: String): String {
            val hex = full.replace(":", "")
            if (hex.length < 8) return full
            return hex.take(4) + "…" + hex.takeLast(4)
        }
    }

    private class ContactsAdapter(
        private val onClick: (ContactEntity) -> Unit,
        private val onLongPress: (ContactEntity) -> Unit
    ) : RecyclerView.Adapter<ContactsAdapter.VH>() {

        private val items: MutableList<ContactEntity> = mutableListOf()
        private var online: Set<String> = emptySet()
        private var trustStates: Map<String, PeerTrustStore.TrustState> = emptyMap()
        private var fingerprints: Map<String, String?> = emptyMap()

        fun submit(
            items: List<ContactEntity>,
            online: Set<String>,
            trustStates: Map<String, PeerTrustStore.TrustState>,
            fingerprints: Map<String, String?>
        ) {
            this.items.clear()
            this.items.addAll(items)
            this.online = online
            this.trustStates = trustStates
            this.fingerprints = fingerprints
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
            holder.dot.visibility = if (c.userId in online) View.VISIBLE else View.GONE

            val fp = fingerprints[c.userId]
            if (fp != null) {
                holder.fingerprint.visibility = View.VISIBLE
                holder.fingerprint.text = fp
            } else {
                holder.fingerprint.visibility = View.GONE
            }

            ShieldRenderer.bind(holder.shield, trustStates[c.userId] ?: PeerTrustStore.TrustState.Unknown)
            holder.itemView.setOnClickListener { onClick(c) }
            holder.itemView.setOnLongClickListener { onLongPress(c); true }
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val avatar: TextView = itemView.findViewById(R.id.text_avatar)
            val name: TextView = itemView.findViewById(R.id.text_name)
            val userId: TextView = itemView.findViewById(R.id.text_userid)
            val fingerprint: TextView = itemView.findViewById(R.id.text_fingerprint)
            val shield: ImageView = itemView.findViewById(R.id.icon_trust_shield)
            val dot: View = itemView.findViewById(R.id.dot_online)
        }
    }
}
