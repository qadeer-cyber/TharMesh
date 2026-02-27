package com.tharmesh.ui.chat

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tharmesh.data.UserPrefs
import com.tharmesh.db.AppDatabase
import com.tharmesh.db.entity.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TO_USER_ID = "toUserId"
    }

    private lateinit var statusTextView: TextView
    private lateinit var messageInput: EditText
    private lateinit var adapter: MessageAdapter

    private val messages: MutableList<MessageUi> = mutableListOf()
    private var toUserId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        statusTextView = TextView(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        statusTextView.setPadding(pad, pad, pad, pad)
        statusTextView.text = "⏳ queued locally"
        root.addView(
            statusTextView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val recyclerView = RecyclerView(this)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = MessageAdapter(messages)
        recyclerView.adapter = adapter
        val listParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0
        )
        listParams.weight = 1f
        root.addView(recyclerView, listParams)

        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity = Gravity.CENTER_VERTICAL
        inputRow.setPadding(pad, pad, pad, pad)

        messageInput = EditText(this)
        messageInput.hint = "Type a message"
        messageInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val inputParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        inputParams.weight = 1f
        inputRow.addView(messageInput, inputParams)

        val sendButton = Button(this)
        sendButton.text = "Send"
        sendButton.setOnClickListener {
            onSendClicked()
        }
        inputRow.addView(sendButton)

        root.addView(
            inputRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)

        val providedToUserId = intent.getStringExtra(EXTRA_TO_USER_ID).orEmpty().trim()
        if (providedToUserId.isNotEmpty()) {
            toUserId = providedToUserId
            title = toUserId
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
                if (toUserId.isEmpty()) {
                    toUserId = "unknown"
                }
                title = toUserId
                loadMessagesFromRoomOrFallback()
            }
            .show()
    }

    private fun loadMessagesFromRoomOrFallback() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val list = AppDatabase.getInstance(applicationContext).messageDao().getMessagesForUser(toUserId)
                val mapped: List<MessageUi> = list.map { entity: MessageEntity ->
                    MessageUi(
                        id = entity.id,
                        body = entity.body,
                        isMine = entity.fromUserId != toUserId,
                        status = entity.status,
                        timestamp = entity.timestamp
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

    private fun onSendClicked() {
        val text = messageInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            return
        }

        val uiMessage = MessageUi(
            id = System.currentTimeMillis(),
            body = text,
            isMine = true,
            status = "Queued ⏳",
            timestamp = System.currentTimeMillis()
        )
        messages.add(uiMessage)
        adapter.notifyItemInserted(messages.size - 1)
        messageInput.setText("")

        statusTextView.text = "⏳ queued locally"

        lifecycleScope.launch(Dispatchers.IO) {
            saveMessageToRoomIfAvailable(uiMessage)
            // TODO: Hook relay state updates here (🕸 relayed)
            // TODO: Hook ACK handling here (✅ delivered)
        }
    }

    private fun saveMessageToRoomIfAvailable(messageUi: MessageUi) {
        try {
            val me = UserPrefs.ensureProfile(applicationContext)
            val entity = MessageEntity(
                fromUserId = me.userId,
                toUserId = toUserId,
                body = messageUi.body,
                status = messageUi.status,
                timestamp = messageUi.timestamp
            )
            AppDatabase.getInstance(applicationContext).messageDao().insert(entity)
        } catch (ignored: Throwable) {
            // Keep UI safe if Room setup is unavailable.
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val container = LinearLayout(parent.context)
        container.orientation = LinearLayout.VERTICAL
        val pad = (8 * parent.resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad, pad, pad)

        val messageText = TextView(parent.context)
        messageText.textSize = 16f
        container.addView(messageText)

        val statusText = TextView(parent.context)
        statusText.textSize = 12f
        container.addView(statusText)

        return MessageViewHolder(container, messageText, statusText)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val item = items[position]
        holder.messageText.text = item.body
        holder.statusText.text = item.status

        val params = holder.itemView.layoutParams as? RecyclerView.LayoutParams
            ?: RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        if (item.isMine) {
            params.marginStart = (32 * holder.itemView.resources.displayMetrics.density).toInt()
            params.marginEnd = 0
        } else {
            params.marginStart = 0
            params.marginEnd = (32 * holder.itemView.resources.displayMetrics.density).toInt()
        }
        holder.itemView.layoutParams = params
    }

    override fun getItemCount(): Int {
        return items.size
    }

    class MessageViewHolder(
        itemView: View,
        val messageText: TextView,
        val statusText: TextView
    ) : RecyclerView.ViewHolder(itemView)
}
