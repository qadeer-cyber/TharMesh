// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.data

import android.content.Context
import com.tharmesh.crypto.CryptoBox
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.ui.theme.ThemeManager
import java.util.UUID

data class UserProfile(
    val userId: String,
    val username: String,
    val email: String? = null,
    val avatarUrl: String? = null,
    val authProvider: String = "anonymous"
)

object UserPrefs {

    private const val PREFS_NAME = "tharmesh_user_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"
    private const val KEY_EMAIL = "email"
    private const val KEY_AVATAR = "avatar_url"
    private const val KEY_PROVIDER = "auth_provider"
    private const val KEY_AUTHENTICATED = "authenticated"
    private const val KEY_PRIVATE_KEY = "identity_private_key_b64"
    private const val KEY_PUBLIC_KEY = "identity_public_key_b64"
    private const val KEY_FINGERPRINT = "identity_fingerprint"

    /**
     * Stage 6.1 — persisted Theme Mode (System / Light / Dark). Stored as the
     * stable enum name string. Default is SYSTEM so a fresh install follows
     * the OS dark-mode setting, which on most devices means the user sees the
     * same dark UI they had before this PR until they explicitly opt in to
     * light mode.
     */
    private const val KEY_THEME_MODE = "theme_mode"

    /**
     * Stage 6.3 — manual Disaster Mode toggle. When true:
     *   - every outgoing bundle is sent with `priority = true` so the engine
     *     bypasses [com.tharmesh.dtn.PerPeerSendPacer] and the retry loop
     *     applies [com.tharmesh.dtn.RetryConfig.SOS]
     *   - incoming SOS-prefixed bundles vibrate + ring on the device
     *   - the persistent red banner in MainActivity is shown
     * Never auto-enabled — the user explicitly opts in via Settings.
     */
    private const val KEY_DISASTER_MODE = "disaster_mode"

    /**
     * Profile rebuild — WhatsApp-style status / tagline shown under the
     * display name on the Profile screen. Free-form text, capped at 140
     * chars at the UI layer. Empty default — user explicitly opts in by
     * editing the field.
     */
    private const val KEY_STATUS = "profile_status"

    /**
     * Profile rebuild — local URI of the user's avatar image. The picker
     * copies the chosen image into the app's internal files dir so we own
     * a stable file:// path independent of the original picker URI's
     * permission grant. Empty / null means "use the letter-mark placeholder".
     */
    private const val KEY_AVATAR_LOCAL = "profile_avatar_local"

    /**
     * Profile rebuild — message-sound + vibration toggles surfaced in the
     * Notifications settings section. Both default to ON to match the
     * pre-existing (always-on) behaviour. Disaster Mode's vibrate + ring
     * is independently gated and is NOT silenced by these toggles — the
     * user explicitly opted into the disaster alert by enabling the
     * mode, so we honour that intent over the global toggles.
     */
    private const val KEY_NOTIF_SOUND = "notif_sound"
    private const val KEY_NOTIF_VIBRATE = "notif_vibrate"

    /**
     * Stage 7 PR C — first-run onboarding flag. Set to `true` by
     * [com.tharmesh.ui.onboarding.OnboardingActivity] after the user has
     * completed (or skipped) the name + mesh + first-contact steps.
     * [com.tharmesh.ui.auth.LoginActivity] checks this flag to decide
     * whether to route to onboarding or straight to MainActivity, so
     * existing installs (which never had onboarding) skip it on
     * upgrade.
     */
    private const val KEY_ONBOARDED = "onboarded"

    const val PROVIDER_ANONYMOUS = "anonymous"
    const val PROVIDER_GOOGLE = "google"

