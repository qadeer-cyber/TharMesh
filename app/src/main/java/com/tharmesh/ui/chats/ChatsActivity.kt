package com.tharmesh.ui.chats

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import tharmesh.app.R
import com.tharmesh.TharMeshApp
import com.tharmesh.data.MessageRepository
import com.tharmesh.data.UserPrefs
import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.ConversationEntity
import com.tharmesh.ui.auth.LoginActivity
import com.tharmesh.ui.chat.ChatActivity
import com.tharmesh.ui.contacts.ContactsActivity
import com.tharmesh.ui.groups.GroupsActivity
import com.tharmesh.ui.status.StatusActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/** WhatsApp-style chat list, backed by [MessageRepository] (Room Flow). */
class ChatsActivity : AppCompatActivity() {

    private lateinit var adapter: ChatsAdapter
    private lateinit var empty: View
    private lateinit var repository: MessageRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!UserPrefs.hasProfile(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_chats)
        TharMeshApp.get().ensureMeshStarted()
        repository = TharMeshApp.get().repository

        val recycler: RecyclerView = findViewById(R.id.recycler_chats)
        val fab: FloatingActionButton = findViewById(R.id.fab_new_chat)
        val bottomNav: BottomNavigationView = findViewById(R.id.bottom_nav)
        empty = findViewById(R.id.text_empty)

        adapter = ChatsAdapter { conv ->
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra(ChatActivity.EXTRA_TO_USER_ID, conv.userId)
            intent.putExtra(ChatActivity.EXTRA_TITLE, conv.title)
            startActivity(intent)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        fab.setOnClickListener { promptNewChat() }

        // Legacy nav items removed — new MainActivity owns the bottom-nav now.
        // ChatsActivity is kept purely for compatibility; the new MessagesFragment
        // duplicates this screen's contents inside the tab host.

        lifecycleScope.launch {
            repository.observeConversations().collectLatest { list ->
                adapter.submitList(list)
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun promptNewChat() {
        val input = EditText(this)
        input.hint = getString(R.string.new_chat_recipient_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.new_chat)
            .setView(input)
            .setPositiveButton(R.string.dialog_start) { _, _ ->
                val toUserId = input.text?.toString()?.trim().orEmpty()
                if (toUserId.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.ensureConversation(toUserId) }
                    val next = Intent(this@ChatsActivity, ChatActivity::class.java)
                    next.putExtra(ChatActivity.EXTRA_TO_USER_ID, toUserId)
                    startActivity(next)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private class ChatsAdapter(
        private val onClick: (ConversationEntity) -> Unit
    ) : RecyclerView.Adapter<ChatsAdapter.VH>() {

        private val items: MutableList<ConversationEntity> = mutableListOf()

        fun submitList(list: List<ConversationEntity>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val conv = items[position]
            holder.title.text = conv.title
            holder.avatar.text = conv.title.take(1).uppercase()
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
