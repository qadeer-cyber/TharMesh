// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.

package com.tharmesh.account

import com.tharmesh.db.dao.PeerIdentityDao
import com.tharmesh.db.entity.PeerIdentityEntity
import com.tharmesh.identity.Base64Url
import com.tharmesh.identity.CryptoIdentity
import com.tharmesh.identity.IdentityQrPayload
import com.tharmesh.identity.QrCodec
import com.tharmesh.identity.QrIdentityAlign
import com.tharmesh.identity.RoomPeerTrustStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Follow-up checks. No BLE, no Wi-Fi, no Android Keystore provider.
 * [AccountKeySeal.LABEL_SOFTWARE_TEST_DOUBLE] is a software AES key.
 */
class AccountFollowupTest {

    @Test
    fun qrUsesMeshSigningKey_andDoesNotDiscardADifferentStoreKey() {
        val mesh = CryptoIdentity.generate()
        val older = CryptoIdentity.generate()
        val meshKey = QrIdentityAlign.KeyMaterial(mesh.publicKeyBase64, mesh.privateKeyBase64)
        val storeKey = QrIdentityAlign.KeyMaterial(older.publicKeyBase64, older.privateKeyBase64)
        val decision = QrIdentityAlign.decide(meshKey, storeKey)
        assertEquals(mesh.publicKeyBase64, decision.present!!.publicKeyBase64)
        assertEquals(mesh.privateKeyBase64, decision.present!!.privateKeyBase64)
        assertTrue(decision.meshPrivateUnchanged)
        assertNull(decision.importIntoMesh)
        assertEquals(older.privateKeyBase64, decision.archiveStore!!.privateKeyBase64)
        assertEquals(mesh.fingerprint, CryptoIdentity.fingerprintOf(decision.present!!.publicKeyBase64))
        val payload = QrCodec.encode(
            IdentityQrPayload("user-legacy", "Legacy", decision.present!!.publicKeyBase64)
        )
        val decoded = QrCodec.decode(payload)
        assertEquals(mesh.publicKeyBase64, decoded!!.publicKeyBase64)
        val store = RoomPeerTrustStore(FakeDao()) { 1L }
        assertTrue(
            store.markVerified("user-legacy", decoded.publicKeyBase64) is
                com.tharmesh.identity.PeerTrustStore.VerifyResult.Verified
        )
        assertEquals(mesh.publicKeyBase64, store.storedKey("user-legacy"))
        val enrolled = EnrollmentQr.encodeRequest(
            DeviceEnrollment.createRequest(
                "acct",
                mesh.publicKeyBase64,
                SoftwareDeviceSigner(CryptoIdentity.generate()),
                "dev",
                byteArrayOf(4, 5, 6)
            )
        )
        assertNull(QrCodec.decode(enrolled))
    }

    @Test
    fun storeKeyIsImportedOnlyWhenMeshKeyIsAbsent() {
        val older = CryptoIdentity.generate()
        val storeKey = QrIdentityAlign.KeyMaterial(older.publicKeyBase64, older.privateKeyBase64)
        val decision = QrIdentityAlign.decide(mesh = null, store = storeKey)
        val imported = decision.importIntoMesh!!
        assertTrue(
            Base64Url.decode(imported.privateKeyBase64)!!.contentEquals(
                Base64Url.decode(older.privateKeyBase64)!!
            )
        )
        assertEquals(older.fingerprint, CryptoIdentity.fingerprintOf(decision.present!!.publicKeyBase64))
        assertNull(decision.archiveStore)
        val again = QrIdentityAlign.decide(imported, storeKey)
        assertNull(again.archiveStore)
        assertEquals(imported.privateKeyBase64, again.present!!.privateKeyBase64)
    }

