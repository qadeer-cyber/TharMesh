package com.tharmesh.ui.messages

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tharmesh.TharMeshApp
import com.tharmesh.data.MessageRepository
import com.tharmesh.data.UserPrefs
import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.ConversationEntity
import com.tharmesh.mesh.MeshNode
import com.tharmesh.permissions.PermissionMonitor
import com.tharmesh.permissions.PermissionStatus
import com.tharmesh.ui.chat.ChatActivity
import com.tharmesh.ui.devices.DevicePickerSheet
import com.tharmesh.ui.diagnostics.DiagnosticsActivity
import com.tharmesh.ui.profile.ProfileActivity
import com.tharmesh.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tharmesh.app.R
import java.io.File
import java.util.Date

/**
 * Stage 6.1 — Chats home (was Messages tab).
 *
 * Layout: WhatsApp-style top icon row + rounded search pill + filter chips
 * (All / Unread / Nearby / Trusted / +) + flat conversation list + FAB stack.
 *
 * Filtering is driven by the live [ConversationEntity] flow combined with
 * snapshots of [com.tharmesh.mesh.NearbyDirectory.nodes] (Nearby chip) and
 * [com.tharmesh.db.dao.PeerIdentityDao.getVerifiedUserIds] (Trusted chip;
 * Stage 6.2 refined this from any TOFU-bound peer to peers explicitly
 * verified=true via QR scan). No synthetic rows — if a filter has no matching
 * real data the empty state renders.
 */
class MessagesFragment : Fragment() {

    private lateinit var adapter: ChatsAdapter
    private lateinit var empty: View
    private lateinit var repository: MessageRepository

    private var lastConversations: List<ConversationEntity> = emptyList()
    private var searchQuery: String = ""
    private var activeFilter: Filter = Filter.ALL

    private enum class Filter { ALL, UNREAD, NEARBY, TRUSTED }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_messages, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = TharMeshApp.get().repository

        bindTopBar(view)
        bindSearch(view)
        bindFilterChips(view)
        bindList(view)
        bindFabs(view)

        viewLifecycleOwner.lifecycleScope.launch {
            repository.observeConversations().collectLatest { list ->
                lastConversations = list
                applyFilter()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            TharMeshApp.get().directory.nodes.collectLatest { refreshMeshWarningDot() }
        }
    }

    override fun onResume() {
        super.onResume()
        view?.let {
            renderProfileAvatar(it)
            refreshMeshWarningDot()
        }
    }

    /**
     * Top icon row: mesh status · theme toggle · profile avatar · overflow.
     * Mesh icon carries a small warning dot when BT/Location/perms are not
     * ready and opens an enable-Bluetooth dialog on tap (replaces the old
     * full-width banner). Camera / Alerts icons live elsewhere now.
     */
    private fun bindTopBar(view: View) {
        val ctx = requireContext()
        renderProfileAvatar(view)

        view.findViewById<FrameLayout>(R.id.btn_top_mesh).setOnClickListener {
            showMeshStatusDialog()
        }

        view.findViewById<ImageView>(R.id.btn_top_theme).setOnClickListener { showThemePicker() }

        view.findViewById<ImageView>(R.id.btn_top_overflow).setOnClickListener { anchor ->
            val popup = PopupMenu(ctx, anchor)
            popup.menu.add(R.string.settings_title).setOnMenuItemClickListener {
                // Switch the bottom-nav to the Settings tab; ProfileActivity
                // remains the primary surface for personal account info, but
                // the overflow gives a one-tap path here too.
                val nav = requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
                nav?.selectedItemId = R.id.nav_settings
                true
            }
            popup.menu.add(R.string.diagnostics_title).setOnMenuItemClickListener {
                startActivity(Intent(ctx, DiagnosticsActivity::class.java)); true
            }
            popup.show()
        }

        view.findViewById<FrameLayout>(R.id.btn_top_profile).setOnClickListener {
            startActivity(Intent(ctx, ProfileActivity::class.java))
        }
    }

    /**
     * Render the top-right avatar from the saved profile photo (if any),
     * falling back to the first letter of the username on a tinted circle.
     */
    private fun renderProfileAvatar(view: View) {
        val ctx = requireContext()
        val profile = UserPrefs.readProfile(ctx)
        val initial = view.findViewById<TextView>(R.id.top_profile_avatar)
        val image = view.findViewById<ImageView>(R.id.top_profile_avatar_image)
        initial.text = profile?.username?.take(1)?.uppercase() ?: "T"

        val photoPath = UserPrefs.getAvatarLocalPath(ctx)
        val photoFile = photoPath?.let { File(it) }
        val bitmap = photoFile?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (bitmap != null) {
            image.setImageBitmap(bitmap)
            image.clipToOutline = true
            image.background = ctx.getDrawable(R.drawable.bg_avatar)
            image.visibility = View.VISIBLE
            initial.visibility = View.GONE
        } else {
            image.visibility = View.GONE
            initial.visibility = View.VISIBLE
        }
    }

