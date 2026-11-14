// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity
import org.json.JSONObject

enum class LocalAuth { ABSENT, OFFLINE_AUTHORIZED, AWAITING_IDENTITY }

enum class CloudCredential { NONE, ACTIVE, TOKEN_EXPIRED, SIGNED_OUT, UNREACHABLE }

enum class IdentityOrigin { NONE, LEGACY_UNLINKED, ORIGINAL, RESTORED, RESET }

data class MeshMaterial(
    val userId: String,
    val publicKeyBase64: String,
    val privateKeyBase64: String
) {
    fun fingerprint(): String = CryptoIdentity.fingerprintOf(publicKeyBase64)

    companion object {
        fun from(identity: CryptoIdentity, userId: String): MeshMaterial {
            return MeshMaterial(userId, identity.publicKeyBase64, identity.privateKeyBase64)
        }
    }
}

data class AccountMaterial(
    val publicKeyBase64: String,
    val privateKeyBase64: String,
    val generation: Int,
    val origin: IdentityOrigin
) {
    fun fingerprint(): String = CryptoIdentity.fingerprintOf(publicKeyBase64)
}

/**
 * On-device account record. [accessToken] is a cloud credential. The mesh key
 * does not depend on it. [recoverySealed] is ciphertext; the recovery secret
 * is never a field here.
 */
data class InstallRecord(
    val mesh: MeshMaterial? = null,
    val account: AccountMaterial? = null,
    val accountId: String? = null,
    val handle: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val provider: String = "anonymous",
    val localAuth: LocalAuth = LocalAuth.ABSENT,
    val cloud: CloudCredential = CloudCredential.NONE,
    val tokenExpiresAtMs: Long? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val holdMeshGeneration: Boolean = false,
    val recoverySealed: IdentityRecovery.SealedIdentity? = null,
    val deviceCertificateB64: String? = null
)

object RecoveryPayload {

    fun encode(mesh: MeshMaterial, account: AccountMaterial): String {
        return JSONObject()
            .put("v", 1)
            .put("meshUserId", mesh.userId)
            .put("meshPublic", mesh.publicKeyBase64)
            .put("meshPrivate", mesh.privateKeyBase64)
            .put("accountPublic", account.publicKeyBase64)
            .put("accountPrivate", account.privateKeyBase64)
            .put("generation", account.generation)
            .put("origin", account.origin.name)
            .toString()
    }

    fun decode(json: String): Pair<MeshMaterial, AccountMaterial>? {
        return try {
            val o = JSONObject(json)
            val mesh = MeshMaterial(
                userId = o.getString("meshUserId"),
                publicKeyBase64 = o.getString("meshPublic"),
                privateKeyBase64 = o.getString("meshPrivate")
            )
            val account = AccountMaterial(
                publicKeyBase64 = o.getString("accountPublic"),
                privateKeyBase64 = o.getString("accountPrivate"),
                generation = o.getInt("generation"),
                origin = IdentityOrigin.valueOf(o.getString("origin"))
            )
            if (mesh.userId.isBlank() || mesh.privateKeyBase64.isBlank()) null else mesh to account
        } catch (ignored: Throwable) {
            null
        }
    }
}

/** JSON the server is allowed to store. No private keys and no passphrase. */
object RecoveryUpload {

    fun body(accountId: String, sealed: IdentityRecovery.SealedIdentity): String {
        return JSONObject()
            .put("account_id", accountId)
            .put("kdf", sealed.kdf)
            .put("n", sealed.iterations)
            .put("r", sealed.r)
            .put("p", sealed.p)
            .put("salt_b64", sealed.saltB64)
            .put("iv_b64", sealed.ivB64)
            .put("ciphertext_b64", sealed.ciphertextB64)
            .toString()
    }

    fun parse(json: String): IdentityRecovery.SealedIdentity? {
        return try {
            val o = JSONObject(json)
            IdentityRecovery.SealedIdentity(
                kdf = o.getString("kdf"),
                iterations = o.getInt("n"),
                saltB64 = o.getString("salt_b64"),
                ivB64 = o.getString("iv_b64"),
                ciphertextB64 = o.getString("ciphertext_b64"),
                r = o.optInt("r", IdentityRecovery.SCRYPT_R),
                p = o.optInt("p", IdentityRecovery.SCRYPT_P)
            )
        } catch (ignored: Throwable) {
            null
        }
    }
}