    @JvmStatic
    fun hasProfile(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTHENTICATED, false)
    }

    @JvmStatic
    fun ensureProfile(context: Context): UserProfile {
        val existing = readProfile(context)
        if (existing != null) return existing
        val newUserId = "user-" + UUID.randomUUID().toString().take(8)
        return saveProfile(
            context,
            UserProfile(
                userId = newUserId,
                username = newUserId,
                authProvider = PROVIDER_ANONYMOUS
            )
        )
    }

    @JvmStatic
    fun saveProfile(context: Context, profile: UserProfile): UserProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_USER_ID, profile.userId)
            .putString(KEY_USERNAME, profile.username)
            .putString(KEY_EMAIL, profile.email)
            .putString(KEY_AVATAR, profile.avatarUrl)
            .putString(KEY_PROVIDER, profile.authProvider)
            .putBoolean(KEY_AUTHENTICATED, true)
            .apply()
        return profile
    }

    @JvmStatic
    fun readProfile(context: Context): UserProfile? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString(KEY_USER_ID, null)
        val username = prefs.getString(KEY_USERNAME, null)
        if (userId.isNullOrBlank() || username.isNullOrBlank()) return null
        return UserProfile(
            userId = userId,
            username = username,
            email = prefs.getString(KEY_EMAIL, null),
            avatarUrl = prefs.getString(KEY_AVATAR, null),
            authProvider = prefs.getString(KEY_PROVIDER, PROVIDER_ANONYMOUS) ?: PROVIDER_ANONYMOUS
        )
    }

    /**
     * Stage 7 PR C — true once the user has finished (or skipped)
     * the onboarding flow. Existing installs created their profile
     * before this flag existed — for those we treat the flag as
     * already-set so they aren't pushed back through onboarding on
     * upgrade. See [shouldShowOnboarding].
     */
    @JvmStatic
    fun isOnboarded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ONBOARDED, false)
    }

    @JvmStatic
    fun markOnboarded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    /**
     * Returns `true` only for fresh installs that finished
     * [LoginActivity] but haven't yet completed onboarding. Existing
     * installs that already had a profile before PR C shipped are
     * grandfathered: when we see a profile + identity + a username
     * that was clearly user-chosen (or Google-derived), we mark them
     * onboarded so they don't get bounced through onboarding on the
     * upgrade build.
     */
    @JvmStatic
    fun shouldShowOnboarding(context: Context): Boolean {
        if (isOnboarded(context)) return false
        val profile = readProfile(context) ?: return true
        // Pre-PR-C profiles always have username == userId, since
        // LoginActivity's anonymous path generated `user-<8hex>` and used
        // it for both fields. Google-signed profiles always have a
        // distinct human display name. Treat the former as "needs
        // onboarding"; the latter we grandfather.
        val looksUserChosen = profile.username.isNotBlank() &&
            profile.username != profile.userId
        if (looksUserChosen) {
            // Persist so we don't re-evaluate on every launch.
            markOnboarded(context)
            return false
        }
        return true
    }

    @JvmStatic
    fun signOut(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    /** Stable userId derived from Google's `sub` claim so reinstalls keep the same mesh ID. */
    fun userIdFromGoogleSub(googleSub: String): String {
        val hash = CryptoBox.sha256(googleSub).take(12)
        return "tm-$hash"
    }

    /**
     * Return the device's long-term signing identity, generating + persisting a
     * fresh ECDSA P-256 keypair on first call. Subsequent calls deserialize the
     * stored PKCS#8 / X.509 base64 blobs via [CryptoIdentity.fromBase64].
     *
     * NOTE: the private key is stored in SharedPreferences, NOT Android Keystore.
     * This is a deliberate Stage 4.6 baseline — a later stage should migrate to
     * Keystore (API 23+) for hardware-backed storage. Until then, device
     * compromise == identity compromise. Documented as a Known Limitation.
     */
    @JvmStatic
    @Synchronized
    fun ensureIdentity(context: Context): CryptoIdentity {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val priv = prefs.getString(KEY_PRIVATE_KEY, null)
        val pub = prefs.getString(KEY_PUBLIC_KEY, null)
        if (!priv.isNullOrBlank() && !pub.isNullOrBlank()) {
            val restored = CryptoIdentity.fromBase64(priv, pub)
            if (restored != null) return restored
            // Stored blobs are corrupted — regenerate. Log via println (no Android
            // logger at the UserPrefs layer). This IS a security-relevant event:
            // peers that had pinned our old key via TOFU will now reject us until
            // their state is cleared. Acceptable for the rare corruption case.
            println("UserPrefs.ensureIdentity: stored keypair failed to decode, regenerating")
        }
        val identity = CryptoIdentity.generate()
        prefs.edit()
            .putString(KEY_PRIVATE_KEY, identity.privateKeyBase64)
            .putString(KEY_PUBLIC_KEY, identity.publicKeyBase64)
            .putString(KEY_FINGERPRINT, identity.fingerprint)
            .apply()
        return identity
    }

    /** Read-only — returns null if no identity has been generated yet. */
    fun readIdentity(context: Context): CryptoIdentity? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val priv = prefs.getString(KEY_PRIVATE_KEY, null) ?: return null
        val pub = prefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        return CryptoIdentity.fromBase64(priv, pub)
    }

    /**
     * Stage 6.1 — return the persisted theme mode, defaulting to SYSTEM when
     * the user has never picked a value. Tolerant of missing/garbled values
     * (e.g. a downgrade from a future TharMesh version that adds new modes).
     */
    @JvmStatic
    fun getThemeMode(context: Context): ThemeManager.Mode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_THEME_MODE, null) ?: return ThemeManager.Mode.SYSTEM
        return runCatching { ThemeManager.Mode.valueOf(raw) }.getOrDefault(ThemeManager.Mode.SYSTEM)
    }

    @JvmStatic
    fun setThemeMode(context: Context, mode: ThemeManager.Mode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    @JvmStatic
    fun isDisasterModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DISASTER_MODE, false)
    }

    @JvmStatic
    fun setDisasterModeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DISASTER_MODE, enabled).apply()
    }

    @JvmStatic
    fun getStatus(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_STATUS, "") ?: ""
    }

    @JvmStatic
    fun setStatus(context: Context, status: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_STATUS, status.take(140)).apply()
    }

    @JvmStatic
    fun getAvatarLocalPath(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AVATAR_LOCAL, null)?.takeIf { it.isNotBlank() }
    }

    @JvmStatic
    fun setAvatarLocalPath(context: Context, path: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AVATAR_LOCAL, path).apply()
    }

    @JvmStatic
    fun isNotificationSoundEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_NOTIF_SOUND, true)
    }

    @JvmStatic
    fun setNotificationSoundEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_NOTIF_SOUND, enabled).apply()
    }

    @JvmStatic
    fun isNotificationVibrateEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_NOTIF_VIBRATE, true)
    }

    @JvmStatic
    fun setNotificationVibrateEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_NOTIF_VIBRATE, enabled).apply()
    }
}
