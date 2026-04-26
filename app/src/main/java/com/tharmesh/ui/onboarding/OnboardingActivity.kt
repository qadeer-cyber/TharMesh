// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.ui.onboarding

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tharmesh.TharMeshApp
import com.tharmesh.data.UserPrefs
import com.tharmesh.permissions.NearbyPermissions
import com.tharmesh.ui.contacts.MyQrActivity
import com.tharmesh.ui.contacts.ScanQrActivity
import com.tharmesh.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tharmesh.app.R
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Stage 7 PR C — first-run onboarding. Three steps:
 *
 *  1. Set your name (required) + optional photo. We seed the EditText
 *     with whatever username [com.tharmesh.ui.auth.LoginActivity] saved
 *     so Google-signed users see their display name pre-filled and just
 *     tap Continue.
 *  2. Enable mesh — explainer + button that requests
 *     [NearbyPermissions.required] at the OS level. Skippable so users
 *     can finish onboarding before they grant Bluetooth/Location.
 *  3. Add your first contact — Scan QR or "Show my QR" so a peer can
 *     scan them; both fall through to a Finish / Skip pair.
 *
 * Routed in by [LoginActivity.finishWith] when
 * [UserPrefs.shouldShowOnboarding] returns true. On finish (Done or
 * any Skip), [UserPrefs.markOnboarded] is set and we route to
 * [MainActivity]. The flow is single-activity / `ViewFlipper`-driven
 * so back-navigation between steps works without a fragment back-stack.
 */
class OnboardingActivity : AppCompatActivity() {

    private companion object {
        const val REQUEST_PICK_AVATAR = 9001
        const val REQUEST_SCAN_QR = 9002
        const val REQUEST_NEARBY_PERMS = 9003
        const val AVATAR_FILE = "avatar.jpg"
        const val AVATAR_MAX_DIM = 512
    }

    private lateinit var flipper: ViewFlipper
    private lateinit var stepDots: List<View>

    // Step 1 — Name + photo
    private lateinit var avatarImage: ImageView
    private lateinit var avatarLetter: TextView
    private lateinit var nameInput: EditText
    private lateinit var nameContinue: Button

    // Step 2 — Mesh
    private lateinit var meshEnable: Button
    private lateinit var meshSkip: Button
    private lateinit var meshStatus: TextView

    // Step 3 — First contact
    private lateinit var firstScan: Button
    private lateinit var firstShowMyQr: Button
    private lateinit var firstDone: Button
    private lateinit var firstSkip: Button

    private val ioScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        title = ""

        flipper = findViewById(R.id.flipper)
        stepDots = listOf(
            findViewById(R.id.step_dot_1),
            findViewById(R.id.step_dot_2),
            findViewById(R.id.step_dot_3)
        )

