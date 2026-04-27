package com.tharmesh.ui.contacts

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tharmesh.identity.IdentityQrPayload
import com.tharmesh.identity.IdentityStore
import com.tharmesh.identity.InviteCode
import com.tharmesh.identity.LocalIdentity
import com.tharmesh.identity.QrCodec
import tharmesh.app.R

/**
 * Premium "My QR" screen. Renders the user's identity payload as a real
 * scannable QR code (white card on a dark background) plus the human-readable
 * name + userId underneath and a copyable text invite code as a fallback for
 * peers without camera access.
 *
 * The QR payload is the same JSON [QrCodec.encode] format the rest of the app
 * already produces and consumes, so existing peers (with the camera scanner
 * shipped alongside this activity) can scan it and get the userId + display
 * name + public key in one tap.
 */
class MyQrActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_qr)
        title = getString(R.string.my_qr_title)

        // Stage 7 PR E — count "showed my invite to someone". Bumped
        // once per Activity instance (i.e. once per user-initiated open
        // of the screen), not on configuration changes — savedInstanceState
        // null means a fresh launch.
        if (savedInstanceState == null) {
            com.tharmesh.data.GrowthMetrics.recordInviteSent(this)
        }

        val identity = loadIdentitySafe()
        val qrPayload = QrCodec.encode(
            IdentityQrPayload(
                userId = identity.userId,
                name = identity.name,
                publicKeyBase64 = identity.publicKeyBase64
            )
        )

        findViewById<TextView>(R.id.text_my_name).text = identity.name
        findViewById<TextView>(R.id.text_my_userid).text = identity.userId
        val invite = InviteCode.generate(identity.userId)
        findViewById<TextView>(R.id.text_invite_code).text = invite

        val image = findViewById<ImageView>(R.id.image_qr)
        image.post {
            val size = minOf(image.width, image.height).coerceAtLeast(512)
            image.setImageBitmap(encodeQrBitmap(qrPayload, size))
        }

        findViewById<Button>(R.id.btn_share).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND)
            send.type = "text/plain"
            send.putExtra(
                Intent.EXTRA_TEXT,
                getString(R.string.my_qr_share_text, identity.name, invite)
            )
            startActivity(Intent.createChooser(send, getString(R.string.my_qr_share)))
        }
    }

    /**
     * Encodes [text] as a PNG-style 1-bit bitmap using ZXing's
     * [QRCodeWriter] at error-correction level M (15% redundancy — a safe
     * trade-off between density and reliability for printed/photographed
     * QR codes). The returned bitmap is square at [size] x [size] pixels.
     */
    private fun encodeQrBitmap(text: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width
        val h = matrix.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun loadIdentitySafe(): LocalIdentity {
        return try {
            IdentityStore(applicationContext).ensureIdentity()
        } catch (ignored: Throwable) {
            LocalIdentity(
                userId = "local-user",
                name = "Local User",
                publicKeyBase64 = "",
                privateKeyBase64 = ""
            )
        }
    }
}