    @Test
    fun methodA_requiresBothApprovals_andRejectsSubstitution() {
        val account = CryptoIdentity.generate()
        val mesh = CryptoIdentity.generate()
        val device = SoftwareDeviceSigner(CryptoIdentity.generate())
        assertFalse(device.hardwareNonExportable)
        val ok = DeviceTransfer.exchange(
            account, mesh, device, "acct-1",
            newDeviceConfirms = true, oldDeviceApproves = true, substituteMeshKey = false
        )
        assertEquals(DeviceTransfer.Outcome.ACCEPTED, ok.outcome)
        assertTrue(ok.requestQr!!.startsWith("tharmesh://enroll?"))
        assertTrue(ok.certificateQr!!.startsWith("tharmesh://enroll-cert?"))
        assertEquals(
            DeviceTransfer.Outcome.NEW_DEVICE_DECLINED,
            DeviceTransfer.exchange(
                account, mesh, device, "acct-1",
                newDeviceConfirms = false, oldDeviceApproves = true, substituteMeshKey = false
            ).outcome
        )
        assertEquals(
            DeviceTransfer.Outcome.OLD_DEVICE_DECLINED,
            DeviceTransfer.exchange(
                account, mesh, device, "acct-1",
                newDeviceConfirms = true, oldDeviceApproves = false, substituteMeshKey = false
            ).outcome
        )
        assertEquals(
            DeviceTransfer.Outcome.SUBSTITUTED_KEY,
            DeviceTransfer.exchange(
                account, mesh, device, "acct-1",
                newDeviceConfirms = true, oldDeviceApproves = true, substituteMeshKey = true
            ).outcome
        )
        assertEquals(
            DeviceTransfer.Outcome.WRONG_ACCOUNT,
            DeviceTransfer.exchange(
                account, mesh, device, "acct-1",
                newDeviceConfirms = true, oldDeviceApproves = true, substituteMeshKey = false,
                presentedAccountId = "acct-other"
            ).outcome
        )
    }

    @Test
    fun anonymousAndGoogleLink_keepMeshKeyMessagesAndContacts() {
        val mesh = MeshMaterial.from(CryptoIdentity.generate(), "user-anon")
        val messages = listOf("m1", "m2")
        val contacts = listOf("peer-a", "peer-b")
        for (provider in listOf("anonymous", "google")) {
            val linked = IdentityLifecycle.linkLegacy(
                InstallRecord(
                    mesh = mesh,
                    provider = provider,
                    localAuth = LocalAuth.OFFLINE_AUTHORIZED,
                    displayName = "Kept"
                ),
                accountId = "acct-$provider",
                handle = "kept_name",
                displayName = "Kept",
                email = "kept@b.co",
                provider = provider,
                serverFingerprint = mesh.fingerprint()
            )
            assertEquals(mesh.privateKeyBase64, linked.record.mesh!!.privateKeyBase64)
            assertEquals(mesh.userId, linked.record.mesh!!.userId)
            assertEquals(mesh.publicKeyBase64, linked.record.mesh!!.publicKeyBase64)
            assertEquals(listOf("m1", "m2"), messages)
            assertEquals(listOf("peer-a", "peer-b"), contacts)
            assertNotEquals("acct-other", linked.record.accountId)
        }
        val refused = IdentityLifecycle.linkLegacy(
            InstallRecord(mesh = mesh, provider = "google"),
            "acct", null, null, "g@b.co", "google", "other-fingerprint"
        )
        assertEquals(mesh.privateKeyBase64, refused.record.mesh!!.privateKeyBase64)
    }

    @Test
    fun restartExpiryAndServerDown_keepTheSameMeshKey() {
        val mesh = MeshMaterial.from(CryptoIdentity.generate(), "user-reboot")
        var record = IdentityLifecycle.continueOffline(InstallRecord(), mesh, "Reboot")
        record = record.copy(
            cloud = CloudCredential.ACTIVE,
            accessToken = "tok",
            tokenExpiresAtMs = 500L
        )
        val reloaded = InstallRecordCodec.fromJson(InstallRecordCodec.toJson(record))!!
        assertEquals(mesh.privateKeyBase64, reloaded.mesh!!.privateKeyBase64)
        val expired = IdentityLifecycle.onClock(reloaded, now = 500L)
        assertEquals(CloudCredential.TOKEN_EXPIRED, expired.cloud)
        assertEquals(LocalAuth.OFFLINE_AUTHORIZED, expired.localAuth)
        val down = IdentityLifecycle.noteServerUnreachable(expired)
        assertEquals(CloudCredential.UNREACHABLE, down.cloud)
        assertEquals(mesh.privateKeyBase64, down.mesh!!.privateKeyBase64)
        val http = HttpTransport { _, _, _ -> throw java.io.IOException("down") }
        val failed = AuthBackend.signIn(http, "https://example.supabase.co", "anon", "a@b.co", "password1")
        assertEquals("network", (failed as AuthCall.Failed).reason)
        assertEquals(mesh.privateKeyBase64, down.mesh!!.privateKeyBase64)
    }

