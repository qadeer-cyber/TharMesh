// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.ui.account

import com.tharmesh.ui.theme.Appearance
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
import com.tharmesh.account.AccountActivation
import com.tharmesh.account.AccountMaterial
import com.tharmesh.account.AndroidAccountKeyBox
import com.tharmesh.account.AndroidDeviceKey
import com.tharmesh.account.AuthRedirect
import com.tharmesh.account.AuthBackend
import com.tharmesh.account.AuthCall
import com.tharmesh.account.AuthRateLimiter
import com.tharmesh.account.CloudCredential
import com.tharmesh.account.CloudSessionStore
import com.tharmesh.account.DeviceDirectory
import com.tharmesh.account.DeviceEnrollment
import com.tharmesh.account.RecentReauth
import com.tharmesh.account.RecoveryRotationStage
import com.tharmesh.account.EnrollmentReplayGuard
import com.tharmesh.account.EnrollmentQr
import com.tharmesh.account.LegacyReconciliation
import com.tharmesh.account.HandlePolicy
import com.tharmesh.account.IdentityProvisioning
import com.tharmesh.account.IdentityLifecycle
import com.tharmesh.account.IdentityOrigin
import com.tharmesh.account.IdentityRecovery
import com.tharmesh.account.InstallRecord
import com.tharmesh.account.LocalAuth
import com.tharmesh.account.MeshMaterial
import com.tharmesh.account.OfflineMode
import com.tharmesh.account.OfflineModeResolver
import com.tharmesh.account.PendingSignup
import com.tharmesh.account.PendingSignupStore
import com.tharmesh.account.RecoveryRotation
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
        CREATE, SIGN_IN, VERIFY_EMAIL, EMAIL_OTP, GOOGLE, RECOVER, SECURE_IDENTITY, SAVE_RECOVERY,
        LEGACY_RECONCILE, ROTATE_RECOVERY, DEVICES, OFFLINE, SECURITY
    }

    private val otpPrefs by lazy { getSharedPreferences("tharmesh_email_otp", MODE_PRIVATE) }
    private var authBusy = false
    private var canonicalRecovery = false
    private var expectedRecoveryKey: String? = null
    private val googleAuth by lazy { com.tharmesh.auth.GoogleAuthService(this) }
    private val http = UrlHttpTransport()
    private val random = SecureRandom()
    private lateinit var screen: Screen
    private var pendingPhrase: String? = null
    private var pendingRecord: InstallRecord? = null
    private var pendingEnrollment: com.tharmesh.account.EnrollmentRequest? = null
    private var legacyReconcileMessage: LegacyReconciliation.Decision =
        LegacyReconciliation.Decision.REQUIRES_RECOVERY_OR_ENROLLMENT
    private var cloudDevices: List<DeviceDirectory.DeviceRow> = emptyList()
    private var localDeviceId: String? = null
    private var resendBlockedUntilMs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val resendTick = Runnable { if (screen == Screen.EMAIL_OTP) paintOtpCooldown() else paintResendCooldown() }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_flow)
        screen = runCatching {
            Screen.valueOf(intent.getStringExtra(EXTRA_SCREEN) ?: Screen.SECURITY.name)
        }.getOrDefault(Screen.SECURITY)
        if (screen == Screen.CREATE && PendingSignupStore.read(this) != null) {
            screen = Screen.VERIFY_EMAIL
        }
        findViewById<TextView>(R.id.account_back).setOnClickListener { finish() }
        bind(screen)
        if (screen == Screen.GOOGLE && savedInstanceState == null) launchGoogle()
        intent.getStringExtra(EXTRA_AUTH_REASON)?.let { reason ->
            findViewById<TextView>(R.id.account_status).text = explain(reason, diagnostic = reason)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(resendTick)
        super.onDestroy()
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
        listOf(f1, f2, f3, f4).forEach { it.visibility = View.GONE; it.text = null; it.isEnabled = true }
        f2.filters = emptyArray()
        f2.setOnEditorActionListener(null)
        f2.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED
        f2.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        f2.isSaveEnabled = false
        f2.textSize = 16f
        f2.letterSpacing = 0f
        f2.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        primary.isEnabled = true
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
        handler.removeCallbacks(resendTick)
        secondary.isEnabled = true

        when (next) {
            Screen.GOOGLE -> {
                title.setText(R.string.login_continue_with_google)
                body.setText(R.string.account_google_body)
                primary.setText(R.string.login_continue_with_google)
                primary.setOnClickListener { launchGoogle() }
            }
            Screen.EMAIL_OTP -> {
                title.setText(R.string.account_otp_title)
                val email = otpPrefs.getString("email", null)
                body.setText(if (email == null) R.string.account_otp_request_body else R.string.account_otp_verify_body)
                show(f1, R.string.account_field_email)
                f1.setText(email)
                f1.isEnabled = email == null
                if (email == null) {
                    primary.setText(R.string.account_otp_send)
                    primary.setOnClickListener { requestOtp(f1.text.toString().trim(), status) }
                    if (otpPrefs.getLong("retry", 0L) > System.currentTimeMillis()) paintOtpCooldown()
                } else {
                    show(f2, R.string.account_otp_code)
                    f2.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    f2.filters = arrayOf(android.text.InputFilter.LengthFilter(6))
                    f2.gravity = android.view.Gravity.CENTER
                    f2.textSize = 28f
                    f2.letterSpacing = 0.3f
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        f2.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                        f2.setAutofillHints("smsOTPCode")
                    }
                    f2.isSaveEnabled = false
                    f2.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    f2.setOnEditorActionListener { _, action, _ ->
                        if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                            verifyOtp(email, f2.text.toString(), status)
                            true
                        } else {
                            false
                        }
                    }
                    f2.requestFocus()
                    primary.setText(R.string.account_otp_verify)
                    primary.setOnClickListener { verifyOtp(email, f2.text.toString(), status) }
                    secondary.visibility = View.VISIBLE
                    secondary.setOnClickListener { requestOtp(email, status) }
                    tertiary.visibility = View.VISIBLE
                    tertiary.setText(R.string.account_otp_change)
                    tertiary.setOnClickListener {
                        if (!authBusy) {
                            otpPrefs.edit().remove("email").apply()
                            bind(Screen.EMAIL_OTP)
                        }
                    }
                    paintOtpCooldown()
                }
            }
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
                show(f2, R.string.account_field_password)
                val pendingEmail = PendingSignupStore.read(this)?.email
                f1.setText(pendingEmail ?: intent.getStringExtra(EXTRA_EMAIL).orEmpty())
                primary.setText(R.string.account_action_finish_signin)
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.account_action_resend)
                primary.setOnClickListener { signIn(f1, f2, status) }
                secondary.setOnClickListener { resend(f1, status) }
                paintResendCooldown()
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
                if (canonicalRecovery) {
                    secondary.visibility = View.GONE
                    confirm.visibility = View.GONE
                }
                primary.setOnClickListener { restore(f1, f2, status) }
            }
            Screen.SECURE_IDENTITY -> {
                if (!AccountActivation.mayOpenSecureIdentity(
                        emailVerified = CloudSessionStore.readEmailVerified(this),
                        hasLegacyProfile = UserPrefs.hasProfile(this),
                        hasMeshIdentity = UserPrefs.readIdentity(this) != null
                    )
                ) {
                    bind(Screen.VERIFY_EMAIL)
                    return
                }
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
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.account_recovery_later)
                secondary.setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setMessage(R.string.account_recovery_defer_warning)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.account_recovery_continue) { _, _ -> goHome() }
                        .show()
                }
            }
            Screen.LEGACY_RECONCILE -> {
                title.setText(R.string.account_legacy_reconcile_title)
                body.setText(
                    when (legacyReconcileMessage) {
                        LegacyReconciliation.Decision.RESET_PRECLUDES_LEGACY_RESTORE ->
                            R.string.account_legacy_reconcile_reset
                        LegacyReconciliation.Decision.CONFLICT ->
                            R.string.account_legacy_reconcile_conflict
                        else -> R.string.account_legacy_reconcile_keep_local
                    }
                )
                primary.setText(R.string.account_action_sign_in)
                primary.setOnClickListener { goHome() }
            }
            Screen.ROTATE_RECOVERY -> {
                title.setText(R.string.account_rotate_recovery_title)
                val staged = RecoveryRotationStage.read(this)
                body.text = if (staged != null) {
                    getString(R.string.account_rotate_resume_body)
                } else {
                    getString(R.string.account_rotate_recovery_body)
                }
                if (!RecentReauth.isFresh(this) && staged == null) {
                    status.setText(R.string.account_reauth_required)
                    primary.setText(R.string.account_action_sign_in)
                    primary.setOnClickListener { bind(Screen.SIGN_IN) }
                } else if (staged != null) {
                    pendingPhrase = staged.phrase
                    RecoveryRotationStage.sealedFromStaged(staged)?.let { sealed ->
                        pendingRecord = InstallRecord(
                            accountId = staged.accountId,
                            mesh = UserPrefs.readIdentity(this)?.let { mesh ->
                                MeshMaterial.from(mesh, UserPrefs.readProfile(this)?.userId ?: staged.accountId)
                            },
                            recoverySealed = sealed,
                            provider = UserPrefs.readProfile(this)?.authProvider ?: UserPrefs.PROVIDER_EMAIL
                        )
                    }
                    primary.setText(R.string.account_rotate_resume_upload)
                    primary.setOnClickListener { bind(Screen.SAVE_RECOVERY) }
                    secondary.visibility = View.VISIBLE
                    secondary.setText(R.string.account_rotate_discard_stage)
                    secondary.setOnClickListener {
                        RecoveryRotationStage.clear(this)
                        bind(Screen.ROTATE_RECOVERY)
                    }
                } else {
                    primary.setText(R.string.account_rotate_recovery_action)
                    primary.setOnClickListener { rotateRecovery(status) }
                }
            }
            Screen.DEVICES -> {
                title.setText(R.string.account_devices_title)
                val finger = UserPrefs.readIdentity(this)?.fingerprint
                body.text = if (finger.isNullOrBlank()) {
                    getString(R.string.account_devices_body)
                } else {
                    getString(R.string.account_devices_body) + "\n\n" +
                        getString(R.string.account_devices_current, finger)
                }
                show(f1, R.string.account_field_enroll)
                confirm.visibility = View.VISIBLE
                confirm.setText(R.string.account_confirm_enroll)
                primary.setText(R.string.account_action_show_qr)
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.account_action_approve)
                tertiary.visibility = View.VISIBLE
                tertiary.setText(R.string.account_action_accept)
                primary.setOnClickListener { showEnrollment(qr, secret, confirm.isChecked, status) }
                secondary.setOnClickListener { approveEnrollment(f1, confirm.isChecked, status) }
                tertiary.setOnClickListener { acceptEnrollment(f1, confirm.isChecked, status) }
                show(f2, R.string.account_field_revoke_device)
                refreshCloudDevices(status)
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

    private fun launchGoogle() {
        if (authBusy) return
        val googleIntent = googleAuth.silentSignInIntent()
        if (googleIntent == null) {
            findViewById<TextView>(R.id.account_status).setText(R.string.login_missing_oauth)
            return
        }
        startActivityForResult(googleIntent, 7001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7001 || screen != Screen.GOOGLE || resultCode != android.app.Activity.RESULT_OK) return
        val status = findViewById<TextView>(R.id.account_status)
        val token = googleAuth.idTokenFromIntent(data).getOrNull()
        if (token == null) {
            status.setText(R.string.account_google_failed)
            return
        }
        runVerification(status) {
            AuthBackend.signInWithGoogleIdToken(http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, token)
        }
    }

    private fun requestOtp(email: String, status: TextView) {
        if (authBusy || otpPrefs.getLong("retry", 0L) > System.currentTimeMillis()) return
        authBusy = true
        status.setText(R.string.account_otp_sending)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AuthBackend.requestEmailOtp(http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, email)
            }
            authBusy = false
            when (result) {
                is AuthCall.Ok -> {
                    otpPrefs.edit().putString("email", email)
                        .putLong("retry", System.currentTimeMillis() + 60_000L).apply()
                    bind(Screen.EMAIL_OTP)
                }
                is AuthCall.Failed -> {
                    if (result.reason == "rate_limited") {
                        val delay = (result.retryAfterSeconds ?: 60L).coerceIn(1L, 3600L)
                        otpPrefs.edit().putLong("retry", System.currentTimeMillis() + delay * 1000L).apply()
                        paintOtpCooldown()
                    }
                    status.text = explain(result.reason, result.retryAfterSeconds, result.diagnostic)
                }
                else -> status.setText(R.string.account_google_failed)
            }
        }
    }

    private fun paintOtpCooldown() {
        if (screen != Screen.EMAIL_OTP) return
        val requesting = otpPrefs.getString("email", null) == null
        val button = findViewById<TextView>(if (requesting) R.id.account_primary else R.id.account_secondary)
        val left = ((otpPrefs.getLong("retry", 0L) - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(0L)
        button.isEnabled = left == 0L
        if (left > 0L) {
            button.text = getString(R.string.account_resend_wait, left.toInt())
            handler.removeCallbacks(resendTick)
            handler.postDelayed(resendTick, 1000L)
        } else {
            button.setText(if (requesting) R.string.account_otp_send else R.string.account_otp_resend)
        }
    }

    private fun verifyOtp(email: String, code: String, status: TextView) {
        val waitMs = otpPrefs.getLong("verify_retry", 0L) - System.currentTimeMillis()
        if (waitMs > 0L) {
            status.text = explain("rate_limited", (waitMs + 999L) / 1000L)
            return
        }
        runVerification(status) {
            AuthBackend.verifyEmailOtp(http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, email, code)
        }
    }

    private fun runVerification(status: TextView, verify: () -> AuthCall) {
        if (authBusy) return
        authBusy = true
        status.setText(R.string.account_otp_checking)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { verify() }
            authBusy = false
            when (result) {
                is AuthCall.Ok -> {
                    val tokens = if (
                        screen == Screen.GOOGLE &&
                        result.tokens.accessToken.isNotBlank() &&
                        result.tokens.accountId.isNotBlank()
                    ) {
                        // The ID-token grant is the verification. A local Google profile is not.
                        result.tokens.copy(emailVerified = true)
                    } else {
                        result.tokens
                    }
                    if (!tokens.emailVerified) {
                        status.setText(R.string.account_error_email_unverified)
                        return@launch
                    }
                    otpPrefs.edit().remove("email").apply()
                    applyLogin(AuthCall.Ok(tokens), status)
                }
                is AuthCall.Failed -> {
                    if (screen == Screen.EMAIL_OTP && result.reason == "rate_limited") {
                        val wait = (result.retryAfterSeconds ?: 60L).coerceIn(1L, 3600L)
                        otpPrefs.edit().putLong("verify_retry", System.currentTimeMillis() + wait * 1000L).apply()
                    }
                    status.text = explain(result.reason, result.retryAfterSeconds, result.diagnostic)
                }
                else -> status.setText(R.string.account_error_email_unverified)
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
        val pkce = AuthRedirect.generatePkce(random)
        PendingSignupStore.save(this, PendingSignup(mail, "", pkce.verifier))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AuthBackend.signUp(
                    http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY,
                    mail, pass, mapOf("handle" to h, "display_name" to name), pkce.challenge
                )
            }
            when (result) {
                is AuthCall.Failed -> {
                    if (result.reason == "rate_limited") {
                        armResendCooldown(result.retryAfterSeconds)
                        bind(Screen.VERIFY_EMAIL)
                    } else {
                        PendingSignupStore.clear(this@AccountFlowActivity)
                    }
                    if (result.reason != "cloud_auth_not_configured" && result.reason != "invalid_credentials_shape" &&
                        result.reason != "rate_limited"
                    ) {
                        limiter.recordFailure(System.currentTimeMillis())
                    }
                    status.text = explain(result.reason, result.retryAfterSeconds, result.diagnostic)
                }
                is AuthCall.PendingVerification -> {
                    PendingSignupStore.save(
                        this@AccountFlowActivity,
                        PendingSignup(mail, result.accountId, pkce.verifier)
                    )
                    intent.putExtra(EXTRA_EMAIL, mail)
                    intent.putExtra(EXTRA_ACCOUNT_ID, result.accountId)
                    UserPrefs.setIdentityHeld(this@AccountFlowActivity, true)
                    bind(Screen.VERIFY_EMAIL)
                }
                is AuthCall.Ok -> {
                    intent.putExtra(EXTRA_EMAIL, mail)
                    intent.putExtra(EXTRA_ACCOUNT_ID, result.tokens.accountId)
                    rememberCloud(result.tokens.accountId, result.tokens.accessToken, result.tokens.expiresInSeconds, result.tokens.emailVerified, result.tokens.refreshToken)
                    UserPrefs.setIdentityHeld(this@AccountFlowActivity, true)
                    if (result.tokens.emailVerified) {
                        PendingSignupStore.clear(this@AccountFlowActivity)
                        bind(Screen.SECURE_IDENTITY)
                    } else {
                        PendingSignupStore.save(
                            this@AccountFlowActivity,
                            PendingSignup(mail, result.tokens.accountId, pkce.verifier)
                        )
                        bind(Screen.VERIFY_EMAIL)
                    }
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
                    } else if (result.reason == "rate_limited") {
                        armResendCooldown(result.retryAfterSeconds)
                        status.text = explain(result.reason, result.retryAfterSeconds, result.diagnostic)
                    } else if (result.reason == "email_unverified") {
                        intent.putExtra(EXTRA_EMAIL, mail)
                        status.setText(R.string.account_verify_body)
                        if (screen != Screen.VERIFY_EMAIL) bind(Screen.VERIFY_EMAIL)
                    } else {
                        if (result.reason != "cloud_auth_not_configured") limiter.recordFailure(System.currentTimeMillis())
                        status.text = explain(result.reason, diagnostic = result.diagnostic)
                    }
                }
                is AuthCall.PendingVerification -> status.setText(R.string.account_verify_body)
                is AuthCall.Ok -> applyLogin(result, status)
            }
        }
    }

    private fun applyLogin(result: AuthCall.Ok, status: TextView) {
        if (!result.tokens.emailVerified) {
            intent.putExtra(EXTRA_EMAIL, PendingSignupStore.read(this)?.email.orEmpty())
            status.setText(R.string.account_verify_body)
            bind(Screen.VERIFY_EMAIL)
            return
        }
        val canonicalAccount = UserPrefs.canonicalMeshAccount(this)
        val boundAccount = canonicalAccount ?: CloudSessionStore.readAccountId(this)
        if (boundAccount != null && boundAccount != result.tokens.accountId &&
            (canonicalAccount != null || UserPrefs.readIdentity(this) != null)
        ) {
            status.setText(R.string.account_error_conflict)
            return
        }
        val mesh = UserPrefs.readIdentity(this)?.let {
            val profile = UserPrefs.readProfile(this)
            MeshMaterial.from(it, profile?.userId ?: "user")
        }
        val now = System.currentTimeMillis()
        val expiresAt = now + result.tokens.expiresInSeconds * 1000L
        val provider = if (screen == Screen.GOOGLE) UserPrefs.PROVIDER_GOOGLE else UserPrefs.PROVIDER_EMAIL
        RecentReauth.mark(this)
        val login = IdentityLifecycle.cloudLogin(
            record = InstallRecord(mesh = mesh, provider = provider),
            accountId = result.tokens.accountId,
            serverFingerprint = null,
            email = null,
            emailVerified = result.tokens.emailVerified,
            accessToken = result.tokens.accessToken,
            expiresAtMs = expiresAt,
            now = now
        )
        CloudSessionStore.write(
            this,
            login.record.copy(refreshToken = result.tokens.refreshToken.ifBlank { null })
        )
        when (login.bind) {
            com.tharmesh.account.AccountSession.Bind.KEEP_LOCAL_IDENTITY -> {
                UserPrefs.setIdentityHeld(this, false)
                PendingSignupStore.clear(this)
                status.setText(R.string.account_signin_body)
                goHome()
            }
            com.tharmesh.account.AccountSession.Bind.NEEDS_RECOVERY_OR_NEW_IDENTITY -> {
                UserPrefs.setIdentityHeld(this, true)
                intent.putExtra(EXTRA_ACCOUNT_ID, result.tokens.accountId)
                provisionIdentity(result, status)
            }
            com.tharmesh.account.AccountSession.Bind.REFUSE_SILENT_REPLACEMENT -> {
                status.setText(R.string.account_error_conflict)
            }
        }
    }

    private fun provisionIdentity(auth: AuthCall.Ok, status: TextView) {
        if (authBusy) return
        authBusy = true
        val button = findViewById<TextView>(R.id.account_primary)
        button.isEnabled = false
        status.setText(R.string.account_provisioning)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                IdentityProvisioning(http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, auth.tokens)
                    .activate { allowCreate ->
                        AndroidAccountKeyBox.stageMeshIdentity(this@AccountFlowActivity, auth.tokens.accountId, allowCreate)
                    }
            }
            authBusy = false
            button.isEnabled = true
            when (outcome) {
                is IdentityProvisioning.Result.Activated -> {
                    val userId = com.tharmesh.account.CanonicalAccount.meshUserIdForNewAccount(auth.tokens.accountId, null)
                    if (userId == null || !UserPrefs.activateCanonicalIdentity(
                            this@AccountFlowActivity, auth.tokens.accountId, userId, outcome.identity.publicKeyBase64
                        )
                    ) {
                        status.setText(R.string.account_error_conflict)
                        return@launch
                    }
                    PendingSignupStore.clear(this@AccountFlowActivity)
                    goHome()
                }
                is IdentityProvisioning.Result.RecoveryRequired -> {
                    canonicalRecovery = true
                    expectedRecoveryKey = outcome.canonicalKey
                    val local = UserPrefs.readIdentity(this@AccountFlowActivity)
                    val evidence = withContext(Dispatchers.IO) {
                        val access = CloudSessionStore.readAccessToken(this@AccountFlowActivity)
                        if (access.isNullOrBlank()) null
                        else DeviceDirectory.fetchEvidence(
                            http,
                            BuildConfig.SUPABASE_URL,
                            BuildConfig.SUPABASE_ANON_KEY,
                            access
                        )
                    }
                    val serverState = evidence?.identityState
                        ?: if (outcome.canonicalKey == null) "legacy_unknown" else "recovery_required"
                    legacyReconcileMessage = LegacyReconciliation.decide(
                        LegacyReconciliation.Evidence(
                            serverState = serverState,
                            canonicalMeshPublicKey = outcome.canonicalKey ?: evidence?.meshPublicKey,
                            localMesh = local,
                            explicitResetCommitted = false,
                            cloudDeviceRows = evidence?.deviceCount ?: 0,
                            cloudRecoveryBlob = evidence?.hasRecoveryBlob ?: false
                        )
                    )
                    when (legacyReconcileMessage) {
                        LegacyReconciliation.Decision.KEEP_LOCAL_AND_USE_CLOUD_SESSION,
                        LegacyReconciliation.Decision.KEYS_ALREADY_ALIGNED -> {
                            PendingSignupStore.clear(this@AccountFlowActivity)
                            goHome()
                        }
                        LegacyReconciliation.Decision.RESET_PRECLUDES_LEGACY_RESTORE,
                        LegacyReconciliation.Decision.CONFLICT ->
                            bind(Screen.LEGACY_RECONCILE)
                        else -> bind(Screen.RECOVER)
                    }
                }
                IdentityProvisioning.Result.Retry -> {
                    status.setText(R.string.account_provisioning_retry)
                    button.setText(R.string.account_action_provisioning_retry)
                    button.setOnClickListener { provisionIdentity(auth, status) }
                }
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
        if (authBusy) return
        if (UserPrefs.readIdentity(this) != null) {
            status.setText(R.string.account_error_conflict)
            return
        }
        authBusy = true
        lifecycleScope.launch {
            val built = withContext(Dispatchers.Default) {
                val meshKey = CryptoIdentity.generate()
                val accountKey = CryptoIdentity.generate()
                val phrase = IdentityRecovery.generatePhrase(random)
                val userId = com.tharmesh.account.CanonicalAccount.meshUserIdForNewAccount(
                    intent.getStringExtra(EXTRA_ACCOUNT_ID),
                    UserPrefs.readProfile(this@AccountFlowActivity)?.userId
                )
                if (userId == null) {
                    return@withContext Triple(
                        meshKey,
                        phrase,
                        IdentityLifecycle.EstablishResult.Refused(InstallRecord()) as IdentityLifecycle.EstablishResult
                    )
                }
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
            authBusy = false
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
            PendingSignupStore.clear(this@AccountFlowActivity)
            finishSeal(created.record, phrase, status)
        }
    }

    private fun resetIdentity(status: TextView) {
        lifecycleScope.launch {
            val built = withContext(Dispatchers.Default) {
                val meshKey = CryptoIdentity.generate()
                val accountKey = CryptoIdentity.generate()
                val phrase = IdentityRecovery.generatePhrase(random)
                val userId = com.tharmesh.account.CanonicalAccount.meshUserIdForNewAccount(
                    intent.getStringExtra(EXTRA_ACCOUNT_ID),
                    UserPrefs.readProfile(this@AccountFlowActivity)?.userId
                ) ?: return@withContext Triple(
                    meshKey,
                    phrase,
                    IdentityLifecycle.EstablishResult.Refused(InstallRecord()) as IdentityLifecycle.EstablishResult
                )
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
                if (canonicalRecovery && expectedRecoveryKey != mesh.publicKeyBase64) {
                    status.setText(R.string.account_error_conflict)
                    return
                }
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
        CloudSessionStore.completeIdentity(this, record)
        TharMeshApp.get().ensureMeshReady()
        bind(Screen.SAVE_RECOVERY)
    }

    private fun uploadRecovery(status: TextView) {
        val record = pendingRecord
        val sealed = record?.recoverySealed
        val accountId = record?.accountId
        val access = CloudSessionStore.readAccessToken(this)
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
            if (result is AuthCall.Ok) {
                RecoveryRotationStage.clear(this@AccountFlowActivity)
                status.setText(R.string.account_uploaded)
            } else {
                status.setText(R.string.account_rotate_upload_pending)
            }
        }
    }

    private fun showEnrollment(qr: ImageView, secret: TextView, confirmed: Boolean, status: TextView) {
        if (!confirmed) {
            status.setText(R.string.account_error_approve)
            return
        }
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
        pendingEnrollment = request
        val text = EnrollmentQr.encodeRequest(request)
        secret.visibility = View.VISIBLE
        secret.text = text
        qr.visibility = View.VISIBLE
        qr.setImageBitmap(encodeQr(text, 512))
        status.text = identity.fingerprint
    }

    private fun acceptEnrollment(field: EditText, confirmed: Boolean, status: TextView) {
        if (!confirmed) {
            status.setText(R.string.account_error_approve)
            return
        }
        val request = pendingEnrollment
        val cert = EnrollmentQr.decodeCertificate(field.text.toString().trim())
        val accountPublic = pendingRecord?.account?.publicKeyBase64
            ?: CloudSessionStore.readAccountPublic(this)
        if (request == null || cert == null || accountPublic == null) {
            status.setText(R.string.account_error_approve)
            return
        }
        if (!EnrollmentReplayGuard.markConsumed(this, cert.accountId, cert.nonceB64)) {
            status.setText(R.string.account_error_enroll_replay)
            return
        }
        val ok = DeviceEnrollment.accept(cert, accountPublic, request)
        status.setText(if (ok) R.string.account_enroll_accepted else R.string.account_error_approve)
    }

    private fun refreshCloudDevices(status: TextView) {
        val access = CloudSessionStore.readAccessToken(this)
        val body = findViewById<TextView>(R.id.account_body)
        val finger = UserPrefs.readIdentity(this)?.fingerprint
        val prefix = if (finger.isNullOrBlank()) getString(R.string.account_devices_body)
        else getString(R.string.account_devices_body) + "\n\n" + getString(R.string.account_devices_current, finger)
        if (access.isNullOrBlank() || !AuthBackend.configured(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)) {
            body.text = prefix + "\n\n" + getString(R.string.account_devices_list_offline)
            return
        }
        val secondary = findViewById<TextView>(R.id.account_secondary)
        val f2 = field(R.id.account_field_2)
        secondary.visibility = View.VISIBLE
        secondary.setText(R.string.account_action_revoke)
        secondary.setOnClickListener { revokeCloudDevice(f2, status) }
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                DeviceDirectory.list(http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, access)
            }
            cloudDevices = rows ?: emptyList()
            localDeviceId = UserPrefs.readIdentity(this@AccountFlowActivity)?.fingerprint?.take(16)
            val listing = if (cloudDevices.isEmpty()) getString(R.string.account_devices_list_empty)
            else cloudDevices.joinToString("\n") { row ->
                val mark = if (row.revoked) getString(R.string.account_devices_status_revoked)
                else getString(R.string.account_devices_status_active)
                val current = if (row.deviceId == localDeviceId) " " + getString(R.string.account_devices_label_current) else ""
                "• ${row.deviceId} — $mark$current"
            }
            body.text = prefix + "\n\n" + listing + "\n\n" + getString(R.string.account_devices_lost_hint)
        }
    }

    private fun revokeCloudDevice(field: EditText, status: TextView) {
        val id = field.text.toString().trim()
        if (id.isEmpty()) {
            status.setText(R.string.account_error_revoke_device)
            return
        }
        val access = CloudSessionStore.readAccessToken(this) ?: run {
            status.setText(R.string.account_error_not_configured)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                DeviceDirectory.revoke(
                    http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, access, id
                )
            }
            status.setText(
                if (result is AuthCall.Ok) R.string.account_devices_revoked else R.string.account_error_revoke_device
            )
            refreshCloudDevices(status)
        }
    }

    private fun rotateRecovery(status: TextView) {
        if (authBusy) return
        if (!RecentReauth.isFresh(this)) {
            status.setText(R.string.account_reauth_required)
            return
        }
        val mesh = UserPrefs.readIdentity(this) ?: run {
            status.setText(R.string.account_error_held)
            return
        }
        val accountId = CloudSessionStore.readAccountId(this) ?: UserPrefs.readProfile(this)?.userId
        if (accountId.isNullOrBlank()) {
            status.setText(R.string.account_error_not_configured)
            return
        }
        authBusy = true
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                RecoveryRotation.rotate(mesh.privateKeyBase64, mesh.publicKeyBase64, random)
            }
            pendingPhrase = outcome.phrase
            RecoveryRotationStage.save(this@AccountFlowActivity, outcome.phrase, outcome.sealed, accountId)
            pendingRecord = InstallRecord(
                accountId = accountId,
                mesh = MeshMaterial.from(
                    mesh,
                    UserPrefs.readProfile(this@AccountFlowActivity)?.userId ?: accountId
                ),
                recoverySealed = outcome.sealed,
                provider = UserPrefs.readProfile(this@AccountFlowActivity)?.authProvider
                    ?: UserPrefs.PROVIDER_EMAIL
            )
            authBusy = false
            status.setText(R.string.account_rotate_recovery_done)
            bind(Screen.SAVE_RECOVERY)
        }
    }

    private fun approveEnrollment(field: EditText, userApproved: Boolean, status: TextView) {
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
            userApproved = userApproved
        )
        if (cert == null) {
            status.setText(R.string.account_error_approve)
            return
        }
        status.text = EnrollmentQr.encodeCertificate(cert)
    }

    private fun resend(email: EditText, status: TextView) {
        if (System.currentTimeMillis() < resendBlockedUntilMs) {
            paintResendCooldown()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AuthBackend.resendVerification(
                    http, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, email.text.toString()
                )
            }
            when (result) {
                is AuthCall.Failed -> {
                    if (result.reason == "rate_limited") armResendCooldown(result.retryAfterSeconds)
                    status.text = explain(result.reason, result.retryAfterSeconds, result.diagnostic)
                }
                else -> {
                    armResendCooldown(60)
                    status.setText(R.string.account_verify_sent)
                }
            }
        }
    }

    private fun armResendCooldown(retryAfterSeconds: Long?) {
        val wait = (retryAfterSeconds ?: 60L).coerceIn(1L, 3600L)
        resendBlockedUntilMs = System.currentTimeMillis() + wait * 1000L
        paintResendCooldown()
    }

    private fun paintResendCooldown() {
        if (screen != Screen.VERIFY_EMAIL) return
        val button = findViewById<TextView>(R.id.account_secondary)
        val left = ((resendBlockedUntilMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        if (left > 0L) {
            button.isEnabled = false
            button.text = getString(R.string.account_resend_wait, left.toInt())
            handler.postDelayed(resendTick, 1000L)
        } else {
            button.isEnabled = true
            button.setText(R.string.account_action_resend)
        }
    }

    private fun rememberCloud(accountId: String, access: String, expiresIn: Long, verified: Boolean, refresh: String = "") {
        val now = System.currentTimeMillis()
        CloudSessionStore.write(
            this,
            InstallRecord(
                accountId = accountId,
                accessToken = access,
                refreshToken = refresh.ifBlank { null },
                tokenExpiresAtMs = now + expiresIn * 1000L,
                emailVerified = verified,
                cloud = if (expiresIn <= 0L) CloudCredential.TOKEN_EXPIRED else CloudCredential.ACTIVE,
                localAuth = LocalAuth.AWAITING_IDENTITY,
                holdMeshGeneration = true
            )
        )
    }

    private fun explain(reason: String, retryAfterSeconds: Long? = null, diagnostic: String = ""): String {
        val message = when (reason) {
            "cloud_auth_not_configured" -> getString(R.string.account_error_not_configured)
            "network" -> getString(R.string.account_error_network)
            "email_delivery_failed" -> getString(R.string.account_error_email_delivery)
            "redirect_not_allowed" -> getString(R.string.account_error_redirect_configuration)
            "server_error" -> getString(R.string.account_error_server)
            "invalid_credentials_shape", "invalid_handle" -> getString(R.string.account_error_invalid)
            "handle_taken", "email_taken" -> getString(R.string.account_error_handle)
            "email_unverified" -> getString(R.string.account_error_email_unverified)
            "invalid_credentials" -> getString(R.string.account_error_invalid_credentials)
            "otp_invalid" -> getString(R.string.account_otp_invalid)
            "otp_expired" -> getString(
                if (screen == Screen.EMAIL_OTP) R.string.account_otp_expired else R.string.account_error_otp_expired
            )
            "rate_limited" -> getString(
                R.string.account_error_email_rate_limited,
                (retryAfterSeconds ?: 60L).toInt()
            )
            "account_exists" -> getString(R.string.account_error_account_exists)
            "already_confirmed" -> getString(R.string.account_error_already_confirmed)
            "weak_password" -> getString(R.string.account_error_weak_password)
            "validation_failed" -> getString(R.string.account_error_validation)
            "pkce_expired", "pkce_verifier_missing" -> getString(R.string.account_error_pkce)
            "email_address_invalid" -> getString(R.string.account_error_email_invalid)
            "signup_disabled" -> getString(R.string.account_error_signup_disabled)
            "callback_rejected" -> getString(R.string.account_error_callback)
            "unauthorized" -> getString(R.string.account_error_unauthorized)
            else -> getString(R.string.account_error_unrecognized)
        }
        val code = diagnostic.ifBlank { reason }
        return getString(R.string.account_error_with_code, message, code)
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
        const val EXTRA_AUTH_REASON = "auth_reason"
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
