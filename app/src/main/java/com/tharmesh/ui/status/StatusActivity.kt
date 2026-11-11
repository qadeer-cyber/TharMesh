package com.tharmesh.ui.status

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.ui.theme.Appearance
import tharmesh.app.R

/**
 * Legacy status activity. Stories are not implemented. Disaster and mesh
 * status live on the Alerts tab ([StatusFragment]), not on a fake story list.
 */
class StatusActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unavailable)
        findViewById<TextView>(R.id.unavailable_title).setText(R.string.status_unavailable_title)
        findViewById<TextView>(R.id.unavailable_heading).setText(R.string.status_unavailable_title)
        findViewById<TextView>(R.id.unavailable_body).setText(R.string.status_unavailable_body)
        findViewById<android.view.View>(R.id.unavailable_back).setOnClickListener { finish() }
    }
}
