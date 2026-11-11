// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.ui.account

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tharmesh.TharMeshApp
import com.tharmesh.account.AccountMaterial
import com.tharmesh.account.AndroidAccountKeyBox
import com.tharmesh.account.AndroidDeviceKey
import com.tharmesh.account.AuthBackend
import com.tharmesh.account.AuthCall
import com.tharmesh.account.AuthRateLimiter
import com.tharmesh.account.CloudCredential
import com.tharmesh.account.CloudSessionStore
import com.tharmesh.account.DeviceEnrollment
import com.tharmesh.account.EnrollmentQr
import com.tharmesh.account.HandlePolicy
import com.tharmesh.account.IdentityLifecycle
import com.tharmesh.account.IdentityOrigin
import com.tharmesh.account.IdentityRecovery
import com.tharmesh.account.InstallRecord
import com.tharmesh.account.LocalAuth
import com.tharmesh.account.MeshMaterial
import com.tharmesh.account.OfflineMode
import com.tharmesh.account.OfflineModeResolver
import com.tharmesh.account.RecoveryUpload
import com.tharmesh.account.SupabaseDirectory
import com.tharmesh.account.UrlHttpTransport
import com.tharmesh.data.UserPrefs
import com.tharmesh.data.UserProfile
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.ui.legal.TermsActivity
import com.tharmesh.ui.main.MainActivity
import com.tharmesh.ui.onboarding.OnboardingActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tharmesh.app.BuildConfig
import tharmesh.app.R
import java.security.SecureRandom

/**
 * Account screens. Cloud calls fail closed when Supabase is not configured.
 * This activity does not use [com.tharmesh.account.SimulatedAccountDirectory].
 */
class AccountFlowActivity : AppCompatActivity() {

    enum class Screen {
        CREATE, SIGN_IN, VERIFY_EMAIL, RECOVER, SECURE_IDENTITY, SAVE_RECOVERY,
        DEVICES, OFFLINE, SECURITY
    }

