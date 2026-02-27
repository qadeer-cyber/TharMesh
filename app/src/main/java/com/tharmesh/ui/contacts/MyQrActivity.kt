package com.tharmesh.ui.contacts

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tharmesh.identity.IdentityQrPayload
import com.tharmesh.identity.IdentityStore
import com.tharmesh.identity.InviteCode
import com.tharmesh.identity.QrCodec

class MyQrActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val qrText = TextView(this)
        qrText.textSize = 16f
        qrText.gravity = Gravity.CENTER

        val inviteText = TextView(this)
        inviteText.textSize = 14f
        inviteText.gravity = Gravity.CENTER

        val identity = loadIdentitySafe()
        val qrPayload = QrCodec.encode(
            IdentityQrPayload(
                userId = identity.userId,
                name = identity.name,
                publicKeyBase64 = identity.publicKeyBase64
            )
        )
        qrText.text = "QR: $qrPayload"
        inviteText.text = "Invite: " + InviteCode.generate(identity.userId)

        root.addView(qrText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(inviteText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        title = "My QR"
    }

    private fun loadIdentitySafe(): com.tharmesh.identity.LocalIdentity {
        return try {
            IdentityStore(applicationContext).ensureIdentity()
        } catch (ignored: Throwable) {
            com.tharmesh.identity.LocalIdentity(
                userId = "local-user",
                name = "Local User",
                publicKeyBase64 = "",
                privateKeyBase64 = ""
            )
        }
    }
}
