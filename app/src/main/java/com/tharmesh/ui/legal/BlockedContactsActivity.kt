// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.legal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tharmesh.TharMeshApp
import com.tharmesh.data.BlockedContacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tharmesh.app.R

/**
 * Stage 8.4 \u2014 Pakistan compliance: management screen for blocked
 * contacts. Reachable from Settings \u2192 Privacy \u2192 Blocked contacts.
 *
 * Renders the live [BlockedContacts.snapshot] as a list with one row
 * per blocked userId; each row shows the human display-name (resolved
 * from the local [com.tharmesh.db.entity.ContactEntity] when present,
 * else the raw userId) and an Unblock button. Tapping Unblock removes
 * the userId from the block list and refreshes the rendered list in
 * place. The empty-state appears when no contact is blocked.
 *
 * Unblocking does NOT auto-restore a prior contact row \u2014 the user can
 * still re-add the contact via QR / nearby / invite as normal.
 */
class BlockedContactsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked_contacts)
        listContainer = findViewById(R.id.blocked_list)
        emptyView = findViewById(R.id.blocked_empty)
        findViewById<ImageView>(R.id.blocked_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val ids = BlockedContacts.snapshot(this@BlockedContactsActivity).toList()
                if (ids.isEmpty()) return@withContext emptyList<Row>()
                val dao = TharMeshApp.get().database.contactDao()
                ids.map { id ->
                    val contact = dao.getByUserId(id)
                    Row(userId = id, displayName = contact?.displayName)
                }.sortedBy { (it.displayName ?: it.userId).lowercase() }
            }
            renderRows(rows)
        }
    }

    private fun renderRows(rows: List<Row>) {
        listContainer.removeAllViews()
        if (rows.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            findViewById<View>(R.id.blocked_scroll).visibility = View.GONE
            return
        }
        emptyView.visibility = View.GONE
        findViewById<View>(R.id.blocked_scroll).visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        for (row in rows) {
            val view = inflater.inflate(R.layout.item_blocked_contact_row, listContainer, false)
            view.findViewById<TextView>(R.id.blocked_row_name).text =
                row.displayName ?: row.userId
            view.findViewById<TextView>(R.id.blocked_row_userid).text = row.userId
            view.findViewById<Button>(R.id.blocked_row_unblock).setOnClickListener {
                BlockedContacts.unblock(this, row.userId)
                refresh()
            }
            listContainer.addView(view)
        }
    }

    private data class Row(val userId: String, val displayName: String?)
}
