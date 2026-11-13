// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.account

import android.content.Context

/**
 * Local staged recovery rotation. Survives interruption until [clear] after
 * confirmed cloud upload.
 */
object RecoveryRotationStage {
    private const val PREFS = "tharmesh_recovery_rotation_stage"

    data class Staged(
        val phrase: String,
        val sealedJson: String,
        val accountId: String,
        val savedAtMs: Long,
        val expectedGeneration: Int
    )

    fun save(
        context: Context,
        phrase: String,
        sealed: IdentityRecovery.SealedIdentity,
        accountId: String,
        expectedGeneration: Int
    ) {
        val json = org.json.JSONObject()
            .put("kdf", sealed.kdf)
            .put("n", sealed.iterations)
            .put("r", sealed.r)
            .put("p", sealed.p)
            .put("salt_b64", sealed.saltB64)
            .put("iv_b64", sealed.ivB64)
            .put("ciphertext_b64", sealed.ciphertextB64)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("phrase", phrase)
            .putString("sealed", json.toString())
            .putString("account", accountId)
            .putLong("at", System.currentTimeMillis())
            .putInt("gen", expectedGeneration)
            .apply()
    }

    fun read(context: Context): Staged? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val phrase = prefs.getString("phrase", null) ?: return null
        val sealed = prefs.getString("sealed", null) ?: return null
        val account = prefs.getString("account", null) ?: return null
        return Staged(phrase, sealed, account, prefs.getLong("at", 0L), prefs.getInt("gen", 0))
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun sealedFromStaged(staged: Staged): IdentityRecovery.SealedIdentity? {
        return try {
            val o = org.json.JSONObject(staged.sealedJson)
            IdentityRecovery.SealedIdentity(
                kdf = o.getString("kdf"),
                iterations = o.getInt("n"),
                r = o.getInt("r"),
                p = o.getInt("p"),
                saltB64 = o.getString("salt_b64"),
                ivB64 = o.getString("iv_b64"),
                ciphertextB64 = o.getString("ciphertext_b64")
            )
        } catch (_: Throwable) {
            null
        }
    }
}
