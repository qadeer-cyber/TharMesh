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
import com.tharmesh.identity.InviteCode
import com.tharmesh.identity.QrCodec

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
            val raw = input.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) {
                return@setOnClickListener
            }

            val qr = QrCodec.decode(raw)
            val invite = InviteCode.parse(raw)
            val resolved = when {
                qr != null && qr.userId.isNotBlank() -> qr.userId
                invite != null -> invite.userId
                else -> raw
            }

            val data = Intent()
            data.putExtra(RESULT_CODE, resolved)
            setResult(Activity.RESULT_OK, data)
            finish()
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
