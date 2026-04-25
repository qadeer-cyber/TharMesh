package com.tharmesh.ui.chat

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tharmesh.app.R
import com.tharmesh.TharMeshApp
import com.tharmesh.data.MessageRepository
import com.tharmesh.data.UserPrefs
import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.MessageEntity
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
    private lateinit var replyBar: View
    private lateinit var replyBarAuthor: TextView
    private lateinit var replyBarPreview: TextView
    private lateinit var replyBarClose: ImageButton

    private lateinit var repository: MessageRepository
    private var myUserId: String = ""
    private var toUserId: String = ""
    private var replyingTo: MessageEntity? = null

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
        recycler = findViewById(R.id.recycler_messages)
        input = findViewById(R.id.edit_message)
        sendButton = findViewById(R.id.button_send)
        replyBar = findViewById(R.id.reply_bar)
        replyBarAuthor = findViewById(R.id.reply_bar_author)
        replyBarPreview = findViewById(R.id.reply_bar_preview)
        replyBarClose = findViewById(R.id.reply_bar_close)

        adapter = MessageAdapter(myUserId) { msg -> startReply(msg) }
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
        start()
    }

    override fun onResume() {
        super.onResume()
        if (toUserId.isNotEmpty()) {
            lifecycleScope.launch { repository.markChatRead(toUserId) }
        }
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
                updateTopStatus(msgs)
            }
        }
    }

    private fun updateTopStatus(msgs: List<MessageEntity>) {
        val lastMine = msgs.lastOrNull { it.fromUserId == myUserId }
        topStatus.text = when (lastMine?.status) {
            // SENDING renders the same as QUEUED intentionally — the UI contract is
            // "no tick until bytes are on the wire". SENDING is an internal correctness
            // state (transport accepted, PayloadSent pending) that users don't need to
            // see separately. Keeping both branches here preserves the exhaustive when.
            MessageStatus.QUEUED,
            MessageStatus.SENDING -> getString(R.string.chat_header_offline) + " · ⏳"
            MessageStatus.SENT -> "✓ sent"
            MessageStatus.DELIVERED -> "✓✓ delivered"
            MessageStatus.READ -> "✓✓ read"
            MessageStatus.FAILED -> "! failed"
            else -> getString(R.string.chat_header_offline)
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
        private val onReply: (MessageEntity) -> Unit
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
                val color = if (msg.status == MessageStatus.READ) R.color.tm_tick_read else R.color.tm_tick_default
                holder.status.setTextColor(ContextCompat.getColor(ctx, color))
            }

            holder.itemView.setOnLongClickListener {
                onReply(msg)
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
