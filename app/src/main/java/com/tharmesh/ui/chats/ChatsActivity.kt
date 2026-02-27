package com.tharmesh.ui.chats

import android.content.Intent
import android.os.Bundle
import android.text.InputType
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
import com.tharmesh.R
import com.tharmesh.db.AppDatabase
import com.tharmesh.db.entity.ConversationEntity
import com.tharmesh.ui.chat.ChatActivity
import com.tharmesh.ui.contacts.ContactsActivity
import com.tharmesh.ui.groups.GroupsActivity
import com.tharmesh.ui.status.StatusActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatsActivity : AppCompatActivity() {

    private val conversations: MutableList<ConversationUi> = mutableListOf()
    private lateinit var adapter: ConversationAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chats)

        val recyclerView = findViewById<RecyclerView>(R.id.recycler_chats)
        val fab = findViewById<FloatingActionButton>(R.id.fab_new_chat)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        emptyView = findViewById(R.id.text_empty)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ConversationAdapter(conversations) { conversation: ConversationUi ->
            openChat(conversation.toUserId)
        }
        recyclerView.adapter = adapter

        fab.setOnClickListener { showNewChatDialog() }

        bottomNav.selectedItemId = R.id.nav_chats
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> true
                R.id.nav_status -> {
                    startActivity(Intent(this, StatusActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_groups -> {
                    startActivity(Intent(this, GroupsActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_contacts -> {
                    startActivity(Intent(this, ContactsActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadConversationsFromDbOrFallback()
    }

    private fun showNewChatDialog() {
        val input = EditText(this)
        input.hint = "Recipient userId / number"
        input.inputType = InputType.TYPE_CLASS_TEXT

        AlertDialog.Builder(this)
            .setTitle("New Chat")
            .setView(input)
            .setPositiveButton("Open") { _, _ ->
                val toUserId = input.text?.toString()?.trim().orEmpty()
                if (toUserId.isNotEmpty()) {
                    openChat(toUserId)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openChat(toUserId: String) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra(ChatActivity.EXTRA_TO_USER_ID, toUserId)
        startActivity(intent)
    }

    private fun loadConversationsFromDbOrFallback() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rows: List<ConversationEntity> = AppDatabase.getInstance(applicationContext).conversationDao().getAll()
                val mapped = rows.map { row ->
                    ConversationUi(
                        toUserId = row.convoId,
                        title = row.title,
                        lastMessage = row.lastMessage,
                        lastTimestamp = row.lastTs,
                        unreadCount = row.unreadCount
                    )
                }
                withContext(Dispatchers.Main) {
                    conversations.clear()
                    if (mapped.isEmpty()) {
                        conversations.add(
                            ConversationUi(
                                toUserId = "88001122",
                                title = "88001122",
                                lastMessage = "Start chatting offline",
                                lastTimestamp = System.currentTimeMillis(),
                                unreadCount = 0
                            )
                        )
                    } else {
                        conversations.addAll(mapped)
                    }
                    adapter.notifyDataSetChanged()
                    emptyView.visibility = if (conversations.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (ignored: Throwable) {
                withContext(Dispatchers.Main) {
                    conversations.clear()
                    conversations.add(
                        ConversationUi(
                            toUserId = "88001122",
                            title = "88001122",
                            lastMessage = "Start chatting offline",
                            lastTimestamp = System.currentTimeMillis(),
                            unreadCount = 0
                        )
                    )
                    adapter.notifyDataSetChanged()
                    emptyView.visibility = if (conversations.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }
}

data class ConversationUi(
    val toUserId: String,
    val title: String,
    val lastMessage: String,
    val lastTimestamp: Long,
    val unreadCount: Int
)

private class ConversationAdapter(
    private val items: List<ConversationUi>,
    private val onClick: (ConversationUi) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder>() {

    private val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return ConversationViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        val item = items[position]
        holder.titleView.text = item.title
        holder.previewView.text = item.lastMessage
        holder.timeView.text = formatter.format(Date(item.lastTimestamp))
        holder.avatarView.text = item.title.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        if (item.unreadCount > 0) {
            holder.unreadView.visibility = View.VISIBLE
            holder.unreadView.text = item.unreadCount.toString()
        } else {
            holder.unreadView.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class ConversationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatarView: TextView = itemView.findViewById(R.id.text_avatar)
        val titleView: TextView = itemView.findViewById(R.id.text_title)
        val previewView: TextView = itemView.findViewById(R.id.text_preview)
        val timeView: TextView = itemView.findViewById(R.id.text_time)
        val unreadView: TextView = itemView.findViewById(R.id.text_unread)
    }
}