        bindStepName()
        bindStepMesh()
        bindStepFirstContact()
        showStep(0)
    }

    private fun showStep(index: Int) {
        flipper.displayedChild = index
        stepDots.forEachIndexed { i, dot ->
            dot.setBackgroundResource(
                if (i <= index) R.drawable.bg_step_active else R.drawable.bg_step_inactive
            )
        }
    }

    // ============================================================== //
    // Step 1 — Name + optional photo                                  //
    // ============================================================== //
    private fun bindStepName() {
        avatarImage = findViewById(R.id.onb_avatar_image)
        avatarLetter = findViewById(R.id.onb_avatar_letter)
        nameInput = findViewById(R.id.onb_name_input)
        nameContinue = findViewById(R.id.onb_name_continue)

        // Pre-fill with whatever LoginActivity persisted (anonymous: the
        // userId; Google: the display name). Empty until the user types
        // something distinct from the userId, since the userId-as-name
        // fallback isn't a real name.
        val profile = UserPrefs.readProfile(this)
        val seed = profile?.username
            ?.takeIf { it.isNotBlank() && it != profile.userId }
            .orEmpty()
        nameInput.setText(seed)
        nameInput.setSelection(seed.length)

        renderAvatarPlaceholder(seed.firstOrNull())

        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val name = s?.toString()?.trim().orEmpty()
                nameContinue.isEnabled = name.isNotEmpty()
                renderAvatarPlaceholder(name.firstOrNull())
            }
        })

        findViewById<Button>(R.id.onb_pick_photo).setOnClickListener {
            launchPhotoPicker()
        }

        nameContinue.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener
            val current = UserPrefs.readProfile(this) ?: return@setOnClickListener
            UserPrefs.saveProfile(this, current.copy(username = name))
            showStep(1)
        }
    }

    private fun renderAvatarPlaceholder(letter: Char?) {
        val existingAvatar = UserPrefs.getAvatarLocalPath(this)
        if (existingAvatar != null) {
            val bmp = BitmapFactory.decodeFile(existingAvatar)
            if (bmp != null) {
                avatarImage.setImageBitmap(bmp)
                avatarLetter.text = ""
                return
            }
        }
        avatarImage.setImageDrawable(null)
        avatarLetter.text = letter?.uppercaseChar()?.toString() ?: ""
    }

    private fun launchPhotoPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(
                Intent.createChooser(intent, getString(R.string.onb_name_pick_photo)),
                REQUEST_PICK_AVATAR
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.onb_no_picker, Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistAvatar(uri: Uri): File? {
        val target = File(filesDir, AVATAR_FILE)
        return runCatching {
            val input: InputStream = contentResolver.openInputStream(uri)
                ?: return@runCatching null
            val raw = input.use { BitmapFactory.decodeStream(it) }
                ?: return@runCatching null
            val scaled = scaleDown(raw, AVATAR_MAX_DIM)
            FileOutputStream(target).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            target
        }.getOrNull()
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = w.toFloat() / h.toFloat()
        val (nw, nh) = if (w >= h) maxDim to (maxDim / ratio).toInt()
        else (maxDim * ratio).toInt() to maxDim
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    // ============================================================== //
    // Step 2 — Enable mesh                                            //
    // ============================================================== //
    private fun bindStepMesh() {
        meshEnable = findViewById(R.id.onb_mesh_enable)
        meshSkip = findViewById(R.id.onb_mesh_skip)
        meshStatus = findViewById(R.id.onb_mesh_status)
        refreshMeshStatus()

        meshEnable.setOnClickListener {
            if (NearbyPermissions.allGranted(this)) {
                refreshMeshStatus()
                showStep(2)
                return@setOnClickListener
            }
            val missing = NearbyPermissions.required.filter { perm ->
                ContextCompat.checkSelfPermission(this, perm) !=
                    PackageManager.PERMISSION_GRANTED
            }
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                REQUEST_NEARBY_PERMS
            )
        }

        meshSkip.setOnClickListener { showStep(2) }
    }

    private fun refreshMeshStatus() {
        val granted = NearbyPermissions.allGranted(this)
        meshStatus.text = if (granted) {
            getString(R.string.onb_mesh_enabled)
        } else {
            ""
        }
        meshEnable.text = if (granted) {
            getString(R.string.onb_continue)
        } else {
            getString(R.string.onb_mesh_enable)
        }
    }

    // ============================================================== //
    // Step 3 — First contact                                          //
    // ============================================================== //
    private fun bindStepFirstContact() {
        firstScan = findViewById(R.id.onb_first_scan)
        firstShowMyQr = findViewById(R.id.onb_first_show_my_qr)
        firstDone = findViewById(R.id.onb_first_done)
        firstSkip = findViewById(R.id.onb_first_skip)

        firstScan.setOnClickListener {
            startActivityForResult(
                Intent(this, ScanQrActivity::class.java),
                REQUEST_SCAN_QR
            )
        }
        firstShowMyQr.setOnClickListener {
            startActivity(Intent(this, MyQrActivity::class.java))
        }
        firstDone.setOnClickListener { finishOnboarding() }
        firstSkip.setOnClickListener { finishOnboarding() }
    }

    // ============================================================== //
    // Activity result + permission-result wiring                      //
    // ============================================================== //
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_AVATAR && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val saved = persistAvatar(uri)
            if (saved != null) {
                UserPrefs.setAvatarLocalPath(this, saved.absolutePath)
                renderAvatarPlaceholder(nameInput.text.toString().trim().firstOrNull())
            } else {
                Toast.makeText(this, R.string.onb_photo_save_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == REQUEST_SCAN_QR && resultCode == Activity.RESULT_OK) {
            val userId = data?.getStringExtra(ScanQrActivity.RESULT_CODE)?.takeIf { it.isNotBlank() }
                ?: return
            val pubKey = data.getStringExtra(ScanQrActivity.RESULT_PUB_KEY)
            val name = data.getStringExtra(ScanQrActivity.RESULT_DISPLAY_NAME) ?: userId
            ioScope.launch {
                val app = TharMeshApp.get()
                app.repository.addContact(userId, name)
                if (!pubKey.isNullOrBlank()) {
                    app.peerTrustStore.markVerified(userId, pubKey)
                }
            }
            finishOnboarding()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NEARBY_PERMS) return
        refreshMeshStatus()
        if (NearbyPermissions.allGranted(this)) {
            // Bring the mesh up immediately so it's already discovering peers
            // by the time the user lands on the first-contact step. Cheap to
            // call; idempotent if already started.
            TharMeshApp.get().ensureMeshStarted()
            showStep(2)
        }
        // If the user denied, leave the user on this step with the unchanged
        // "Skip for now" affordance — never block onboarding on permissions.
    }

    private fun finishOnboarding() {
        UserPrefs.markOnboarded(this)
        val next = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(next)
        finish()
    }
}
