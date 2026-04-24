package com.tharmesh.ui.messages

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tharmesh.TharMeshApp
import com.tharmesh.data.MessageRepository
import com.tharmesh.db.MessageStatus
import com.tharmesh.db.entity.ConversationEntity
import com.tharmesh.ui.chat.ChatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tharmesh.app.R
import java.util.Date

/** Messages tab: chat list backed by MessageRepository Flow. */
class MessagesFragment : Fragment() {

    private lateinit var adapter: ChatsAdapter
    private lateinit var empty: View
    private lateinit var repository: MessageRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_messages, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = TharMeshApp.get().repository

        val recycler: RecyclerView = view.findViewById(R.id.recycler_chats)
        val fab: FloatingActionButton = view.findViewById(R.id.fab_new_chat)
        empty = view.findViewById(R.id.empty_chats)

        adapter = ChatsAdapter { conv ->
            val intent = Intent(requireContext(), ChatActivity::class.java)
            intent.putExtra(ChatActivity.EXTRA_TO_USER_ID, conv.userId)
            intent.putExtra(ChatActivity.EXTRA_TITLE, conv.title)
            startActivity(intent)
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        fab.setOnClickListener { promptNewChat() }

        viewLifecycleOwner.lifecycleScope.launch {
            repository.observeConversations().collectLatest { list ->
                adapter.submitList(list)
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun promptNewChat() {
        val input = EditText(requireContext())
        input.hint = getString(R.string.new_chat_recipient_hint)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.new_chat)
            .setView(input)
            .setPositiveButton(R.string.dialog_start) { _, _ ->
                val toUserId = input.text?.toString()?.trim().orEmpty()
                if (toUserId.isEmpty()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.ensureConversation(toUserId) }
                    val next = Intent(requireContext(), ChatActivity::class.java)
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
                MessageStatus.QUEUED -> "⏳ "
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
