package com.tharmesh.ui.contacts

import android.app.Activity
import android.content.Intent
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
import com.tharmesh.db.AppDatabase
import com.tharmesh.db.entity.ContactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactsActivity : AppCompatActivity() {

    private val contacts: MutableList<String> = mutableListOf()
    private lateinit var adapter: ContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = (12 * resources.displayMetrics.density).toInt()

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        actions.setPadding(pad, pad, pad, pad)

        val myQrButton = Button(this)
        myQrButton.text = "My QR"
        myQrButton.setOnClickListener {
            startActivity(Intent(this, MyQrActivity::class.java))
        }
        actions.addView(myQrButton)

        val scanQrButton = Button(this)
        scanQrButton.text = "Scan QR"
        scanQrButton.setOnClickListener {
            startActivityForResult(Intent(this, ScanQrActivity::class.java), REQUEST_SCAN_QR)
        }
        actions.addView(scanQrButton)

        val inviteButton = Button(this)
        inviteButton.text = "Add by Invite Code"
        inviteButton.setOnClickListener {
            showAddInviteDialog()
        }
        actions.addView(inviteButton)

        root.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val recyclerView = RecyclerView(this)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ContactAdapter(contacts)
        recyclerView.adapter = adapter
        root.addView(
            recyclerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        title = "Contacts"

        loadContactsFromDbOrFallback()
    }

    private fun loadContactsFromDbOrFallback() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbItems = AppDatabase.getInstance(applicationContext).contactDao().getAll()
                withContext(Dispatchers.Main) {
                    contacts.clear()
                    if (dbItems.isEmpty()) {
                        contacts.add("88001122")
                        contacts.add("99002233")
                    } else {
                        contacts.addAll(dbItems.map { it.userId + if (it.displayName.isNotBlank()) " (${it.displayName})" else "" })
                    }
                    adapter.notifyDataSetChanged()
                }
            } catch (ignored: Throwable) {
                withContext(Dispatchers.Main) {
                    contacts.clear()
                    contacts.add("88001122")
                    contacts.add("99002233")
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun showAddInviteDialog() {
        val input = EditText(this)
        input.hint = "Enter invite code"
        input.inputType = InputType.TYPE_CLASS_TEXT

        AlertDialog.Builder(this)
            .setTitle("Add Contact")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.isNotEmpty()) {
                    addContact(code)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addContact(value: String, name: String = "", publicKey: String = "") {
        contacts.add(0, value + if (name.isNotBlank()) " ($name)" else "")
        adapter.notifyItemInserted(0)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                AppDatabase.getInstance(applicationContext).contactDao().upsert(
                    ContactEntity(
                        userId = value,
                        displayName = name,
                        publicKey = publicKey,
                        addedAt = now,
                        lastSeen = now
                    )
                )
            } catch (ignored: Throwable) {
                // Fallback stays in-memory only.
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCAN_QR && resultCode == Activity.RESULT_OK) {
            val userId = data?.getStringExtra(ScanQrActivity.RESULT_USER_ID).orEmpty().trim()
            val publicKey = data?.getStringExtra(ScanQrActivity.RESULT_PUBLIC_KEY).orEmpty().trim()
            val name = data?.getStringExtra(ScanQrActivity.RESULT_NAME).orEmpty().trim()
            if (userId.isNotEmpty()) {
                addContact(userId, name, publicKey)
            }
        }
    }

    companion object {
        private const val REQUEST_SCAN_QR = 4001
    }
}

private class ContactAdapter(
    private val items: List<String>
) : RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val textView = TextView(parent.context)
        val pad = (12 * parent.resources.displayMetrics.density).toInt()
        textView.setPadding(pad, pad, pad, pad)
        return ContactViewHolder(textView)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.textView.text = items[position]
    }

    override fun getItemCount(): Int {
        return items.size
    }

    class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView as TextView
    }
}
