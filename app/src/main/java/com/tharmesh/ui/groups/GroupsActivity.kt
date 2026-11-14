package com.tharmesh.ui.groups

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.ui.theme.Appearance
import tharmesh.app.R

/** Groups are not implemented. This screen says so and does not invent a group list. */
class GroupsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unavailable)
        findViewById<TextView>(R.id.unavailable_title).setText(R.string.groups_unavailable_title)
        findViewById<TextView>(R.id.unavailable_heading).setText(R.string.groups_unavailable_title)
        findViewById<TextView>(R.id.unavailable_body).setText(R.string.groups_unavailable_body)
        findViewById<android.view.View>(R.id.unavailable_back).setOnClickListener { finish() }
    }
}
