package com.tharmesh.ui.status

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tharmesh.R
import com.tharmesh.ui.chats.ChatsActivity
import com.tharmesh.ui.contacts.ContactsActivity
import com.tharmesh.ui.groups.GroupsActivity

class StatusActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        val pad = (12 * resources.displayMetrics.density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val comingSoon = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            text = "Status - Coming soon"
        }
        content.addView(comingSoon)

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@StatusActivity)
            adapter = PlaceholderAdapter(listOf("No status updates yet"))
        }
        content.addView(
            recyclerView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val bottomNav = BottomNavigationView(this).apply {
            inflateMenu(R.menu.bottom_nav)
            selectedItemId = R.id.nav_status
            setOnItemSelectedListener { item ->
                if (item.itemId == selectedItemId) return@setOnItemSelectedListener true
                when (item.itemId) {
                    R.id.nav_chats -> {
                        startActivity(Intent(this@StatusActivity, ChatsActivity::class.java))
                        finish()
                        true
                    }
                    R.id.nav_groups -> {
                        startActivity(Intent(this@StatusActivity, GroupsActivity::class.java))
                        finish()
                        true
                    }
                    R.id.nav_contacts -> {
                        startActivity(Intent(this@StatusActivity, ContactsActivity::class.java))
                        finish()
                        true
                    }
                    else -> false
                }
            }
        }
        content.addView(bottomNav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(
            content,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val fab = FloatingActionButton(this)
        fab.setImageResource(android.R.drawable.ic_input_add)
        fab.contentDescription = "Add Status"
        val fabParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        fabParams.gravity = Gravity.BOTTOM or Gravity.END
        fabParams.setMargins(pad, pad, pad, pad * 5)
        root.addView(fab, fabParams)

        setContentView(root)
        title = "Status"
    }
}

private class PlaceholderAdapter(
    private val items: List<String>
) : RecyclerView.Adapter<PlaceholderAdapter.PlaceholderViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceholderViewHolder {
        val text = TextView(parent.context)
        val pad = (12 * parent.resources.displayMetrics.density).toInt()
        text.setPadding(pad, pad, pad, pad)
        return PlaceholderViewHolder(text)
    }

    override fun onBindViewHolder(holder: PlaceholderViewHolder, position: Int) {
        holder.textView.text = items[position]
    }

    override fun getItemCount(): Int = items.size

    class PlaceholderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView as TextView
    }
}