    private val http = UrlHttpTransport()
    private val random = SecureRandom()
    private lateinit var screen: Screen
    private var pendingPhrase: String? = null
    private var pendingRecord: InstallRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_flow)
        screen = runCatching {
            Screen.valueOf(intent.getStringExtra(EXTRA_SCREEN) ?: Screen.SECURITY.name)
        }.getOrDefault(Screen.SECURITY)
        findViewById<TextView>(R.id.account_back).setOnClickListener { finish() }
        bind(screen)
    }

    private fun bind(next: Screen) {
        screen = next
        val title = findViewById<TextView>(R.id.account_title)
        val body = findViewById<TextView>(R.id.account_body)
        val f1 = field(R.id.account_field_1)
        val f2 = field(R.id.account_field_2)
        val f3 = field(R.id.account_field_3)
        val f4 = field(R.id.account_field_4)
        val confirm = findViewById<CheckBox>(R.id.account_confirm)
        val secret = findViewById<TextView>(R.id.account_secret)
        val qr = findViewById<ImageView>(R.id.account_qr)
        val primary = findViewById<TextView>(R.id.account_primary)
        val secondary = findViewById<TextView>(R.id.account_secondary)
        val tertiary = findViewById<TextView>(R.id.account_tertiary)
        val status = findViewById<TextView>(R.id.account_status)
        listOf(f1, f2, f3, f4).forEach { it.visibility = View.GONE; it.text = null }
        confirm.visibility = View.GONE
        confirm.isChecked = false
        secret.visibility = View.GONE
        qr.visibility = View.GONE
        secondary.visibility = View.GONE
        tertiary.visibility = View.GONE
        status.text = ""
        primary.setOnClickListener(null)
        secondary.setOnClickListener(null)
        tertiary.setOnClickListener(null)

        when (next) {
            Screen.CREATE -> {
                title.setText(R.string.account_create_title)
                body.setText(R.string.account_create_body)
                show(f1, R.string.account_field_email)
                show(f2, R.string.account_field_password)
                show(f3, R.string.account_field_handle)
                show(f4, R.string.account_field_name)
                primary.setText(R.string.account_action_create)
                primary.setOnClickListener { createAccount(f1, f2, f3, f4, status) }
            }
            Screen.SIGN_IN -> {
                title.setText(R.string.account_signin_title)
                body.setText(R.string.account_signin_body)
                show(f1, R.string.account_field_email)
                show(f2, R.string.account_field_password)
                primary.setText(R.string.account_action_sign_in)
                primary.setOnClickListener { signIn(f1, f2, status) }
            }
            Screen.VERIFY_EMAIL -> {
                title.setText(R.string.account_verify_title)
                body.setText(R.string.account_verify_body)
                show(f1, R.string.account_field_email)
                f1.setText(intent.getStringExtra(EXTRA_EMAIL).orEmpty())
                primary.setText(R.string.account_action_checked)
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.account_action_resend)
                tertiary.visibility = View.VISIBLE
                tertiary.setText(R.string.account_action_continue_offline)
                primary.setOnClickListener { refreshVerification(f1, status) }
                secondary.setOnClickListener { resend(f1, status) }
                tertiary.setOnClickListener { bind(Screen.SECURE_IDENTITY) }
            }
            Screen.RECOVER -> {
                title.setText(R.string.account_recover_title)
                body.setText(R.string.account_recover_body)
                show(f1, R.string.account_field_recovery)
                show(f2, R.string.account_field_ciphertext)
                f2.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                primary.setText(R.string.account_action_restore)
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.account_action_reset)
                confirm.visibility = View.VISIBLE
                confirm.setText(R.string.account_confirm_new_key)
                secondary.setOnClickListener {
                    if (!confirm.isChecked) {
                        status.setText(R.string.account_reset_warning)
                    } else {
                        resetIdentity(status)
                    }
                }
                primary.setOnClickListener { restore(f1, f2, status) }
            }
            Screen.SECURE_IDENTITY -> {
                title.setText(R.string.account_secure_title)
                body.setText(R.string.account_secure_body)
                val existing = UserPrefs.readIdentity(this)
                if (existing != null) {
                    primary.setText(R.string.account_action_protect)
                    primary.setOnClickListener { protectExisting(status) }
                } else {
                    confirm.visibility = View.VISIBLE
                    confirm.setText(R.string.account_confirm_first_key)
                    primary.setText(R.string.account_action_create_identity)
                    primary.setOnClickListener {
                        if (!confirm.isChecked) {
                            status.setText(R.string.account_error_held)
                        } else {
                            createFirstIdentity(status)
                        }
                    }
                    secondary.visibility = View.VISIBLE
                    secondary.setText(R.string.account_action_restore)
                    secondary.setOnClickListener { bind(Screen.RECOVER) }
                }
            }
            Screen.SAVE_RECOVERY -> {
                title.setText(R.string.account_save_title)
                body.setText(R.string.account_save_body)
                secret.visibility = View.VISIBLE
                secret.text = pendingPhrase ?: ""
                primary.setText(R.string.account_action_saved)
                primary.setOnClickListener {
                    pendingPhrase = null
                    secret.text = ""
                    secret.visibility = View.GONE
                    uploadRecovery(status)
                }
            }
            Screen.DEVICES -> {
                title.setText(R.string.account_devices_title)
                body.setText(R.string.account_devices_body)
                show(f1, R.string.account_field_enroll)
                primary.setText(R.string.account_action_show_qr)
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.account_action_approve)
                primary.setOnClickListener { showEnrollment(qr, secret, status) }
                secondary.setOnClickListener { approveEnrollment(f1, status) }
            }
            Screen.OFFLINE -> {
                title.setText(R.string.account_offline_title)
                body.setText(R.string.account_offline_body)
                primary.setText(R.string.account_security_title)
                status.text = offlineLabel()
                primary.setOnClickListener { bind(Screen.SECURITY) }
            }
            Screen.SECURITY -> {
                title.setText(R.string.account_security_title)
                body.setText(R.string.account_security_body)
                status.text = securitySummary()
                primary.setText(R.string.account_secure_title)
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.account_recover_title)
                tertiary.visibility = View.VISIBLE
                tertiary.setText(R.string.account_offline_title)
                primary.setOnClickListener { bind(Screen.SECURE_IDENTITY) }
                secondary.setOnClickListener { bind(Screen.RECOVER) }
                tertiary.setOnClickListener { bind(Screen.OFFLINE) }
            }
        }
    }

    private fun createAccount(
        email: EditText,
        password: EditText,
        handle: EditText,
        displayName: EditText,
        status: TextView
    ) {
        val mail = email.text.toString().trim()
        val pass = password.text.toString()
        val h = HandlePolicy.normalize(handle.text.toString())
        if (h == null || !mail.contains("@") || pass.length < 8) {
            status.setText(R.string.account_error_invalid)
            return
        }
        val name = displayName.text.toString().trim().ifBlank { h }
        if (!limiter.allow(System.currentTimeMillis())) {
            status.setText(R.string.account_error_rate_limited)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AuthBackend.signUp(
                    http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY,
                    mail, pass, mapOf("handle" to h, "display_name" to name)
                )
            }
            when (result) {
                is AuthCall.Failed -> {
                    if (result.reason != "cloud_auth_not_configured" && result.reason != "invalid_credentials_shape") {
                        limiter.recordFailure(System.currentTimeMillis())
                    }
                    status.text = explain(result.reason)
                }
                is AuthCall.PendingVerification -> {
                    intent.putExtra(EXTRA_EMAIL, mail)
                    intent.putExtra(EXTRA_ACCOUNT_ID, result.accountId)
                    UserPrefs.setIdentityHeld(this@AccountFlowActivity, true)
                    bind(Screen.VERIFY_EMAIL)
                }
                is AuthCall.Ok -> {
                    intent.putExtra(EXTRA_EMAIL, mail)
                    intent.putExtra(EXTRA_ACCOUNT_ID, result.tokens.accountId)
                    intent.putExtra(EXTRA_ACCESS, result.tokens.accessToken)
                    rememberCloud(result.tokens.accountId, result.tokens.accessToken, result.tokens.expiresInSeconds, result.tokens.emailVerified)
                    UserPrefs.setIdentityHeld(this@AccountFlowActivity, true)
                    if (result.tokens.emailVerified) bind(Screen.SECURE_IDENTITY) else bind(Screen.VERIFY_EMAIL)
                }
            }
        }
    }

    private fun signIn(email: EditText, password: EditText, status: TextView) {
        val mail = email.text.toString().trim()
        val pass = password.text.toString()
        if (!limiter.allow(System.currentTimeMillis())) {
            status.setText(R.string.account_error_rate_limited)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AuthBackend.signIn(http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, mail, pass)
            }
            when (result) {
                is AuthCall.Failed -> {
                    if (result.reason == "network") {
                        status.setText(R.string.account_error_network)
                    } else {
                        if (result.reason != "cloud_auth_not_configured") limiter.recordFailure(System.currentTimeMillis())
                        status.text = explain(result.reason)
                    }
                }
                is AuthCall.PendingVerification -> status.setText(R.string.account_verify_body)
                is AuthCall.Ok -> applyLogin(result, status)
            }
        }
    }

    private fun applyLogin(result: AuthCall.Ok, status: TextView) {
        val mesh = UserPrefs.readIdentity(this)?.let {
            val profile = UserPrefs.readProfile(this)
            MeshMaterial.from(it, profile?.userId ?: "user")
        }
        val now = System.currentTimeMillis()
        val expiresAt = now + result.tokens.expiresInSeconds * 1000L
        val login = IdentityLifecycle.cloudLogin(
            record = InstallRecord(mesh = mesh, provider = UserPrefs.PROVIDER_EMAIL),
            accountId = result.tokens.accountId,
            serverFingerprint = null,
            email = null,
            emailVerified = result.tokens.emailVerified,
            accessToken = result.tokens.accessToken,
            expiresAtMs = expiresAt,
            now = now
        )
        CloudSessionStore.write(this, login.record)
        when (login.bind) {
            com.tharmesh.account.AccountSession.Bind.KEEP_LOCAL_IDENTITY -> {
                UserPrefs.setIdentityHeld(this, false)
                status.setText(R.string.account_signin_body)
                goHome()
            }
            com.tharmesh.account.AccountSession.Bind.NEEDS_RECOVERY_OR_NEW_IDENTITY -> {
                UserPrefs.setIdentityHeld(this, true)
                intent.putExtra(EXTRA_ACCOUNT_ID, result.tokens.accountId)
                intent.putExtra(EXTRA_ACCESS, result.tokens.accessToken)
                bind(Screen.RECOVER)
            }
            com.tharmesh.account.AccountSession.Bind.REFUSE_SILENT_REPLACEMENT -> {
                status.setText(R.string.account_error_conflict)
            }
        }
    }

    private fun protectExisting(status: TextView) {
        val identity = UserPrefs.readIdentity(this) ?: return
        val profile = UserPrefs.readProfile(this)
        val userId = profile?.userId ?: return
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                val phrase = IdentityRecovery.generatePhrase(random)
                val account = CryptoIdentity.generate()
                val record = IdentityLifecycle.establishOriginal(
                    record = InstallRecord(
                        mesh = MeshMaterial.from(identity, userId),
                        accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID),
                        displayName = profile.username,
                        provider = profile.authProvider
                    ),
                    mesh = MeshMaterial.from(identity, userId),
                    account = AccountMaterial(
                        account.publicKeyBase64,
                        account.privateKeyBase64,
                        generation = 1,
                        origin = IdentityOrigin.LEGACY_UNLINKED
                    ),
                    phrase = phrase,
                    random = random
                )
                phrase to outcomePair(record)
            }
            val (phrase, created) = outcome
            if (created == null) {
                status.setText(R.string.account_error_conflict)
                return@launch
            }
            if (!UserPrefs.writeIdentity(this@AccountFlowActivity, identity, allowReplace = false)) {
                status.setText(R.string.account_error_conflict)
                return@launch
            }
            finishSeal(created, phrase, status)
        }
    }

    private fun createFirstIdentity(status: TextView) {
        if (UserPrefs.readIdentity(this) != null) {
            status.setText(R.string.account_error_conflict)
            return
        }
        lifecycleScope.launch {
            val built = withContext(Dispatchers.Default) {
                val meshKey = CryptoIdentity.generate()
                val accountKey = CryptoIdentity.generate()
                val phrase = IdentityRecovery.generatePhrase(random)
                val userId = UserPrefs.readProfile(this@AccountFlowActivity)?.userId
                    ?: ("user-" + java.util.UUID.randomUUID().toString().take(8))
                val mesh = MeshMaterial.from(meshKey, userId)
                val outcome = IdentityLifecycle.establishOriginal(
                    record = InstallRecord(
                        accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID),
                        email = intent.getStringExtra(EXTRA_EMAIL),
                        provider = UserPrefs.PROVIDER_EMAIL
                    ),
                    mesh = mesh,
                    account = AccountMaterial(
                        accountKey.publicKeyBase64,
                        accountKey.privateKeyBase64,
                        generation = 1,
                        origin = IdentityOrigin.ORIGINAL
                    ),
                    phrase = phrase,
                    random = random
                )
                Triple(meshKey, phrase, outcome)
            }
            val (meshKey, phrase, outcome) = built
            val created = (outcome as? IdentityLifecycle.EstablishResult.Created) ?: run {
                status.setText(R.string.account_error_conflict)
                return@launch
            }
            if (!created.presentsAsOriginal) {
                status.setText(R.string.account_reset_warning)
            }
            if (!UserPrefs.writeIdentity(this@AccountFlowActivity, meshKey, allowReplace = false)) {
                status.setText(R.string.account_error_conflict)
                return@launch
            }
            val name = intent.getStringExtra(EXTRA_EMAIL)?.substringBefore("@") ?: meshKey.fingerprint
            val existing = UserPrefs.readProfile(this@AccountFlowActivity)
            UserPrefs.saveProfile(
                this@AccountFlowActivity,
                UserProfile(
                    userId = existing?.userId ?: created.record.mesh!!.userId,
                    username = existing?.username ?: name,
                    email = existing?.email ?: intent.getStringExtra(EXTRA_EMAIL),
                    avatarUrl = existing?.avatarUrl,
                    authProvider = existing?.authProvider ?: UserPrefs.PROVIDER_EMAIL
                )
            )
            UserPrefs.setIdentityHeld(this@AccountFlowActivity, false)
            finishSeal(created.record, phrase, status)
        }
    }

    private fun resetIdentity(status: TextView) {
        lifecycleScope.launch {
            val built = withContext(Dispatchers.Default) {
                val meshKey = CryptoIdentity.generate()
                val accountKey = CryptoIdentity.generate()
                val phrase = IdentityRecovery.generatePhrase(random)
                val userId = UserPrefs.readProfile(this@AccountFlowActivity)?.userId
                    ?: ("user-" + java.util.UUID.randomUUID().toString().take(8))
                val outcome = IdentityLifecycle.explicitReset(
                    record = InstallRecord(accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)),
                    mesh = MeshMaterial.from(meshKey, userId),
                    account = AccountMaterial(accountKey.publicKeyBase64, accountKey.privateKeyBase64, 1, IdentityOrigin.RESET),
                    phrase = phrase,
                    userConfirmedWarning = true,
                    random = random
                )
                Triple(meshKey, phrase, outcome)
            }
            val created = built.third as? IdentityLifecycle.EstablishResult.Created ?: return@launch
            if (created.presentsAsOriginal) {
                status.setText(R.string.account_reset_warning)
                return@launch
            }
            status.setText(R.string.account_reset_warning)
            if (!UserPrefs.writeIdentity(this@AccountFlowActivity, built.first, allowReplace = true)) {
                status.setText(R.string.account_error_conflict)
                return@launch
            }
            UserPrefs.setIdentityHeld(this@AccountFlowActivity, false)
            val profile = UserPrefs.readProfile(this@AccountFlowActivity)
            if (profile == null) {
                UserPrefs.saveProfile(
                    this@AccountFlowActivity,
                    UserProfile(
                        userId = created.record.mesh!!.userId,
                        username = created.record.mesh.userId,
                        authProvider = UserPrefs.PROVIDER_EMAIL
                    )
                )
            }
            finishSeal(created.record, built.second, status)
        }
    }

    private fun restore(phraseField: EditText, blobField: EditText, status: TextView) {
        val phrase = phraseField.text.toString()
        val pasted = RecoveryUpload.parse(blobField.text.toString())
        val stored = CloudSessionStore.readRecovery(this)?.let { RecoveryUpload.parse(it) }
        val sealed = pendingRecord?.recoverySealed ?: pasted ?: stored
        if (sealed == null) {
            status.setText(R.string.account_error_wrong_recovery)
            return
        }
        val before = UserPrefs.readIdentity(this)?.publicKeyBase64
        val result = IdentityLifecycle.restore(InstallRecord(mesh = before?.let {
            MeshMaterial(UserPrefs.readProfile(this)?.userId ?: "", it, "")
        }), phrase, sealed)
        when (result) {
            is IdentityLifecycle.RestoreResult.WrongSecret -> status.setText(R.string.account_error_wrong_recovery)
            is IdentityLifecycle.RestoreResult.Conflict -> status.setText(R.string.account_error_conflict)
            is IdentityLifecycle.RestoreResult.Malformed -> status.setText(R.string.account_error_wrong_recovery)
            is IdentityLifecycle.RestoreResult.Restored -> {
                val mesh = result.record.mesh ?: return
                val identity = CryptoIdentity.fromBase64(mesh.privateKeyBase64, mesh.publicKeyBase64) ?: return
                if (!UserPrefs.writeIdentity(this, identity, allowReplace = false)) {
                    status.setText(R.string.account_error_conflict)
                    return
                }
                val existing = UserPrefs.readProfile(this)
                if (existing == null || existing.userId == mesh.userId) {
                    UserPrefs.saveProfile(
                        this,
                        UserProfile(
                            userId = existing?.userId ?: mesh.userId,
                            username = existing?.username ?: mesh.userId,
                            email = existing?.email,
                            avatarUrl = existing?.avatarUrl,
                            authProvider = existing?.authProvider ?: UserPrefs.PROVIDER_EMAIL
                        )
                    )
                }
                UserPrefs.setIdentityHeld(this, false)
                if (!AndroidAccountKeyBox.seal(this, result.record.account!!.privateKeyBase64)) {
                    status.setText(R.string.account_error_keystore)
                }
                goHome()
            }
        }
    }

    private fun finishSeal(record: InstallRecord, phrase: String, status: TextView) {
        val account = record.account
        if (account != null && !AndroidAccountKeyBox.seal(this, account.privateKeyBase64)) {
            status.setText(R.string.account_error_keystore)
        }
        pendingPhrase = phrase
        pendingRecord = record
        val accountId = record.accountId ?: "local"
        record.recoverySealed?.let { CloudSessionStore.writeRecovery(this, RecoveryUpload.body(accountId, it)) }
        CloudSessionStore.write(this, record)
        TharMeshApp.get().ensureMeshReady()
        bind(Screen.SAVE_RECOVERY)
    }

    private fun uploadRecovery(status: TextView) {
        val record = pendingRecord
        val sealed = record?.recoverySealed
        val accountId = record?.accountId
        val access = intent.getStringExtra(EXTRA_ACCESS)
        if (sealed == null || accountId.isNullOrBlank() || access.isNullOrBlank() ||
            !AuthBackend.configured(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        ) {
            status.setText(R.string.account_saved_local_only)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SupabaseDirectory.putRecovery(
                    http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY,
                    access, accountId, sealed
                )
            }
            status.setText(
                if (result is AuthCall.Ok) R.string.account_uploaded else R.string.account_saved_local_only
            )
        }
    }

    private fun showEnrollment(qr: ImageView, secret: TextView, status: TextView) {
        val identity = UserPrefs.readIdentity(this) ?: run {
            status.setText(R.string.account_error_held)
            return
        }
        val device = AndroidDeviceKey.loadOrCreate()
        if (device == null || !device.hardwareNonExportable) {
            status.setText(R.string.account_error_keystore)
            return
        }
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
            ?: UserPrefs.readProfile(this)?.userId
            ?: "local"
        val request = DeviceEnrollment.createRequest(
            accountId = accountId,
            meshPublicKey = identity.publicKeyBase64,
            device = device,
            deviceId = identity.fingerprint,
            nonce = ByteArray(16).also { random.nextBytes(it) }
        )
        val text = EnrollmentQr.encodeRequest(request)
        secret.visibility = View.VISIBLE
        secret.text = text
        qr.visibility = View.VISIBLE
        qr.setImageBitmap(encodeQr(text, 512))
        status.text = identity.fingerprint
    }

    private fun approveEnrollment(field: EditText, status: TextView) {
        val request = EnrollmentQr.decodeRequest(field.text.toString().trim())
        if (request == null) {
            status.setText(R.string.account_error_approve)
            return
        }
        val accountPrivate = AndroidAccountKeyBox.open(this)
        if (accountPrivate == null) {
            status.setText(R.string.account_error_keystore)
            return
        }
        val accountPublic = pendingRecord?.account?.publicKeyBase64
            ?: CloudSessionStore.readAccountPublic(this)
        if (accountPublic == null) {
            status.setText(R.string.account_error_approve)
            return
        }
        val account = CryptoIdentity.fromBase64(accountPrivate, accountPublic)
        if (account == null) {
            status.setText(R.string.account_error_approve)
            return
        }
        val localAccount = pendingRecord?.accountId ?: intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: request.accountId
        val cert = DeviceEnrollment.approve(
            request = request,
            account = account,
            localAccountId = localAccount,
            generation = pendingRecord?.account?.generation ?: 1,
            userApproved = true
        )
        if (cert == null) {
            status.setText(R.string.account_error_approve)
            return
        }
        status.text = EnrollmentQr.encodeCertificate(cert)
    }

    private fun refreshVerification(email: EditText, status: TextView) {
        val access = intent.getStringExtra(EXTRA_ACCESS)
        if (access.isNullOrBlank()) {
            status.setText(R.string.account_error_not_configured)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AuthBackend.fetchUser(http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, access)
            }
            when (result) {
                is AuthCall.Ok -> if (result.tokens.emailVerified) bind(Screen.SECURE_IDENTITY) else status.setText(R.string.account_verify_body)
                is AuthCall.PendingVerification -> status.setText(R.string.account_verify_body)
                is AuthCall.Failed -> status.text = explain(result.reason)
            }
        }
    }

    private fun resend(email: EditText, status: TextView) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AuthBackend.resendVerification(
                    http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, email.text.toString()
                )
            }
            status.text = when (result) {
                is AuthCall.Failed -> explain(result.reason)
                else -> getString(R.string.account_verify_body)
            }
        }
    }

    private fun rememberCloud(accountId: String, access: String, expiresIn: Long, verified: Boolean) {
        val now = System.currentTimeMillis()
        CloudSessionStore.write(
            this,
            InstallRecord(
                accountId = accountId,
                accessToken = access,
                tokenExpiresAtMs = now + expiresIn * 1000L,
                emailVerified = verified,
                cloud = if (expiresIn <= 0L) CloudCredential.TOKEN_EXPIRED else CloudCredential.ACTIVE,
                localAuth = LocalAuth.AWAITING_IDENTITY,
                holdMeshGeneration = true
            )
        )
    }

    private fun explain(reason: String): String {
        return when (reason) {
            "cloud_auth_not_configured" -> getString(R.string.account_error_not_configured)
            "network" -> getString(R.string.account_error_network)
            "invalid_credentials_shape", "invalid_handle" -> getString(R.string.account_error_invalid)
            "handle_taken", "email_taken" -> getString(R.string.account_error_handle)
            else -> getString(R.string.login_failed_fmt, reason)
        }
    }

    private fun offlineLabel(): String {
        val mode = OfflineModeResolver.resolve(
            hasNetwork = hasNetwork(),
            hasLocalMesh = UserPrefs.readIdentity(this) != null,
            localAuth = if (UserPrefs.hasProfile(this)) LocalAuth.OFFLINE_AUTHORIZED else CloudSessionStore.readLocal(this),
            cloud = CloudSessionStore.readCloud(this)
        )
        return getString(stringFor(mode))
    }

    private fun securitySummary(): String {
        val profile = UserPrefs.readProfile(this)
        val id = UserPrefs.readIdentity(this)
        return listOf(
            offlineLabel(),
            profile?.userId ?: "—",
            id?.fingerprint ?: getString(R.string.settings_account_fingerprint_missing),
            profile?.authProvider ?: UserPrefs.PROVIDER_ANONYMOUS
        ).joinToString("\n")
    }

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }

    private fun goHome() {
        TharMeshApp.get().ensureMeshReady()
        val target = when {
            !UserPrefs.hasAcceptedCurrentTerms(this) -> TermsActivity::class.java
            UserPrefs.shouldShowOnboarding(this) -> OnboardingActivity::class.java
            else -> MainActivity::class.java
        }
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun field(id: Int): EditText = findViewById(id)

    private fun show(field: EditText, hint: Int) {
        field.visibility = View.VISIBLE
        field.setHint(hint)
    }

    private fun encodeQr(text: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun outcomePair(outcome: IdentityLifecycle.EstablishResult): InstallRecord? {
        return (outcome as? IdentityLifecycle.EstablishResult.Created)?.record
    }

    companion object {
        private const val EXTRA_SCREEN = "screen"
        private const val EXTRA_EMAIL = "email"
        private const val EXTRA_ACCOUNT_ID = "account_id"
        private const val EXTRA_ACCESS = "access_token"
        private val limiter = AuthRateLimiter()

        fun intent(context: Context, screen: Screen): Intent {
            return Intent(context, AccountFlowActivity::class.java).putExtra(EXTRA_SCREEN, screen.name)
        }

        private fun stringFor(mode: OfflineMode): Int {
            return when (mode) {
                OfflineMode.ONLINE_SESSION -> R.string.account_status_online
                OfflineMode.OFFLINE_AUTHORIZED -> R.string.account_status_offline_authorized
                OfflineMode.TOKEN_EXPIRED_STILL_LOCAL -> R.string.account_status_token_expired
                OfflineMode.CLOUD_UNREACHABLE -> R.string.account_status_unreachable
                OfflineMode.CLOUD_SIGNED_OUT -> R.string.account_status_signed_out
                OfflineMode.NEEDS_SIGN_IN -> R.string.account_status_needs_sign_in
            }
        }
    }
}
