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

/**
 * Scan / paste a peer's QR or invite code. Returns the resolved peer userId
 * via [RESULT_CODE]. Stage 6.2 also returns the scanned public key via
 * [RESULT_PUB_KEY] (only present when the input parsed as a [QrCodec]
 * payload, since [InviteCode] does not carry a key); callers use that to
 * out-of-band-verify the peer's TOFU pin via
 * [com.tharmesh.identity.PeerTrustStore.markVerified].
 */
class ScanQrActivity : AppCompatActivity() {

    companion object {
        const val RESULT_CODE = "result_code"
        const val RESULT_PUB_KEY = "result_pub_key"
        const val RESULT_DISPLAY_NAME = "result_display_name"
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
            val resolvedUserId = when {
                qr != null && qr.userId.isNotBlank() -> qr.userId
                invite != null -> invite.userId
                else -> raw
            }
            val resolvedPubKey = qr?.publicKeyBase64?.takeIf { it.isNotBlank() }
            val resolvedName = qr?.name?.takeIf { it.isNotBlank() }

            val data = Intent()
            data.putExtra(RESULT_CODE, resolvedUserId)
            if (resolvedPubKey != null) data.putExtra(RESULT_PUB_KEY, resolvedPubKey)
            if (resolvedName != null) data.putExtra(RESULT_DISPLAY_NAME, resolvedName)
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
