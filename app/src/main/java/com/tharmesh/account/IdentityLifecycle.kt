// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import java.security.SecureRandom

/**
 * Pure transitions for the account record. None of these talk to the network
 * or to Android Keystore. A login never mints a mesh key. A wrong recovery
 * secret returns the same record. A conflicting mesh key is not overwritten.
 */
object IdentityLifecycle {

    const val RESET_WARNING: String =
        "This is a new mesh key. It is not your previous key. Contacts must scan it again. Old messages sealed to the lost key cannot be opened with this one."

    data class Kdf(
        val n: Int = IdentityRecovery.SCRYPT_N,
        val r: Int = IdentityRecovery.SCRYPT_R,
        val p: Int = IdentityRecovery.SCRYPT_P
    )

    data class LoginResult(val record: InstallRecord, val bind: AccountSession.Bind)

    sealed class RestoreResult {
        data class Restored(val record: InstallRecord) : RestoreResult()
        data class WrongSecret(val record: InstallRecord) : RestoreResult()
        data class Conflict(val record: InstallRecord) : RestoreResult()
        data class Malformed(val record: InstallRecord) : RestoreResult()
    }

    sealed class EstablishResult {
        data class Created(
            val record: InstallRecord,
            val presentsAsOriginal: Boolean
        ) : EstablishResult()
        data class Refused(val record: InstallRecord) : EstablishResult()
    }

    fun continueOffline(record: InstallRecord, mesh: MeshMaterial, displayName: String): InstallRecord {
        if (record.holdMeshGeneration && record.mesh == null) return record
        if (record.mesh != null) {
            return record.copy(
                displayName = displayName.ifBlank { record.displayName },
                localAuth = LocalAuth.OFFLINE_AUTHORIZED
            )
        }
        return record.copy(
            mesh = mesh,
            displayName = displayName.ifBlank { mesh.userId },
            provider = record.provider.ifBlank { "anonymous" },
            localAuth = LocalAuth.OFFLINE_AUTHORIZED,
            holdMeshGeneration = false
        )
    }

    /**
     * Attach a cloud account to a mesh key that already exists. The mesh
     * material in the returned record is the same object contents.
     */
    fun linkLegacy(
        record: InstallRecord,
        accountId: String,
        handle: String?,
        displayName: String?,
        email: String?,
        provider: String,
        serverFingerprint: String?
    ): LoginResult {
        val mesh = record.mesh ?: return LoginResult(record, AccountSession.Bind.NEEDS_RECOVERY_OR_NEW_IDENTITY)
        val bind = AccountSession.bind(mesh.fingerprint(), serverFingerprint)
        if (bind == AccountSession.Bind.REFUSE_SILENT_REPLACEMENT) {
            return LoginResult(record, bind)
        }
        return LoginResult(
            record.copy(
                accountId = accountId,
                handle = handle ?: record.handle,
                displayName = displayName ?: record.displayName,
                email = email ?: record.email,
                provider = provider,
                localAuth = LocalAuth.OFFLINE_AUTHORIZED,
                mesh = mesh,
                account = record.account?.copy(origin = IdentityOrigin.LEGACY_UNLINKED)
                    ?: record.account
            ),
            bind
        )
    }

    fun cloudLogin(
        record: InstallRecord,
        accountId: String,
        serverFingerprint: String?,
        email: String?,
        emailVerified: Boolean,
        accessToken: String,
        expiresAtMs: Long,
        now: Long
    ): LoginResult {
        val bind = AccountSession.bind(record.mesh?.fingerprint(), serverFingerprint)
        val cloud = if (expiresAtMs <= now) CloudCredential.TOKEN_EXPIRED else CloudCredential.ACTIVE
        val base = record.copy(
            accountId = accountId,
            email = email ?: record.email,
            emailVerified = emailVerified,
            accessToken = accessToken,
            tokenExpiresAtMs = expiresAtMs,
            cloud = cloud
        )
        return when (bind) {
            AccountSession.Bind.KEEP_LOCAL_IDENTITY -> LoginResult(
                base.copy(localAuth = LocalAuth.OFFLINE_AUTHORIZED, holdMeshGeneration = false, mesh = record.mesh),
                bind
            )
            AccountSession.Bind.NEEDS_RECOVERY_OR_NEW_IDENTITY -> LoginResult(
                base.copy(
                    localAuth = LocalAuth.AWAITING_IDENTITY,
                    holdMeshGeneration = true,
                    mesh = null
                ),
                bind
            )
            AccountSession.Bind.REFUSE_SILENT_REPLACEMENT -> LoginResult(
                base.copy(
                    mesh = record.mesh,
                    localAuth = if (record.mesh != null) LocalAuth.OFFLINE_AUTHORIZED else record.localAuth,
                    holdMeshGeneration = record.holdMeshGeneration
                ),
                bind
            )
        }
    }

