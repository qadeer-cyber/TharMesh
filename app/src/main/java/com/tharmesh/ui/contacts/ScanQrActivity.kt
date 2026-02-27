package com.tharmesh.ui.contacts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class ScanQrActivity : AppCompatActivity() {

    companion object {
        const val RESULT_CODE = "result_code"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        root.setPadding(pad, pad, pad, pad)

        val input = EditText(this)
        input.hint = "Paste QR / Invite code"
        input.inputType = InputType.TYPE_CLASS_TEXT
        root.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val addButton = Button(this)
        addButton.text = "Add"
        addButton.setOnClickListener {
            val code = input.text?.toString()?.trim().orEmpty()
            if (code.isNotEmpty()) {
                val data = Intent()
                data.putExtra(RESULT_CODE, code)
                setResult(Activity.RESULT_OK, data)
                finish()
            }
        }
        root.addView(
            addButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
        title = "Scan QR"
    }
}
