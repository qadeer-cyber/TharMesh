package com.tharmesh.ui.chats

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tharmesh.db.AppDatabase
import com.tharmesh.db.entity.ConversationEntity
import com.tharmesh.ui.chat.ChatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatsActivity : AppCompatActivity() {

    private val conversations: MutableList<ConversationUi> = mutableListOf()
    private lateinit var adapter: ConversationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        val recyclerView = RecyclerView(this)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ConversationAdapter(conversations) { conversation: ConversationUi ->
            openChat(conversation.toUserId)
        }
        recyclerView.adapter = adapter
        root.addView(
            recyclerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val fab = FloatingActionButton(this)
        fab.setImageResource(android.R.drawable.ic_input_add)
        fab.contentDescription = "New Chat"
        fab.setOnClickListener {
            showNewChatDialog()
        }
        val fabParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        fabParams.gravity = Gravity.BOTTOM or Gravity.END
        val margin = (16 * resources.displayMetrics.density).toInt()
        fabParams.setMargins(margin, margin, margin, margin)
        root.addView(fab, fabParams)

        setContentView(root)
        title = "Chats"
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
        val container = LinearLayout(parent.context)
        container.orientation = LinearLayout.VERTICAL
        val pad = (12 * parent.resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad, pad, pad)

        val titleView = TextView(parent.context)
        titleView.textSize = 16f
        container.addView(titleView)

        val previewView = TextView(parent.context)
        previewView.textSize = 14f
        container.addView(previewView)

        val metaView = TextView(parent.context)
        metaView.textSize = 12f
        container.addView(metaView)

        return ConversationViewHolder(container, titleView, previewView, metaView)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        val item = items[position]
        holder.titleView.text = item.title
        holder.previewView.text = item.lastMessage
        val timeText = formatter.format(Date(item.lastTimestamp))
        val unreadText = if (item.unreadCount > 0) " • ${item.unreadCount} unread" else ""
        holder.metaView.text = "$timeText$unreadText"
        holder.itemView.setOnClickListener {
            onClick(item)
        }
    }

    override fun getItemCount(): Int {
        return items.size
    }

    class ConversationViewHolder(
        itemView: View,
        val titleView: TextView,
        val previewView: TextView,
        val metaView: TextView
    ) : RecyclerView.ViewHolder(itemView)
}
