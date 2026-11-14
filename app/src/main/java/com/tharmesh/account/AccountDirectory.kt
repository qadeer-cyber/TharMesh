// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import org.json.JSONObject

object HandlePolicy {
    private val HANDLE = Regex("^[a-z0-9_]{3,20}$")

    fun normalize(raw: String): String? {
        val h = raw.trim().lowercase()
        return if (HANDLE.matches(h)) h else null
    }
}

object AvatarPolicy {
    fun normalize(raw: String?): String? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        return if (v.startsWith("https://")) v else null
    }
}

/**
 * In-memory stand-in for GoTrue + the RLS tables. Every result is marked
 * [SIMULATED]. Production screens do not call this type.
 */
class SimulatedAccountDirectory {
    companion object {
        const val LABEL: String = "SIMULATED"
    }

    data class Row(
        val accountId: String,
        val email: String,
        val password: String,
        val handle: String,
        val displayName: String,
        var emailVerified: Boolean,
        var recoveryJson: String? = null
    )

    sealed class Result {
        abstract val simulated: Boolean
        data class Ok(val row: Row, override val simulated: Boolean = true) : Result()
        data class Failed(val reason: String, override val simulated: Boolean = true) : Result()
    }

    private val byEmail = HashMap<String, Row>()
    private val byHandle = HashMap<String, Row>()
    private var seq = 0

    fun signUp(email: String, password: String, handle: String, displayName: String): Result {
        val mail = email.trim().lowercase()
        val h = HandlePolicy.normalize(handle) ?: return Result.Failed("invalid_handle")
        if (!mail.contains("@") || password.length < 8) return Result.Failed("invalid_credentials_shape")
        if (byEmail.containsKey(mail)) return Result.Failed("email_taken")
        if (byHandle.containsKey(h)) return Result.Failed("handle_taken")
        seq += 1
        val row = Row(
            accountId = "sim-$seq",
            email = mail,
            password = password,
            handle = h,
            displayName = displayName.trim().ifBlank { h },
            emailVerified = false
        )
        byEmail[mail] = row
        byHandle[h] = row
        return Result.Ok(row)
    }

    fun signIn(email: String, password: String): Result {
        val row = byEmail[email.trim().lowercase()] ?: return Result.Failed("invalid_login")
        if (row.password != password) return Result.Failed("invalid_login")
        return Result.Ok(row)
    }

    fun confirmEmail(accountId: String): Result {
        val row = byEmail.values.firstOrNull { it.accountId == accountId } ?: return Result.Failed("missing_account")
        row.emailVerified = true
        return Result.Ok(row)
    }

    fun putRecovery(accountId: String, uploadJson: String): Result {
        val row = byEmail.values.firstOrNull { it.accountId == accountId } ?: return Result.Failed("missing_account")
        if (!row.emailVerified) return Result.Failed("email_unverified")
        val obj = try {
            JSONObject(uploadJson)
        } catch (ignored: Throwable) {
            return Result.Failed("bad_blob")
        }
        if (obj.optString("kdf") != IdentityRecovery.KDF_SCRYPT) return Result.Failed("bad_blob")
        if (obj.has("private_key") || obj.has("privateKey") || obj.has("phrase") || obj.has("meshPrivate")) {
            return Result.Failed("plaintext_rejected")
        }
        if (obj.optString("ciphertext_b64").isBlank()) return Result.Failed("bad_blob")
        row.recoveryJson = uploadJson
        return Result.Ok(row)
    }

    fun recoveryOf(accountId: String): String? {
        return byEmail.values.firstOrNull { it.accountId == accountId }?.recoveryJson
    }
}

/** Local checks that mirror `supabase/schema.sql`. The SQL file is the deployed copy. */
object RlsPolicy {
    fun ownerWrite(authUid: String?, rowAccountId: String): Boolean {
        return !authUid.isNullOrBlank() && authUid == rowAccountId
    }

    fun recoveryAccepts(uploadJson: String): Boolean {
        val obj = try {
            JSONObject(uploadJson)
        } catch (ignored: Throwable) {
            return false
        }
        if (obj.has("private_key") || obj.has("privateKey") || obj.has("phrase") || obj.has("meshPrivate")) {
            return false
        }
        return obj.optString("kdf") == "scrypt" &&
            obj.optInt("n") >= 16_384 &&
            obj.optInt("r") >= 8 &&
            obj.optInt("p") >= 1 &&
            obj.optString("ciphertext_b64").isNotBlank() &&
            obj.optString("salt_b64").isNotBlank()
    }

    fun handleInsert(authUid: String?, accountId: String, handle: String, taken: Boolean): Boolean {
        if (!ownerWrite(authUid, accountId)) return false
        if (HandlePolicy.normalize(handle) == null) return false
        return !taken
    }
}

class AuthRateLimiter(
    private val maxFailures: Int = 8,
    private val windowMs: Long = 15 * 60 * 1000L
) {
    private val failures = ArrayDeque<Long>()

    fun allow(now: Long): Boolean {
        evict(now)
        return failures.size < maxFailures
    }

    fun recordFailure(now: Long) {
        evict(now)
        failures.addLast(now)
    }

    private fun evict(now: Long) {
        while (failures.isNotEmpty() && now - failures.first() > windowMs) {
            failures.removeFirst()
        }
    }
}

enum class OfflineMode {
    ONLINE_SESSION,
    OFFLINE_AUTHORIZED,
    TOKEN_EXPIRED_STILL_LOCAL,
    CLOUD_UNREACHABLE,
    CLOUD_SIGNED_OUT,
    NEEDS_SIGN_IN
}

object OfflineModeResolver {
    fun resolve(
        hasNetwork: Boolean,
        hasLocalMesh: Boolean,
        localAuth: LocalAuth,
        cloud: CloudCredential
    ): OfflineMode {
        val local = hasLocalMesh || localAuth == LocalAuth.OFFLINE_AUTHORIZED
        if (!local && localAuth != LocalAuth.AWAITING_IDENTITY) return OfflineMode.NEEDS_SIGN_IN
        if (cloud == CloudCredential.SIGNED_OUT && local) return OfflineMode.CLOUD_SIGNED_OUT
        if (cloud == CloudCredential.UNREACHABLE && local) return OfflineMode.CLOUD_UNREACHABLE
        if (cloud == CloudCredential.TOKEN_EXPIRED && local) return OfflineMode.TOKEN_EXPIRED_STILL_LOCAL
        if (!hasNetwork && local) return OfflineMode.OFFLINE_AUTHORIZED
        if (cloud == CloudCredential.ACTIVE && hasNetwork) return OfflineMode.ONLINE_SESSION
        if (local) return OfflineMode.OFFLINE_AUTHORIZED
        return OfflineMode.NEEDS_SIGN_IN
    }
}
