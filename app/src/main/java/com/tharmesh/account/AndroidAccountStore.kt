// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tharmesh.identity.Base64Url
import com.tharmesh.identity.CryptoIdentity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Non-exportable enrollment key. Returns null when Android Keystore cannot
 * create one. Callers must not substitute a software key and then claim it
 * is hardware-backed.
 */
object AndroidDeviceKey {
    private const val ALIAS = "tharmesh_device_sign_v1"

    fun loadOrCreate(): DeviceSigner? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(ALIAS)) {
                val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec(CryptoIdentity.CURVE))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
                gen.initialize(spec)
                gen.generateKeyPair()
            }
            val entry = ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry ?: return null
            val publicKey = Base64Url.encode(entry.certificate.publicKey.encoded)
            val nonExportable = entry.privateKey.encoded == null
            object : DeviceSigner {
                override val publicKeyBase64: String = publicKey
                override val hardwareNonExportable: Boolean = nonExportable
                override fun sign(data: ByteArray): String {
                    val sig = Signature.getInstance(CryptoIdentity.SIGNATURE_ALGORITHM)
                    sig.initSign(entry.privateKey)
                    sig.update(data)
                    return Base64Url.encode(sig.sign())
                }
            }
        } catch (ignored: Throwable) {
            null
        }
    }
}

/**
 * Seals the account private key with a Keystore AES-GCM key that never leaves
 * the device. Returns null on failure. Callers must not write the private key
 * into preferences in the clear.
 */
object AndroidAccountKeyBox {
    private const val ALIAS = "tharmesh_account_wrap_v1"
    private const val PREFS = "tharmesh_account_keybox"
    private const val KEY_BLOB = "sealed_account_private"

    fun seal(context: Context, accountPrivateKeyBase64: String): Boolean {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(accountPrivateKeyBase64.toByteArray(Charsets.UTF_8))
            val packed = AccountKeySeal.pack(iv, ct)
            val stored = AccountKeySeal.bytesToStore(true, packed, accountPrivateKeyBase64) ?: return false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BLOB, stored)
                .apply()
            true
        } catch (ignored: Throwable) {
            false
        }
    }

    fun open(context: Context): String? {
        return try {
            val packedB64 = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BLOB, null) ?: return null
            val packed = Base64Url.decode(packedB64) ?: return null
            val (iv, ct) = AccountKeySeal.unpack(packed) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun aesKey(): java.security.Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            gen.init(spec)
            gen.generateKey()
        }
        return ks.getKey(ALIAS, null)
    }
}

/** Cloud tokens only. Mesh keys are not stored in this file. */
object CloudSessionStore {
    const val PREFS: String = "tharmesh_cloud_session"
    private const val STATE = "cloud_state"
    private const val EXPIRES = "token_expires_at"
    private const val ACCOUNT = "account_id"
    private const val LOCAL = "local_auth"
    private const val EMAIL_VERIFIED = "email_verified"

    fun write(context: Context, record: InstallRecord) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(STATE, record.cloud.name)
            .putString(LOCAL, record.localAuth.name)
            .putString(ACCOUNT, record.accountId)
            .putBoolean(EMAIL_VERIFIED, record.emailVerified)
            .putString("account_public", record.account?.publicKeyBase64)
        if (record.tokenExpiresAtMs != null) {
            editor.putLong(EXPIRES, record.tokenExpiresAtMs)
        } else {
            editor.remove(EXPIRES)
        }
        if (record.accessToken.isNullOrBlank()) {
            editor.remove("access_token")
        } else {
            editor.putString("access_token", record.accessToken)
        }
        if (record.refreshToken.isNullOrBlank()) {
            editor.remove("refresh_token")
        } else {
            editor.putString("refresh_token", record.refreshToken)
        }
        editor.apply()
    }

    fun readAccountId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACCOUNT, null)

    fun completeIdentity(context: Context, record: InstallRecord) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(LOCAL, record.localAuth.name)
            .putString("account_public", record.account?.publicKeyBase64)
            .apply()
    }

    fun readRefreshToken(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("refresh_token", "")
            .orEmpty()
    }

    fun readAccessToken(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("access_token", "")
            .orEmpty()
    }

    fun readEmailVerified(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(EMAIL_VERIFIED, false)
    }

    fun readCloud(context: Context): CloudCredential {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(STATE, null)
        return raw?.let { runCatching { CloudCredential.valueOf(it) }.getOrNull() } ?: CloudCredential.NONE
    }

    fun readAccountPublic(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("account_public", null)
    }

    fun readLocal(context: Context): LocalAuth {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LOCAL, null)
        return raw?.let { runCatching { LocalAuth.valueOf(it) }.getOrNull() } ?: LocalAuth.ABSENT
    }

    fun writeRecovery(context: Context, uploadJson: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("recovery_upload", uploadJson)
            .apply()
    }

    fun readRecovery(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("recovery_upload", null)
    }

    fun clearTokens(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove(EXPIRES)
            .putString(STATE, CloudCredential.SIGNED_OUT.name)
            .apply()
    }
}