    /**
     * Refresh the warning dot on the mesh status icon based on the live
     * permission/runtime state. Replaces the old full-width banner; same
     * signal, less chrome.
     */
    private fun refreshMeshWarningDot() {
        val view = view ?: return
        val dot = view.findViewById<View>(R.id.top_mesh_warning_dot)
        val ready = PermissionMonitor.snapshot(requireContext()) is PermissionStatus.Ready
        dot.visibility = if (ready) View.GONE else View.VISIBLE
    }

    /**
     * Tap-handler for the mesh icon: surfaces the next-blocking runtime
     * issue (BT / Location / permissions) with a one-tap action that opens
     * the relevant system Settings panel. When the mesh is already up the
     * dialog reports a friendly status instead of suppressing the tap.
     */
    private fun showMeshStatusDialog() {
        val ctx = requireContext()
        when (PermissionMonitor.snapshot(ctx)) {
            is PermissionStatus.BluetoothOff -> AlertDialog.Builder(ctx)
                .setTitle(R.string.mesh_warning_title)
                .setMessage(R.string.mesh_warning_bluetooth)
                .setPositiveButton(R.string.mesh_warning_open_settings) { _, _ ->
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    if (intent.resolveActivity(ctx.packageManager) != null) {
                        startActivity(intent)
                    } else {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            is PermissionStatus.LocationOff -> AlertDialog.Builder(ctx)
                .setTitle(R.string.mesh_warning_title)
                .setMessage(R.string.mesh_warning_location)
                .setPositiveButton(R.string.mesh_warning_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            is PermissionStatus.PermissionDenied -> AlertDialog.Builder(ctx)
                .setTitle(R.string.mesh_warning_title)
                .setMessage(R.string.mesh_warning_permissions)
                .setPositiveButton(R.string.mesh_warning_open_settings) { _, _ ->
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = android.net.Uri.fromParts("package", ctx.packageName, null)
                    startActivity(intent)
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            is PermissionStatus.Ready -> {
                val nodes = TharMeshApp.get().directory.nodes.value
                val online = nodes.count { it.online }
                val msg = if (online > 0) {
                    getString(R.string.mesh_warning_connected_fmt, online)
                } else {
                    getString(R.string.mesh_warning_searching)
                }
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.dash_network_title)
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        // Permission state may change inside the system Settings panel —
        // onResume refreshes the warning dot when we come back.
    }

    private fun bindSearch(view: View) {
        val search = view.findViewById<EditText>(R.id.chats_search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                applyFilter()
            }
        })
    }

    private fun bindFilterChips(view: View) {
        val chips = listOf(
            view.findViewById<Button>(R.id.chip_filter_all) to Filter.ALL,
            view.findViewById<Button>(R.id.chip_filter_unread) to Filter.UNREAD,
            view.findViewById<Button>(R.id.chip_filter_nearby) to Filter.NEARBY,
            view.findViewById<Button>(R.id.chip_filter_trusted) to Filter.TRUSTED
        )
        for ((btn, filter) in chips) {
            btn.setOnClickListener {
                activeFilter = filter
                chips.forEach { (b, f) -> b.isActivated = (f == filter) }
                applyFilter()
            }
        }
        // Initial state.
        chips.forEach { (b, f) -> b.isActivated = (f == activeFilter) }

        view.findViewById<FrameLayout>(R.id.chip_filter_plus).setOnClickListener {
            // Stage 6.1 — `+` chip is a discoverable affordance; future
            // PRs (6.2 Trusted Contacts, 6.4 Channels) add real options here.
            showDevicePicker()
        }
    }

    private fun bindList(view: View) {
        val recycler: RecyclerView = view.findViewById(R.id.recycler_chats)
        empty = view.findViewById(R.id.empty_chats)
        adapter = ChatsAdapter { conv ->
            openChat(conv.userId, conv.title, avatarBgForPeer(conv.userId))
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<Button>(R.id.button_empty_start_chat).setOnClickListener { showDevicePicker() }
    }

    private fun bindFabs(view: View) {
        view.findViewById<FloatingActionButton>(R.id.fab_new_chat).setOnClickListener { showDevicePicker() }
        view.findViewById<FloatingActionButton>(R.id.fab_new_chat_small).setOnClickListener { showDevicePicker() }
    }

    /**
     * Render the persisted Theme Mode picker. Persistence and apply happen in
     * [ThemeManager.setAndApply]; AppCompat recreates the running Activity on
     * its own without us needing to call recreate().
     */
    private fun showThemePicker() {
        val ctx = requireContext()
        val labels = arrayOf(
            getString(R.string.settings_theme_system),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark)
        )
        val current = UserPrefs.getThemeMode(ctx).ordinal
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_theme_dialog_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val mode = ThemeManager.Mode.values()[which]
                ThemeManager.setAndApply(ctx, mode)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun applyFilter() {
        if (!::adapter.isInitialized) return
        val q = searchQuery.lowercase()
        val nearbyIds: Set<String> = TharMeshApp.get().directory.nodes.value.map { it.userId }.toSet()

        viewLifecycleOwner.lifecycleScope.launch {
            val trustedIds: Set<String> = if (activeFilter == Filter.TRUSTED) {
                withContext(Dispatchers.IO) {
                    TharMeshApp.get().database.peerIdentityDao().getVerifiedUserIds().toSet()
                }
            } else {
                emptySet()
            }

            val filtered = lastConversations.filter { conv ->
                if (q.isNotEmpty() &&
                    !conv.title.lowercase().contains(q) &&
                    !conv.lastMessage.lowercase().contains(q)
                ) return@filter false
                when (activeFilter) {
                    Filter.ALL -> true
                    Filter.UNREAD -> conv.unreadCount > 0
                    Filter.NEARBY -> conv.userId in nearbyIds
                    Filter.TRUSTED -> conv.userId in trustedIds
                }
            }

            adapter.submitList(filtered)
            empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showDevicePicker() {
        val sheet = DevicePickerSheet()
        sheet.listener = DevicePickerSheet.Listener { node -> openChatWithNode(node) }
        sheet.show(parentFragmentManager, DevicePickerSheet.TAG)
    }

    private fun openChatWithNode(node: MeshNode) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.ensureConversation(node.userId, node.name) }
            openChat(node.userId, node.name, node.avatarBg)
        }
    }

    private fun openChat(userId: String, title: String, avatarBg: Int) {
        val intent = Intent(requireContext(), ChatActivity::class.java)
        intent.putExtra(ChatActivity.EXTRA_TO_USER_ID, userId)
        intent.putExtra(ChatActivity.EXTRA_TITLE, title)
        intent.putExtra(ChatActivity.EXTRA_AVATAR_BG, avatarBg)
        startActivity(intent)
    }

    private fun avatarBgForPeer(userId: String): Int {
        val dir = TharMeshApp.get().directory
        return dir.nodes.value.firstOrNull { it.userId == userId }?.avatarBg ?: R.drawable.bg_avatar
    }

    private class ChatsAdapter(
        private val onClick: (ConversationEntity) -> Unit
    ) : RecyclerView.Adapter<ChatsAdapter.VH>() {

        private val items: MutableList<ConversationEntity> = mutableListOf()
        private val avatars = intArrayOf(
            R.drawable.bg_avatar,
            R.drawable.bg_avatar_green,
            R.drawable.bg_avatar_amber
        )

        fun submitList(list: List<ConversationEntity>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val conv = items[position]
            holder.title.text = conv.title
            holder.avatar.text = conv.title.take(1).uppercase()
            holder.avatar.setBackgroundResource(avatars[position.coerceAtLeast(0) % avatars.size])
            holder.preview.text = conv.lastMessage.ifEmpty { "…" }
            holder.time.text = DateFormat.format("HH:mm", Date(conv.lastTimestamp))

            val statusGlyph = when (conv.lastMessageStatus) {
                MessageStatus.QUEUED,
                MessageStatus.SENDING -> "⏳ "
                MessageStatus.SENT -> "✓ "
                MessageStatus.DELIVERED -> "✓✓ "
                MessageStatus.READ -> "✓✓ "
                else -> ""
            }
            if (statusGlyph.isNotEmpty()) {
                holder.preview.text = statusGlyph + conv.lastMessage
            }

            if (conv.unreadCount > 0) {
                holder.unread.visibility = View.VISIBLE
                holder.unread.text = conv.unreadCount.toString()
            } else {
                holder.unread.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onClick(conv) }
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val avatar: TextView = itemView.findViewById(R.id.text_avatar)
            val title: TextView = itemView.findViewById(R.id.text_title)
            val preview: TextView = itemView.findViewById(R.id.text_preview)
            val time: TextView = itemView.findViewById(R.id.text_time)
            val unread: TextView = itemView.findViewById(R.id.text_unread)
        }
    }
}
