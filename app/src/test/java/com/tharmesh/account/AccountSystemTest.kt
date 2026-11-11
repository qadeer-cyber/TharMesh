// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import com.tharmesh.data.UserPrefs
import com.tharmesh.db.dao.PeerIdentityDao
import com.tharmesh.db.entity.PeerIdentityEntity
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.identity.IdentityQrPayload
import com.tharmesh.identity.QrCodec
import com.tharmesh.identity.RoomPeerTrustStore
import java.io.File
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account lifecycle. Cloud calls are in-process fakes. Nothing here talks to
 * Supabase, Firebase, BLE, or Wi-Fi. [SimulatedAccountDirectory.LABEL] is
 * SIMULATED and is not live authentication.
 */
class AccountSystemTest {

    private val fast = IdentityLifecycle.Kdf(n = 16, r = 1, p = 1)
    private val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(9, 9, 9)) }

    @Test
    fun scrypt_matchesRfc7914Vectors() {
        assertEquals(
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
            hex(Scrypt.derive(byteArrayOf(), byteArrayOf(), 16, 1, 1, 64))
        )
        assertEquals(
            "aec6b7483ed26e08802b41f4032086a0e886be7ac48fcfd92ff0cef8109752f4ac74b077263256a65a99701b7a304d46611c8aa391e799ce10a27753e7e9c09a",
            hex(Scrypt.derive("password".toByteArray(), "NaCl".toByteArray(), 16, 1, 1, 64))
        )
        assertEquals(
            "25a9fa207f87ca09a4ef8b9f777aca16beb784ae1830bfbfd38325aabb9377df1ba784d746ea273bf516a46fbfacf511c5beba4c4ab3acc7fa6f460b6c0f477b",
            hex(Scrypt.derive("pleaseletmein".toByteArray(), "SodiumChloride".toByteArray(), 16, 8, 1, 64))
        )
    }

    @Test
    fun recoverySecret_is128Bits_andScryptIsTheProductionKdf() {
        val phrase = IdentityRecovery.generatePhrase(random)
        val compact = IdentityRecovery.normalize(phrase)
        assertEquals(32, compact.length)
        assertTrue(compact.matches(Regex("[0-9a-f]{32}")))
        assertTrue(IdentityRecovery.SCRYPT_N >= 16_384)
        assertEquals(8, IdentityRecovery.SCRYPT_R)
        assertEquals(1, IdentityRecovery.SCRYPT_P)
        val sealed = IdentityRecovery.seal("mesh-private", phrase, random, n = 16, rParam = 1, pParam = 1)
        assertEquals(IdentityRecovery.KDF_SCRYPT, sealed.kdf)
        assertEquals("mesh-private", IdentityRecovery.open(sealed, phrase))
        assertNull(IdentityRecovery.open(sealed, "00000000000000000000000000000000"))
        assertFalse(sealed.ciphertextB64.contains(compact))
    }

    @Test
    fun initialSignup_usesSimulatedAdapter_notANetwork() {
        val directory = SimulatedAccountDirectory()
        assertEquals("SIMULATED", SimulatedAccountDirectory.LABEL)
        val created = directory.signUp("a@b.co", "password1", "Thar_User", "Qadeer")
        assertTrue(created is SimulatedAccountDirectory.Result.Ok)
        assertTrue(created.simulated)
        val row = (created as SimulatedAccountDirectory.Result.Ok).row
        assertFalse(row.emailVerified)
        assertTrue(row.accountId.isNotBlank())
        directory.confirmEmail(row.accountId)
        val mesh = mesh("user-existing")
        val account = accountKey(1, IdentityOrigin.ORIGINAL)
        val phrase = IdentityRecovery.generatePhrase(random)
        val established = IdentityLifecycle.establishOriginal(
            InstallRecord(accountId = row.accountId, handle = row.handle, email = row.email),
            mesh, account, phrase, random, fast
        ) as IdentityLifecycle.EstablishResult.Created
        val upload = RecoveryUpload.body(row.accountId, established.record.recoverySealed!!)
        assertFalse(upload.contains(mesh.privateKeyBase64))
        assertFalse(upload.contains(phrase.replace("-", "")))
        val stored = directory.putRecovery(row.accountId, upload) as SimulatedAccountDirectory.Result.Ok
        assertTrue(stored.simulated)
        assertFalse(directory.recoveryOf(row.accountId)!!.contains(mesh.privateKeyBase64))
    }

    @Test
    fun offlineStartup_andRestart_andExpiredToken_keepLocalAuthorization() {
        val mesh = mesh("user-offline")
        var record = IdentityLifecycle.continueOffline(InstallRecord(), mesh, "Offline")
        assertEquals(LocalAuth.OFFLINE_AUTHORIZED, record.localAuth)
        assertEquals(mesh.privateKeyBase64, record.mesh!!.privateKeyBase64)
        record = record.copy(cloud = CloudCredential.ACTIVE, tokenExpiresAtMs = 1_000L, accessToken = "tok")
        val expired = IdentityLifecycle.onClock(record, now = 1_000L)
        assertEquals(CloudCredential.TOKEN_EXPIRED, expired.cloud)
        assertEquals(LocalAuth.OFFLINE_AUTHORIZED, expired.localAuth)
        assertEquals(mesh.privateKeyBase64, expired.mesh!!.privateKeyBase64)
        assertTrue(AccountSession.offlineAuthorized(
            AccountSession.Activation(record.accountId ?: "local", "Offline", mesh.fingerprint(), 1L),
            accessTokenExpiresAtMs = 1L
        ))
        val again = InstallRecordCodec.fromJson(InstallRecordCodec.toJson(expired))
        assertEquals(expired.mesh!!.privateKeyBase64, again!!.mesh!!.privateKeyBase64)
        assertEquals(CloudCredential.TOKEN_EXPIRED, again.cloud)
        assertEquals(LocalAuth.OFFLINE_AUTHORIZED, again.localAuth)
        val held = IdentityLifecycle.continueOffline(
            InstallRecord(holdMeshGeneration = true, localAuth = LocalAuth.AWAITING_IDENTITY),
            mesh("other"),
            "nope"
        )
        assertNull(held.mesh)
    }

    @Test
    fun newPhoneLogin_doesNotFabricateAMeshKey() {
        val login = IdentityLifecycle.cloudLogin(
            record = InstallRecord(),
            accountId = "acct-new",
            serverFingerprint = "server-fp-value",
            email = "a@b.co",
            emailVerified = true,
            accessToken = "tok",
            expiresAtMs = 5_000L,
            now = 1_000L
        )
        assertEquals(AccountSession.Bind.NEEDS_RECOVERY_OR_NEW_IDENTITY, login.bind)
        assertNull(login.record.mesh)
        assertTrue(login.record.holdMeshGeneration)
        assertEquals(LocalAuth.AWAITING_IDENTITY, login.record.localAuth)
        assertNotEquals("server-fp-value", login.record.mesh?.fingerprint())
    }

    @Test
    fun encryptedRestore_andWrongSecret_andConflict() {
        val mesh = mesh("user-restore")
        val account = accountKey(1, IdentityOrigin.ORIGINAL)
        val phrase = "correct horse battery"
        val created = IdentityLifecycle.establishOriginal(
            InstallRecord(accountId = "acct"),
            mesh, account, phrase, random, fast
        ) as IdentityLifecycle.EstablishResult.Created
        val sealed = created.record.recoverySealed!!
        val restored = IdentityLifecycle.restore(InstallRecord(accountId = "acct"), phrase, sealed)
        assertTrue(restored is IdentityLifecycle.RestoreResult.Restored)
        val back = (restored as IdentityLifecycle.RestoreResult.Restored).record
        assertEquals(mesh.privateKeyBase64, back.mesh!!.privateKeyBase64)
        assertEquals(IdentityOrigin.RESTORED, back.account!!.origin)
        val wrong = IdentityLifecycle.restore(InstallRecord(mesh = mesh), "not-the-secret", sealed)
        assertTrue(wrong is IdentityLifecycle.RestoreResult.WrongSecret)
        assertEquals(mesh.privateKeyBase64, (wrong as IdentityLifecycle.RestoreResult.WrongSecret).record.mesh!!.privateKeyBase64)
        val other = mesh("user-other")
        val conflict = IdentityLifecycle.restore(InstallRecord(mesh = other), phrase, sealed)
        assertTrue(conflict is IdentityLifecycle.RestoreResult.Conflict)
        assertEquals(other.privateKeyBase64, (conflict as IdentityLifecycle.RestoreResult.Conflict).record.mesh!!.privateKeyBase64)
    }

    @Test
    fun enrollment_requiresApproval_andRejectsKeySubstitution() {
        val account = CryptoIdentity.generate()
        val mesh = CryptoIdentity.generate()
        val device = SoftwareDeviceSigner(CryptoIdentity.generate())
        assertFalse(device.hardwareNonExportable)
        val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val request = DeviceEnrollment.createRequest("acct", mesh.publicKeyBase64, device, "dev-1", nonce)
        assertNull(DeviceEnrollment.approve(request, account, "acct", 1, userApproved = false))
        assertNull(DeviceEnrollment.approve(request, account, "other-acct", 1, userApproved = true))
        val cert = DeviceEnrollment.approve(request, account, "acct", 1, userApproved = true)
        assertNotNull(cert)
        assertTrue(DeviceEnrollment.accept(cert!!, account.publicKeyBase64, request))
        val swapped = cert.copy(meshPublicKey = CryptoIdentity.generate().publicKeyBase64)
        assertFalse(DeviceEnrollment.accept(swapped, account.publicKeyBase64, request))
        val qr = EnrollmentQr.encodeRequest(request)
        assertEquals(request.devicePublicKey, EnrollmentQr.decodeRequest(qr)!!.devicePublicKey)
        assertNull(QrCodec.decode(qr))
        assertFalse(qr.contains("privateKey"))
    }

    @Test
    fun duplicateHandle_andDuplicateEmail_areRejected() {
        val directory = SimulatedAccountDirectory()
        assertTrue(directory.signUp("a@b.co", "password1", "same", "A") is SimulatedAccountDirectory.Result.Ok)
        val email = directory.signUp("a@b.co", "password1", "other", "B")
        assertEquals("email_taken", (email as SimulatedAccountDirectory.Result.Failed).reason)
        val handle = directory.signUp("c@b.co", "password1", "Same", "C")
        assertEquals("handle_taken", (handle as SimulatedAccountDirectory.Result.Failed).reason)
        assertNull(HandlePolicy.normalize("no"))
        assertNull(AvatarPolicy.normalize("http://insecure.example/a.png"))
        assertEquals("https://cdn.example/a.png", AvatarPolicy.normalize(" https://cdn.example/a.png "))
        assertFalse(RlsPolicy.handleInsert("user-a", "user-b", "same", taken = false))
        assertFalse(RlsPolicy.handleInsert("user-a", "user-a", "same", taken = true))
        assertTrue(RlsPolicy.handleInsert("user-a", "user-a", "same", taken = false))
    }

    @Test
    fun anonymousMigration_keepsMeshKey_andQrTrust() {
        val mesh = mesh("user-legacy")
        val linked = IdentityLifecycle.linkLegacy(
            InstallRecord(mesh = mesh, provider = "anonymous", localAuth = LocalAuth.OFFLINE_AUTHORIZED),
            accountId = "acct-legacy",
            handle = "legacy_user",
            displayName = "Legacy",
            email = "legacy@b.co",
            provider = "google",
            serverFingerprint = mesh.fingerprint()
        )
        assertEquals(AccountSession.Bind.KEEP_LOCAL_IDENTITY, linked.bind)
        val linkedMesh = linked.record.mesh!!
        assertEquals(mesh.privateKeyBase64, linkedMesh.privateKeyBase64)
        assertEquals(mesh.userId, linkedMesh.userId)
        val refused = IdentityLifecycle.linkLegacy(
            InstallRecord(mesh = mesh),
            "acct", null, null, null, "google", "different-fingerprint"
        )
        assertEquals(AccountSession.Bind.REFUSE_SILENT_REPLACEMENT, refused.bind)
        assertEquals(mesh.privateKeyBase64, refused.record.mesh!!.privateKeyBase64)
        val payload = IdentityQrPayload(mesh.userId, "Legacy", mesh.publicKeyBase64)
        val decoded = QrCodec.decode(QrCodec.encode(payload))
        assertEquals(mesh.publicKeyBase64, decoded!!.publicKeyBase64)
        val dao = FakeDao()
        val store = RoomPeerTrustStore(dao) { 5L }
        assertTrue(store.markVerified(mesh.userId, mesh.publicKeyBase64) is com.tharmesh.identity.PeerTrustStore.VerifyResult.Verified)
        assertEquals(mesh.publicKeyBase64, store.storedKey(mesh.userId))
        val enroll = EnrollmentQr.encodeRequest(
            DeviceEnrollment.createRequest(
                "acct", mesh.publicKeyBase64, SoftwareDeviceSigner(CryptoIdentity.generate()), "d", byteArrayOf(1, 2, 3)
            )
        )
        assertNull(QrCodec.decode(enroll))
    }

    @Test
    fun lostDeviceReset_isNotPresentedAsTheOriginalKey() {
        val old = mesh("user-lost")
        val created = IdentityLifecycle.establishOriginal(
            InstallRecord(), old, accountKey(1, IdentityOrigin.ORIGINAL), "phrase words here", random, fast
        ) as IdentityLifecycle.EstablishResult.Created
        val reset = IdentityLifecycle.explicitReset(
            created.record,
            mesh("user-lost"),
            accountKey(1, IdentityOrigin.ORIGINAL),
            "another recovery phrase",
            userConfirmedWarning = true,
            random = random,
            kdf = fast
        ) as IdentityLifecycle.EstablishResult.Created
        assertFalse(reset.presentsAsOriginal)
        val resetAccount = reset.record.account!!
        assertEquals(IdentityOrigin.RESET, resetAccount.origin)
        assertEquals(2, resetAccount.generation)
        assertNotEquals(old.publicKeyBase64, reset.record.mesh!!.publicKeyBase64)
        assertTrue(IdentityLifecycle.explicitReset(
            created.record, mesh("x"), accountKey(1, IdentityOrigin.RESET), "p", false, random, fast
        ) is IdentityLifecycle.EstablishResult.Refused)
    }

    @Test
    fun cloudSignOut_clearsToken_andRetainsMeshKey() {
        val mesh = mesh("user-stay")
        val record = InstallRecord(
            mesh = mesh,
            localAuth = LocalAuth.OFFLINE_AUTHORIZED,
            cloud = CloudCredential.ACTIVE,
            accessToken = "secret-token",
            tokenExpiresAtMs = 50L
        )
        val signedOut = IdentityLifecycle.cloudSignOut(record)
        assertNull(signedOut.accessToken)
        assertEquals(CloudCredential.SIGNED_OUT, signedOut.cloud)
        assertEquals(LocalAuth.OFFLINE_AUTHORIZED, signedOut.localAuth)
        assertEquals(mesh.privateKeyBase64, signedOut.mesh!!.privateKeyBase64)
        val kept = UserPrefs.retainedOnSignOut(
            mapOf(
                "identity_private_key_b64" to mesh.privateKeyBase64,
                "identity_public_key_b64" to mesh.publicKeyBase64,
                "user_id" to mesh.userId,
                "username" to "stay",
                "authenticated" to true,
                "cloud_access_token" to "secret-token",
                "legal_blocked_user_ids" to setOf("blocked-peer")
            )
        )
        assertEquals(mesh.privateKeyBase64, kept["identity_private_key_b64"])
        assertEquals(mesh.userId, kept["user_id"])
        assertTrue(kept.containsKey("legal_blocked_user_ids"))
        assertFalse(kept.containsKey("cloud_access_token"))
    }

    @Test
    fun serverDowntime_doesNotDropLocalMesh_andHttpIsNotInvented() {
        val mesh = mesh("user-down")
        val local = IdentityLifecycle.continueOffline(InstallRecord(), mesh, "Down")
        val down = IdentityLifecycle.noteServerUnreachable(local)
        assertEquals(CloudCredential.UNREACHABLE, down.cloud)
        assertEquals(mesh.privateKeyBase64, down.mesh!!.privateKeyBase64)
        assertEquals(LocalAuth.OFFLINE_AUTHORIZED, down.localAuth)
        val throwing = HttpTransport { _, _, _ -> throw java.io.IOException("down") }
        val failed = AuthBackend.signIn(throwing, "https://example.supabase.co", "anon", "a@b.co", "password1")
        assertEquals("network", (failed as AuthCall.Failed).reason)
        val plain = HttpTransport { _, _, _ -> throw AssertionError("must not be called") }
        val blocked = AuthBackend.signIn(plain, "http://example.supabase.co", "anon", "a@b.co", "password1")
        assertEquals("cloud_auth_not_configured", (blocked as AuthCall.Failed).reason)
        val second = AuthBackend.signIn(
            HttpTransport { _, _, _ ->
                HttpTransport.Response(200, "{\"access_token\":\"tok\",\"expires_in\":10,\"user\":{\"id\":\"u\"}}")
            },
            "https://example.supabase.co",
            "anon",
            "a@b.co",
            "password1"
        )
        assertTrue(second is AuthCall.Ok)
        assertEquals(mesh.privateKeyBase64, down.mesh!!.privateKeyBase64)
    }

    @Test
    fun rateLimit_andRulesFile_andRecoveryPolicy() {
        val limiter = AuthRateLimiter(maxFailures = 2, windowMs = 1_000L)
        assertTrue(limiter.allow(0L))
        limiter.recordFailure(0L)
        limiter.recordFailure(10L)
        assertFalse(limiter.allow(20L))
        assertTrue(limiter.allow(2_000L))
        val sql = rulesFile().readText()
        assertTrue(sql.contains("enable row level security"))
        assertTrue(sql.contains("auth.uid() = account_id"))
        assertTrue(sql.contains("recovery_blobs"))
        assertTrue(sql.contains("handles"))
        assertFalse(sql.contains("private_key"))
        assertFalse(sql.contains("phrase"))
        val mesh = mesh("user-rules")
        val sealed = IdentityRecovery.seal(
            RecoveryPayload.encode(mesh, accountKey(1, IdentityOrigin.ORIGINAL)),
            "phrase for rules",
            random,
            n = 16,
            rParam = 1,
            pParam = 1
        )
        val body = RecoveryUpload.body("acct", sealed.copy(iterations = 16_384, r = 8, p = 1))
        assertTrue(RlsPolicy.recoveryAccepts(body))
        assertFalse(RlsPolicy.recoveryAccepts(body.dropLast(1) + ",\"private_key\":\"no\"}"))
        assertFalse(RlsPolicy.ownerWrite(null, "acct"))
        assertFalse(RlsPolicy.ownerWrite("other", "acct"))
        assertTrue(RlsPolicy.ownerWrite("acct", "acct"))
    }

    @Test
    fun offlineIndicator_distinguishesTokenExpiryFromSignOut() {
        assertEquals(
            OfflineMode.TOKEN_EXPIRED_STILL_LOCAL,
            OfflineModeResolver.resolve(false, true, LocalAuth.OFFLINE_AUTHORIZED, CloudCredential.TOKEN_EXPIRED)
        )
        assertEquals(
            OfflineMode.OFFLINE_AUTHORIZED,
            OfflineModeResolver.resolve(false, true, LocalAuth.OFFLINE_AUTHORIZED, CloudCredential.ACTIVE)
        )
        assertEquals(
            OfflineMode.CLOUD_SIGNED_OUT,
            OfflineModeResolver.resolve(false, true, LocalAuth.OFFLINE_AUTHORIZED, CloudCredential.SIGNED_OUT)
        )
        assertEquals(
            OfflineMode.CLOUD_UNREACHABLE,
            OfflineModeResolver.resolve(true, true, LocalAuth.OFFLINE_AUTHORIZED, CloudCredential.UNREACHABLE)
        )
        assertEquals(
            OfflineMode.NEEDS_SIGN_IN,
            OfflineModeResolver.resolve(false, false, LocalAuth.ABSENT, CloudCredential.NONE)
        )
    }

    private fun mesh(userId: String): MeshMaterial {
        return MeshMaterial.from(CryptoIdentity.generate(), userId)
    }

    private fun accountKey(generation: Int, origin: IdentityOrigin): AccountMaterial {
        val key = CryptoIdentity.generate()
        return AccountMaterial(key.publicKeyBase64, key.privateKeyBase64, generation, origin)
    }

    private fun rulesFile(): File {
        val candidates = listOf(
            File("supabase/schema.sql"),
            File("../supabase/schema.sql"),
            File("app/../supabase/schema.sql")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("schema.sql not found from ${File(".").absolutePath}")
    }

    private fun hex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private class FakeDao : PeerIdentityDao {
        val rows = HashMap<String, PeerIdentityEntity>()
        override fun insertIfAbsent(row: PeerIdentityEntity): Long {
            return if (rows.putIfAbsent(row.userId, row) == null) 1L else -1L
        }
        override fun findByUserId(userId: String): PeerIdentityEntity? = rows[userId]
        override fun count(): Int = rows.size
        override fun setVerified(userId: String, ts: Long): Int {
            val row = rows[userId] ?: return 0
            rows[userId] = row.copy(verified = true, verifiedAtMs = ts)
            return 1
        }
        override fun replaceUnverified(userId: String, publicKeyBase64: String, fingerprint: String, ts: Long): Int {
            val row = rows[userId] ?: return 0
            if (row.verified) return 0
            rows[userId] = row.copy(publicKeyBase64 = publicKeyBase64, fingerprint = fingerprint, verified = true, verifiedAtMs = ts)
            return 1
        }
        override fun isVerified(userId: String): Int = if (rows[userId]?.verified == true) 1 else 0
        override fun getVerifiedUserIds(): List<String> = rows.values.filter { it.verified }.map { it.userId }
        override fun getAllUserIds(): List<String> = rows.keys.toList()
    }
}