    fun establishOriginal(
        record: InstallRecord,
        mesh: MeshMaterial,
        account: AccountMaterial,
        phrase: String,
        random: SecureRandom = SecureRandom(),
        kdf: Kdf = Kdf(),
        certificate: String? = null
    ): EstablishResult {
        if (record.mesh != null && record.mesh.publicKeyBase64 != mesh.publicKeyBase64) {
            return EstablishResult.Refused(record)
        }
        val keptMesh = record.mesh ?: mesh
        val origin = if (record.mesh != null) IdentityOrigin.LEGACY_UNLINKED else IdentityOrigin.ORIGINAL
        val accountStored = account.copy(
            origin = origin,
            generation = if (account.generation < 1) 1 else account.generation
        )
        val sealed = IdentityRecovery.seal(
            RecoveryPayload.encode(keptMesh, accountStored),
            phrase,
            random,
            kdf.n,
            kdf.r,
            kdf.p
        )
        return EstablishResult.Created(
            record.copy(
                mesh = keptMesh,
                account = accountStored,
                localAuth = LocalAuth.OFFLINE_AUTHORIZED,
                holdMeshGeneration = false,
                recoverySealed = sealed,
                deviceCertificateB64 = certificate ?: record.deviceCertificateB64,
                displayName = record.displayName ?: keptMesh.userId
            ),
            presentsAsOriginal = origin == IdentityOrigin.ORIGINAL
        )
    }

    fun restore(record: InstallRecord, phrase: String, sealed: IdentityRecovery.SealedIdentity): RestoreResult {
        val opened = IdentityRecovery.open(sealed, phrase) ?: return RestoreResult.WrongSecret(record)
        val parsed = RecoveryPayload.decode(opened) ?: return RestoreResult.Malformed(record)
        val (mesh, account) = parsed
        if (record.mesh != null && record.mesh.publicKeyBase64 != mesh.publicKeyBase64) {
            return RestoreResult.Conflict(record)
        }
        val restored = account.copy(origin = IdentityOrigin.RESTORED)
        return RestoreResult.Restored(
            record.copy(
                mesh = mesh,
                account = restored,
                localAuth = LocalAuth.OFFLINE_AUTHORIZED,
                holdMeshGeneration = false,
                recoverySealed = sealed
            )
        )
    }

    fun explicitReset(
        record: InstallRecord,
        mesh: MeshMaterial,
        account: AccountMaterial,
        phrase: String,
        userConfirmedWarning: Boolean,
        random: SecureRandom = SecureRandom(),
        kdf: Kdf = Kdf()
    ): EstablishResult {
        if (!userConfirmedWarning) return EstablishResult.Refused(record)
        val generation = (record.account?.generation ?: 0) + 1
        val reset = account.copy(generation = generation, origin = IdentityOrigin.RESET)
        val sealed = IdentityRecovery.seal(
            RecoveryPayload.encode(mesh, reset),
            phrase,
            random,
            kdf.n,
            kdf.r,
            kdf.p
        )
        return EstablishResult.Created(
            record.copy(
                mesh = mesh,
                account = reset,
                localAuth = LocalAuth.OFFLINE_AUTHORIZED,
                holdMeshGeneration = false,
                recoverySealed = sealed
            ),
            presentsAsOriginal = false
        )
    }

    fun onClock(record: InstallRecord, now: Long): InstallRecord {
        val exp = record.tokenExpiresAtMs
        if (record.cloud == CloudCredential.ACTIVE && exp != null && now >= exp) {
            return record.copy(cloud = CloudCredential.TOKEN_EXPIRED)
        }
        return record
    }

    fun cloudSignOut(record: InstallRecord): InstallRecord {
        return record.copy(
            cloud = CloudCredential.SIGNED_OUT,
            accessToken = null,
            refreshToken = null,
            tokenExpiresAtMs = null
        )
    }

    fun noteServerUnreachable(record: InstallRecord): InstallRecord {
        if (record.localAuth == LocalAuth.ABSENT && record.mesh == null) return record
        return record.copy(cloud = CloudCredential.UNREACHABLE)
    }
}
