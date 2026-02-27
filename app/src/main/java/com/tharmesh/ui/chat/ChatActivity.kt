package com.tharmesh.ui.chat

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tharmesh.R
import com.tharmesh.crypto.CryptoBox
import com.tharmesh.db.AppDatabase
import com.tharmesh.db.entity.BundleEntity
import com.tharmesh.db.entity.ConversationEntity
import com.tharmesh.db.entity.MessageEntity
import com.tharmesh.identity.IdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TO_USER_ID = "toUserId"
    }

    private lateinit var statusTextView: TextView
    private lateinit var messageInput: EditText
    private lateinit var chatTitle: TextView
    private lateinit var adapter: MessageAdapter

    private val messages: MutableList<MessageUi> = mutableListOf()
    private var toUserId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        chatTitle = findViewById(R.id.text_chat_title)
        statusTextView = findViewById(R.id.text_top_status)
        messageInput = findViewById(R.id.edit_message)
        val sendButton = findViewById<Button>(R.id.button_send)
        val attachButton = findViewById<ImageButton>(R.id.button_attach)
        val recyclerView = findViewById<RecyclerView>(R.id.recycler_messages)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = MessageAdapter(messages)
        recyclerView.adapter = adapter

        sendButton.setOnClickListener { onSendClicked() }
        attachButton.setOnClickListener {
            Toast.makeText(this, "Attachments coming soon", Toast.LENGTH_SHORT).show()
        }

        statusTextView.text = getString(R.string.queued_local)
        val providedToUserId = intent.getStringExtra(EXTRA_TO_USER_ID).orEmpty().trim()
        if (providedToUserId.isNotEmpty()) {
            toUserId = providedToUserId
            chatTitle.text = toUserId
            loadMessagesFromRoomOrFallback()
        } else {
            askRecipient()
        }
    }

    private fun askRecipient() {
        val input = EditText(this)
        input.hint = "Recipient userId / number"
        input.inputType = InputType.TYPE_CLASS_TEXT

        AlertDialog.Builder(this)
            .setTitle("Start Chat")
            .setCancelable(false)
            .setView(input)
            .setPositiveButton("Open") { _, _ ->
                toUserId = input.text?.toString()?.trim().orEmpty()
                if (toUserId.isEmpty()) toUserId = "unknown"
                chatTitle.text = toUserId
                loadMessagesFromRoomOrFallback()
            }
            .show()
    }

    private fun loadMessagesFromRoomOrFallback() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val me = IdentityStore(applicationContext).ensureIdentity()
                val list = db.messageDao().getMessagesForConvo(toUserId)
                val mapped = list.map { entity ->
                    val body = decryptForUi(db, me, entity)
                    val bundleStatus = db.bundleDao().getByBundleId(entity.bundleId)?.status ?: "QUEUED"
                    MessageUi(
                        id = entity.id,
                        body = body,
                        isMine = entity.fromUserId == me.userId,
                        status = mapTick(bundleStatus),
                        timestamp = entity.ts
                    )
                }
                withContext(Dispatchers.Main) {
                    messages.clear()
                    messages.addAll(mapped)
                    adapter.notifyDataSetChanged()
                }
            } catch (ignored: Throwable) {
                withContext(Dispatchers.Main) {
                    messages.clear()
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun decryptForUi(db: AppDatabase, me: com.tharmesh.identity.LocalIdentity, entity: MessageEntity): String {
        return try {
            val senderPub = if (entity.fromUserId == me.userId) {
                me.publicKeyBase64
            } else {
                db.contactDao().getByUserId(entity.fromUserId)?.publicKey.orEmpty()
            }
            if (senderPub.isBlank()) {
                "[Encrypted message]"
            } else {
                val metadata = entity.bundleId + ":" + entity.fromUserId + ":" + entity.toUserId
                CryptoBox.openFromSender(entity.ciphertext, me.privateKeyBase64, senderPub, metadata)
                    ?: "[Encrypted message]"
            }
        } catch (ignored: Throwable) {
            "[Encrypted message]"
        }
    }

    private fun onSendClicked() {
        val text = messageInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        val ts = System.currentTimeMillis()
        val uiMessage = MessageUi(
            id = ts,
            body = text,
            isMine = true,
            status = "Queued ⏳",
            timestamp = ts
        )
        messages.add(uiMessage)
        adapter.notifyItemInserted(messages.size - 1)
        messageInput.setText("")
        statusTextView.text = getString(R.string.queued_local)

        lifecycleScope.launch(Dispatchers.IO) {
            saveMessageBundleAndConversation(text, ts)
        }
    }

    private fun saveMessageBundleAndConversation(plainText: String, ts: Long) {
        try {
            val db = AppDatabase.getInstance(applicationContext)
            val me = IdentityStore(applicationContext).ensureIdentity()
            val contact = db.contactDao().getByUserId(toUserId)
            val receiverPub = if (contact?.publicKey.isNullOrBlank()) me.publicKeyBase64 else contact?.publicKey.orEmpty()

            val bundleId = UUID.randomUUID().toString()
            val metadata = bundleId + ":" + me.userId + ":" + toUserId
            val payloadCiphertext = CryptoBox.sealForReceiver(
                plainText = plainText,
                receiverPublicKeyBase64 = receiverPub,
                senderPrivateKeyBase64 = me.privateKeyBase64,
                metadata = metadata
            )

            db.messageDao().insert(
                MessageEntity(
                    convoId = toUserId,
                    fromUserId = me.userId,
                    toUserId = toUserId,
                    ciphertext = payloadCiphertext,
                    ts = ts,
                    status = "QUEUED",
                    bundleId = bundleId
                )
            )

            db.bundleDao().insert(
                BundleEntity(
                    bundleId = bundleId,
                    toUserId = toUserId,
                    fromUserId = me.userId,
                    payloadCiphertext = payloadCiphertext,
                    createdAt = ts,
                    expiresAt = ts + (24L * 60L * 60L * 1000L),
                    hopCount = 0,
                    maxHops = 8,
                    status = "QUEUED",
                    attemptCount = 0,
                    nextRetryAt = ts,
                    lastAttemptAt = 0L
                )
            )

            val existing = db.conversationDao().getByConvoId(toUserId)
            db.conversationDao().upsert(
                ConversationEntity(
                    convoId = toUserId,
                    title = if (existing?.title.isNullOrBlank()) toUserId else existing?.title.orEmpty(),
                    lastMessage = "Encrypted message",
                    lastTs = ts,
                    unreadCount = existing?.unreadCount ?: 0
                )
            )
        } catch (ignored: Throwable) {
            // UI remains responsive even if DB is unavailable.
        }
    }

    private fun mapTick(status: String): String {
        return when (status) {
            "QUEUED" -> "Queued ⏳"
            "RELAYED" -> "Relayed 🕸"
            "DELIVERED" -> "Delivered ✅"
            "READ" -> "Read ✅✅"
            else -> status
        }
    }
}

data class MessageUi(
    val id: Long,
    val body: String,
    val isMine: Boolean,
    val status: String,
    val timestamp: Long
)

private class MessageAdapter(
    private val items: List<MessageUi>
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    override fun getItemViewType(position: Int): Int {
        return if (items[position].isMine) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutId = if (viewType == 1) R.layout.item_msg_me else R.layout.item_msg_peer
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val item = items[position]
        holder.messageText.text = item.body
        holder.statusText.text = item.status
    }

    override fun getItemCount(): Int = items.size

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.text_body)
        val statusText: TextView = itemView.findViewById(R.id.text_status)
    }
}
