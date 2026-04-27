package com.tharmesh.ui.chat

import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tharmesh.app.R
import com.tharmesh.TharMeshApp
import com.tharmesh.data.MessageRepository
import com.tharmesh.data.UserPrefs
import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.MessageEntity
import com.tharmesh.permissions.PermissionMonitor
import com.tharmesh.permissions.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * WhatsApp-style one-to-one chat. Reads messages from [MessageRepository] as a Flow so
 * status transitions (QUEUED → SENT → DELIVERED → READ) repaint automatically.
 *
 * The previous implementation built its UI programmatically, inherited no background color,
 * and rendered as a blank/black screen on MaterialComponents DayNight themes. This version
 * uses [R.layout.activity_chat] which has an explicit background and header.
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TO_USER_ID = "toUserId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_AVATAR_BG = "avatarBg"
    }

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: MessageAdapter
    private lateinit var input: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var titleView: TextView
    private lateinit var topStatus: TextView
    private lateinit var chatAvatar: TextView
    private lateinit var trustShield: ImageView
    private lateinit var replyBar: View
    private lateinit var replyBarAuthor: TextView
    private lateinit var replyBarPreview: TextView
    private lateinit var replyBarClose: ImageButton

    private lateinit var repository: MessageRepository
    private var myUserId: String = ""
    private var toUserId: String = ""
    private var replyingTo: MessageEntity? = null

    /**
     * Stage 8.0 — last-known message status used by [updateTopStatus] when
     * a mesh-state emission lands without a new message-list emission. The
     * formatter combines this with the live mesh state to produce the
     * subtitle, so we cache the last computed value here.
     */
    private var lastOutgoingStatus: String? = null
    private lateinit var statusStrings: ChatStatusFormatter.Strings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val app = TharMeshApp.get()
        app.ensureMeshStarted()
        repository = app.repository
        myUserId = UserPrefs.ensureProfile(this).userId

        val provided = intent.getStringExtra(EXTRA_TO_USER_ID)?.trim().orEmpty()
        val providedTitle = intent.getStringExtra(EXTRA_TITLE)?.trim().orEmpty()
        val providedAvatarBg = intent.getIntExtra(EXTRA_AVATAR_BG, 0)

        titleView = findViewById(R.id.text_chat_title)
        topStatus = findViewById(R.id.text_top_status)
        chatAvatar = findViewById(R.id.text_chat_avatar)
        trustShield = findViewById(R.id.icon_chat_shield)
        recycler = findViewById(R.id.recycler_messages)
        input = findViewById(R.id.edit_message)
        sendButton = findViewById(R.id.button_send)
        replyBar = findViewById(R.id.reply_bar)
        replyBarAuthor = findViewById(R.id.reply_bar_author)
        replyBarPreview = findViewById(R.id.reply_bar_preview)
        replyBarClose = findViewById(R.id.reply_bar_close)

        adapter = MessageAdapter(
            myUserId = myUserId,
            onLongPress = { msg ->
                // Stage 5.3 — failed messages get a "Retry" affordance via the
                // long-press menu. Non-failed messages keep the existing
                // reply-on-long-press behaviour.
                if (msg.fromUserId == myUserId &&
                    msg.status == com.tharmesh.db.MessageStatus.FAILED
                ) {
                    confirmRetryFailed(msg)
                } else {
                    startReply(msg)
                }
            }
        )
        val lm = LinearLayoutManager(this)
        lm.stackFromEnd = true
        recycler.layoutManager = lm
        recycler.adapter = adapter

        sendButton.setOnClickListener { onSendClicked() }
        replyBarClose.setOnClickListener { cancelReply() }

        if (provided.isEmpty()) {
            // A ChatActivity should always be launched from a picker / conversation row
            // that provides a userId. If we reach here with no target, bail cleanly
            // rather than showing a dead screen.
            finish()
            return
        }
        toUserId = provided
        val title = providedTitle.ifBlank { toUserId }
        titleView.text = title
        chatAvatar.text = title.take(1).uppercase()
        if (providedAvatarBg != 0) {
            chatAvatar.setBackgroundResource(providedAvatarBg)
        }

        // Stage 8.0 — bundle of strings the pure formatter needs. Built
        // once so the per-emission update path stays Android-resource free.
        statusStrings = buildStatusStrings()

        start()

        // Stage 8.0 — auto-focus the message input + show the keyboard so
        // the user can start typing the second the chat opens. This matches
        // WhatsApp / Telegram behaviour and removes a redundant tap. The
        // explicit focus + showSoftInput pair works on API 19+ which is
        // well below the project's minSdk (and obviously below AGP 4.1.3's
        // compileSdk 30 ceiling). Posting via the input view's own handler
        // ensures the call runs after the activity window has been
        // attached, which is required for showSoftInput to actually pop
        // the IME on most OEM Android builds.
        input.requestFocus()
        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * Stage 8.0 — read the chat-header subtitle strings out of resources
     * once and hand them to [ChatStatusFormatter]. Centralised here so the
     * formatter call site stays terse and the strings travel together.
     */
    private fun buildStatusStrings(): ChatStatusFormatter.Strings {
        return ChatStatusFormatter.Strings(
            online1Fmt = getString(R.string.chat_status_online_one),
            onlineNFmt = getString(R.string.chat_status_online_n_fmt),
            searching = getString(R.string.chat_status_searching),
            offline = getString(R.string.chat_status_offline),
            msgQueued = getString(R.string.chat_status_msg_queued),
            msgSending = getString(R.string.chat_status_msg_sending),
            msgSent = getString(R.string.chat_status_msg_sent),
            msgDelivered = getString(R.string.chat_status_msg_delivered),
            msgRead = getString(R.string.chat_status_msg_read),
            msgFailed = getString(R.string.chat_status_msg_failed)
        )
    }

    override fun onResume() {
        super.onResume()
        if (toUserId.isNotEmpty()) {
            lifecycleScope.launch { repository.markChatRead(toUserId) }
            // Stage 6.2 — refresh the shield in case the trust state was just
            // updated (e.g. user came back from ContactsActivity after a QR
            // verification).
            refreshTrustShield()
            // Stage 8.0 — refresh the header subtitle in case Bluetooth or
            // Location were toggled in Settings while we were paused. The
            // directory.nodes flow already covers connect / disconnect, but
            // permission-state transitions don't push through that flow.
            if (::statusStrings.isInitialized) renderTopStatus()
        }
    }

    private fun refreshTrustShield() {
        if (toUserId.isEmpty()) return
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                TharMeshApp.get().peerTrustStore.trustState(toUserId)
            }
            com.tharmesh.ui.contacts.ShieldRenderer.bind(trustShield, state)
            // Always keep the shield visible in the chat header, even for an
            // Unknown peer (no row yet) — render it as the muted outline so
            // users learn there's a trust story per chat.
            if (trustShield.visibility == View.GONE) {
                trustShield.visibility = View.VISIBLE
                trustShield.setImageResource(R.drawable.ic_shield_outline)
                trustShield.contentDescription = getString(R.string.cd_shield_tofu)
                androidx.core.widget.ImageViewCompat.setImageTintList(
                    trustShield,
                    android.content.res.ColorStateList.valueOf(
                        resolveAttrColor(android.R.attr.textColorSecondary)
                    )
                )
            }
        }
    }

    private fun resolveAttrColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun start() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.ensureConversation(toUserId) }
            repository.markChatRead(toUserId)
        }
        lifecycleScope.launch {
            repository.observeConversation(toUserId).collectLatest { msgs ->
                adapter.submitList(msgs)
                if (msgs.isNotEmpty()) recycler.scrollToPosition(msgs.size - 1)
                lastOutgoingStatus = msgs.lastOrNull { it.fromUserId == myUserId }?.status
                renderTopStatus()
            }
        }
        // Stage 8.0 — repaint the header subtitle whenever the mesh's
        // online-peer set changes. The directory's StateFlow re-emits on
        // every connect / disconnect / advert tick, so this is the right
        // primitive to track "are we online right now". onResume already
        // covers the permission-state transition (Bluetooth / Location
        // toggled in Settings) by calling renderTopStatus directly.
        lifecycleScope.launch {
            TharMeshApp.get().directory.nodes.collectLatest { renderTopStatus() }
        }
    }

    /**
     * Stage 8.0 — derive the subtitle from the live mesh state + cached
     * last-outgoing message status, then hand off to the pure formatter.
     * The mesh-state derivation is the same shape as the chat-list
     * mesh-warning-dot (PermissionMonitor.snapshot + directory.nodes), so
     * the two surfaces never disagree.
     */
    private fun renderTopStatus() {
        val mesh = currentMeshState()
        topStatus.text = ChatStatusFormatter.format(mesh, lastOutgoingStatus, statusStrings)
    }

    private fun currentMeshState(): ChatStatusFormatter.MeshState {
        val perm = PermissionMonitor.snapshot(this)
        if (perm !is PermissionStatus.Ready) return ChatStatusFormatter.MeshState.Offline
        val online = TharMeshApp.get().directory.nodes.value.count { it.online }
        return if (online > 0) {
            ChatStatusFormatter.MeshState.Online(online)
        } else {
            ChatStatusFormatter.MeshState.Searching
        }
    }

    private fun onSendClicked() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val replyId = replyingTo?.id
        input.setText("")
        cancelReply()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repository.send(toUserId, text, replyId)
            }
        }
    }

    /**
     * Stage 5.3 D3 — Failure feedback. Show a confirmation dialog for the
     * failed [msg] and, on confirm, re-issue via [MessageRepository.retryFailedMessage].
     * Concurrent recovery: if the row is no longer FAILED by the time the
     * coroutine runs, surface a brief Toast rather than silently doing
     * nothing — matches the "transparent failure" principle.
     */
    private fun confirmRetryFailed(msg: MessageEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_retry_failed_title)
            .setMessage(R.string.chat_retry_failed_body)
            .setPositiveButton(R.string.chat_retry_failed_action) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        repository.retryFailedMessage(msg.id)
                    }
                    if (!ok) {
                        Toast.makeText(
                            this@ChatActivity,
                            R.string.chat_retry_failed_unavailable,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.chat_retry_failed_cancel, null)
            .show()
    }

    private fun startReply(msg: MessageEntity) {
        replyingTo = msg
        replyBarAuthor.text = if (msg.fromUserId == myUserId) "You" else msg.fromUserId
        replyBarPreview.text = msg.body
        replyBar.visibility = View.VISIBLE
    }

    private fun cancelReply() {
        replyingTo = null
        replyBar.visibility = View.GONE
    }

    private class MessageAdapter(
        private val myUserId: String,
        private val onLongPress: (MessageEntity) -> Unit
    ) : RecyclerView.Adapter<MessageAdapter.VH>() {

        private val items: MutableList<MessageEntity> = mutableListOf()
        private val timeFormat = "HH:mm"

        fun submitList(msgs: List<MessageEntity>) {
            items.clear()
            items.addAll(msgs)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position].fromUserId == myUserId) TYPE_ME else TYPE_PEER

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = if (viewType == TYPE_ME) R.layout.item_msg_me else R.layout.item_msg_peer
            val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val msg = items[position]
            val ctx = holder.itemView.context
            holder.body.text = msg.body
            holder.time.text = DateFormat.format(timeFormat, Date(msg.timestamp))

            if (msg.replyToPreview != null) {
                holder.replyContainer?.visibility = View.VISIBLE
                holder.replyPreview?.text = msg.replyToPreview
            } else {
                holder.replyContainer?.visibility = View.GONE
            }

            if (holder.status != null) {
                holder.status.text = statusGlyph(msg.status)
                // Resolve tick colour via theme attrs so the light-mode green
                // bubble gets a contrast-correct slate (#8696A0) and the
                // dark-mode bubble keeps its neon cyan/blue. Was hardcoded
                // R.color.tm_tick_* (static, ignores DayNight); on the light
                // green bubble the static neon-blue read tick was too saturated
                // and the default tick-blue washed out against the green fill.
                val attr = if (msg.status == MessageStatus.READ) {
                    R.attr.tmTickRead
                } else {
                    R.attr.tmTickDefault
                }
                val tv = android.util.TypedValue()
                ctx.theme.resolveAttribute(attr, tv, true)
                holder.status.setTextColor(tv.data)
            }

            holder.itemView.setOnLongClickListener {
                onLongPress(msg)
                true
            }
        }

        private fun statusGlyph(status: String): String = when (status) {
            MessageStatus.QUEUED,
            MessageStatus.SENDING -> "⏳"
            MessageStatus.SENT -> "✓"
            MessageStatus.DELIVERED -> "✓✓"
            MessageStatus.READ -> "✓✓"
            MessageStatus.FAILED -> "!"
            else -> ""
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val body: TextView = itemView.findViewById(R.id.text_body)
            val time: TextView = itemView.findViewById(R.id.text_time)
            val status: TextView? = itemView.findViewById(R.id.text_status)
            val replyContainer: LinearLayout? = itemView.findViewById(R.id.reply_container)
            val replyPreview: TextView? = itemView.findViewById(R.id.reply_preview)
        }

        companion object {
            private const val TYPE_ME = 1
            private const val TYPE_PEER = 2
        }
    }
}