object InstallRecordCodec {

    fun toJson(record: InstallRecord): String {
        val o = JSONObject()
        record.mesh?.let {
            o.put(
                "mesh",
                JSONObject()
                    .put("userId", it.userId)
                    .put("publicKey", it.publicKeyBase64)
                    .put("privateKey", it.privateKeyBase64)
            )
        }
        record.account?.let {
            o.put(
                "account",
                JSONObject()
                    .put("publicKey", it.publicKeyBase64)
                    .put("privateKey", it.privateKeyBase64)
                    .put("generation", it.generation)
                    .put("origin", it.origin.name)
            )
        }
        o.put("accountId", record.accountId)
        o.put("handle", record.handle)
        o.put("displayName", record.displayName)
        o.put("email", record.email)
        o.put("emailVerified", record.emailVerified)
        o.put("provider", record.provider)
        o.put("localAuth", record.localAuth.name)
        o.put("cloud", record.cloud.name)
        if (record.tokenExpiresAtMs != null) o.put("tokenExpiresAtMs", record.tokenExpiresAtMs)
        o.put("accessToken", record.accessToken)
        o.put("refreshToken", record.refreshToken)
        o.put("holdMeshGeneration", record.holdMeshGeneration)
        record.recoverySealed?.let {
            o.put(
                "recovery",
                JSONObject()
                    .put("kdf", it.kdf)
                    .put("iterations", it.iterations)
                    .put("r", it.r)
                    .put("p", it.p)
                    .put("salt", it.saltB64)
                    .put("iv", it.ivB64)
                    .put("ct", it.ciphertextB64)
            )
        }
        o.put("deviceCertificateB64", record.deviceCertificateB64)
        return o.toString()
    }

    fun fromJson(json: String): InstallRecord? {
        return try {
            val o = JSONObject(json)
            val mesh = if (o.has("mesh") && !o.isNull("mesh")) {
                val m = o.getJSONObject("mesh")
                MeshMaterial(m.getString("userId"), m.getString("publicKey"), m.getString("privateKey"))
            } else {
                null
            }
            val account = if (o.has("account") && !o.isNull("account")) {
                val a = o.getJSONObject("account")
                AccountMaterial(
                    a.getString("publicKey"),
                    a.getString("privateKey"),
                    a.getInt("generation"),
                    IdentityOrigin.valueOf(a.getString("origin"))
                )
            } else {
                null
            }
            val sealed = if (o.has("recovery") && !o.isNull("recovery")) {
                val r = o.getJSONObject("recovery")
                IdentityRecovery.SealedIdentity(
                    kdf = r.getString("kdf"),
                    iterations = r.getInt("iterations"),
                    saltB64 = r.getString("salt"),
                    ivB64 = r.getString("iv"),
                    ciphertextB64 = r.getString("ct"),
                    r = r.optInt("r", IdentityRecovery.SCRYPT_R),
                    p = r.optInt("p", IdentityRecovery.SCRYPT_P)
                )
            } else {
                null
            }
            InstallRecord(
                mesh = mesh,
                account = account,
                accountId = o.optString("accountId", null),
                handle = o.optString("handle", null),
                displayName = o.optString("displayName", null),
                email = o.optString("email", null),
                emailVerified = o.optBoolean("emailVerified", false),
                provider = o.optString("provider", "anonymous"),
                localAuth = LocalAuth.valueOf(o.getString("localAuth")),
                cloud = CloudCredential.valueOf(o.getString("cloud")),
                tokenExpiresAtMs = if (o.has("tokenExpiresAtMs")) o.getLong("tokenExpiresAtMs") else null,
                accessToken = o.optString("accessToken", null),
                refreshToken = o.optString("refreshToken", null),
                holdMeshGeneration = o.optBoolean("holdMeshGeneration", false),
                recoverySealed = sealed,
                deviceCertificateB64 = o.optString("deviceCertificateB64", null)
            )
        } catch (ignored: Throwable) {
            null
        }
    }
}