    @Test
    fun softwareKeyWrap_isLabeledAndIsNotKeystore() {
        assertEquals("NOT_ANDROID_KEYSTORE", AccountKeySeal.LABEL_SOFTWARE_TEST_DOUBLE)
        val secret = "account-private-" + "k".repeat(48)
        val key = AccountKeySeal.softwareKey()
        val sealed = AccountKeySeal.sealSoftware(key, secret)
        assertEquals(secret, AccountKeySeal.openSoftware(key, sealed))
        assertNull(AccountKeySeal.openSoftware(AccountKeySeal.softwareKey(ByteArray(32) { 9 }), sealed))
        assertFalse(sealed.contains(secret))
        assertNull(AccountKeySeal.bytesToStore(false, byteArrayOf(1, 2, 3, 4), secret))
        val packed = Base64Url.decode(sealed)!!
        assertEquals(sealed, AccountKeySeal.bytesToStore(true, packed, secret))
    }

    @Test
    fun schema_handleIsUnique_recoveryIsCiphertext_rateLimitIsDocumented() {
        val sql = schemaFile().readText()
        assertTrue(SchemaReview.handleUniquenessDeclared(sql))
        assertTrue(SchemaReview.rateLimitNotePresent(sql))
        val columns = SchemaReview.columnNames(SchemaReview.tableBody(sql, "recovery_blobs")!!)
        assertEquals(SchemaReview.recoveryColumns, columns)
        assertFalse(columns.contains("private_key"))
        assertFalse(columns.contains("phrase"))
        assertFalse(columns.contains("mesh_private"))
        assertTrue(sql.contains("enable row level security"))
        assertTrue(sql.contains("auth.uid() = account_id"))
    }

    @Test
    fun requiredScreens_haveEnglishUrduAndSindhiCopy() {
        val keys = listOf(
            "login_create_account",
            "login_sign_in",
            "login_continue_with_google",
            "login_continue_anonymously",
            "account_create_title",
            "account_create_body",
            "account_signin_title",
            "account_signin_body",
            "account_verify_title",
            "account_verify_body",
            "account_recover_title",
            "account_recover_body",
            "account_secure_title",
            "account_secure_body",
            "account_save_title",
            "account_save_body",
            "account_devices_title",
            "account_devices_body",
            "account_offline_title",
            "account_offline_body",
            "account_security_title",
            "account_security_body"
        )
        val en = readStrings("values")
        val ur = readStrings("values-ur")
        val sd = readStrings("values-sd")
        for (key in keys) {
            assertTrue("missing en $key", en.contains("name=\"$key\""))
            assertTrue("missing ur $key", ur.contains("name=\"$key\""))
            assertTrue("missing sd $key", sd.contains("name=\"$key\""))
        }
    }

    private fun schemaFile(): File {
        return listOf(File("supabase/schema.sql"), File("../supabase/schema.sql"))
            .first { it.isFile }
    }

    private fun readStrings(dir: String): String {
        val file = listOf(
            File("src/main/res/$dir/strings.xml"),
            File("app/src/main/res/$dir/strings.xml")
        ).first { it.isFile }
        return file.readText()
    }

    private class FakeDao : PeerIdentityDao {
        val rows = HashMap<String, PeerIdentityEntity>()
        override fun insertIfAbsent(row: PeerIdentityEntity): Long =
            if (rows.putIfAbsent(row.userId, row) == null) 1L else -1L
        override fun findByUserId(userId: String): PeerIdentityEntity? = rows[userId]
        override fun count(): Int = rows.size
        override fun setVerified(userId: String, ts: Long): Int {
            val row = rows[userId] ?: return 0
            rows[userId] = row.copy(verified = true, verifiedAtMs = ts)
            return 1
        }
        override fun replaceUnverified(
            userId: String,
            publicKeyBase64: String,
            fingerprint: String,
            ts: Long
        ): Int {
            val row = rows[userId] ?: return 0
            if (row.verified) return 0
            rows[userId] = row.copy(
                publicKeyBase64 = publicKeyBase64,
                fingerprint = fingerprint,
                verified = true,
                verifiedAtMs = ts
            )
            return 1
        }
        override fun isVerified(userId: String): Int = if (rows[userId]?.verified == true) 1 else 0
        override fun getVerifiedUserIds(): List<String> = rows.values.filter { it.verified }.map { it.userId }
        override fun getAllUserIds(): List<String> = rows.keys.toList()
    }
}
