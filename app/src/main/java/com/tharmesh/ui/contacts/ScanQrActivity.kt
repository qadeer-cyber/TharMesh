package com.tharmesh.ui.contacts

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.tharmesh.identity.InviteCode
import com.tharmesh.identity.QrCodec
import tharmesh.app.R

/**
 * Camera-based scanner for a peer's QR or invite code. Backed by
 * [DecoratedBarcodeView] from `zxing-android-embedded` for the camera
 * preview + decode loop. Falls back to a paste-text dialog when the
 * camera permission is denied or when the user explicitly taps the
 * "Paste instead" affordance — that path mirrors the pre-PR behaviour
 * for users who already have an invite code or QR JSON copied.
 *
 * Returns the resolved peer userId via [RESULT_CODE]. Stage 6.2 also
 * returns the scanned public key via [RESULT_PUB_KEY] (only present when
 * the input parsed as a [QrCodec] payload, since [InviteCode] does not
 * carry a key); callers use that to out-of-band-verify the peer's TOFU
 * pin via [com.tharmesh.identity.PeerTrustStore.markVerified].
 */
class ScanQrActivity : AppCompatActivity() {

    companion object {
        const val RESULT_CODE = "result_code"
        const val RESULT_PUB_KEY = "result_pub_key"
        const val RESULT_DISPLAY_NAME = "result_display_name"

        private const val REQUEST_CAMERA_PERMISSION = 7101
    }

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var rationaleView: View
    private var hasReturned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_qr)
        title = getString(R.string.scan_qr_title)

        barcodeView = findViewById(R.id.barcode_scanner)
        rationaleView = findViewById(R.id.permission_rationale)

        findViewById<Button>(R.id.btn_paste_fallback).setOnClickListener { showPasteDialog() }
        findViewById<Button>(R.id.btn_paste_instead).setOnClickListener { showPasteDialog() }
        findViewById<Button>(R.id.btn_grant_camera).setOnClickListener { requestCameraPermission() }

        if (hasCameraPermission()) {
            startScanner()
        } else {
            showRationale()
            requestCameraPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Forward physical volume keys to the scanner so users on devices
        // with hardware keys can torch / zoom while scanning.
        return barcodeView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    private fun startScanner() {
        rationaleView.visibility = View.GONE
        barcodeView.visibility = View.VISIBLE
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                // The terminal `hasReturned` guard lives inside [returnResult]
                // so paste-dialog and scanner paths gate against each other.
                // Pause first to stop the decode loop while we commit the
                // result; if [returnResult] declines (empty text, already
                // returned), resume so the user isn't stuck on a frozen
                // preview (code review: PR #33).
                barcodeView.pause()
                if (!returnResult(result.text.orEmpty())) {
                    barcodeView.resume()
                }
            }
        })
        barcodeView.resume()
    }

    private fun showRationale() {
        rationaleView.visibility = View.VISIBLE
        barcodeView.visibility = View.GONE
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startScanner()
        } else {
            showRationale()
        }
    }

    private fun showPasteDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this)
        container.setPadding(pad, pad, pad, 0)
        val input = EditText(this)
        input.hint = getString(R.string.scan_qr_paste_hint)
        container.addView(input)
        AlertDialog.Builder(this)
            .setTitle(R.string.scan_qr_paste_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_start) { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                if (raw.isNotEmpty()) returnResult(raw)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Parse [raw] as a [QrCodec] payload first, then as an [InviteCode],
     * and finally treat it as a literal userId. Sets the activity result
     * with whichever fields could be resolved.
     *
     * Returns `true` when the result was committed (and the activity
     * is finishing), `false` when the input was empty / already returned
     * — the camera caller uses that to resume the scanner so the user
     * isn't stuck on a frozen preview.
     *
     * Single source of truth for [hasReturned]: both the scanner callback
     * and the paste dialog funnel through here, so a queued
     * `barcodeResult` event firing after the user has already submitted
     * a pasted invite cannot overwrite the deliberate paste with an
     * unrelated scan (code review: PR #33).
     */
    private fun returnResult(raw: String): Boolean {
        if (hasReturned) return false

        val text = raw.trim()
        // Set hasReturned only AFTER the empty-text guard, otherwise an
        // empty barcode result locks the activity (code review: PR #33).
        if (text.isEmpty()) return false
        hasReturned = true

        val qr = QrCodec.decode(text)
        val invite = InviteCode.parse(text)
        val resolvedUserId = when {
            qr != null && qr.userId.isNotBlank() -> qr.userId
            invite != null -> invite.userId
            else -> text
        }
        val resolvedPubKey = qr?.publicKeyBase64?.takeIf { it.isNotBlank() }
        val resolvedName = qr?.name?.takeIf { it.isNotBlank() }

        val data = Intent()
        data.putExtra(RESULT_CODE, resolvedUserId)
        if (resolvedPubKey != null) data.putExtra(RESULT_PUB_KEY, resolvedPubKey)
        if (resolvedName != null) data.putExtra(RESULT_DISPLAY_NAME, resolvedName)
        setResult(Activity.RESULT_OK, data)
        finish()
        return true
    }
}
