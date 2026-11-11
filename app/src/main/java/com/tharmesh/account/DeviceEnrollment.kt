// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import com.tharmesh.identity.Base64Url
import com.tharmesh.identity.CryptoIdentity
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A signer for the per-install enrollment key. Android Keystore implementations
 * report [hardwareNonExportable] true. The software signer used in JVM tests
 * reports false and does not add a private-key accessor.
 */
interface DeviceSigner {
    val publicKeyBase64: String
    val hardwareNonExportable: Boolean
    fun sign(data: ByteArray): String
}

class SoftwareDeviceSigner(private val identity: CryptoIdentity) : DeviceSigner {
    override val publicKeyBase64: String = identity.publicKeyBase64
    override val hardwareNonExportable: Boolean = false
    override fun sign(data: ByteArray): String = identity.sign(data)
}

data class EnrollmentRequest(
    val accountId: String,
    val meshPublicKey: String,
    val devicePublicKey: String,
    val deviceId: String,
    val nonceB64: String,
    val deviceSignature: String
)

data class DeviceCertificate(
    val accountId: String,
    val meshPublicKey: String,
    val devicePublicKey: String,
    val deviceId: String,
    val nonceB64: String,
    val generation: Int,
    val accountSignature: String
)

/**
 * Method A. The new phone signs a request with its device key. The old phone
 * signs a certificate with the account key only after an explicit approval.
 * The new phone accepts the certificate only when it matches the request it made.
 */
object DeviceEnrollment {

    fun canonical(
        accountId: String,
        meshPublicKey: String,
        devicePublicKey: String,
        deviceId: String,
        nonceB64: String,
        generation: Int
    ): ByteArray {
        val parts = listOf(
            "TM-ENROLL-v1",
            accountId,
            meshPublicKey,
            devicePublicKey,
            deviceId,
            nonceB64,
            generation.toString()
        )
        val out = java.io.ByteArrayOutputStream()
        parts.forEachIndexed { idx, s ->
            if (idx > 0) out.write(0x1F)
            out.write(s.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    fun createRequest(
        accountId: String,
        meshPublicKey: String,
        device: DeviceSigner,
        deviceId: String,
        nonce: ByteArray
    ): EnrollmentRequest {
        val nonceB64 = Base64Url.encode(nonce)
        val body = canonical(accountId, meshPublicKey, device.publicKeyBase64, deviceId, nonceB64, generation = 0)
        return EnrollmentRequest(
            accountId = accountId,
            meshPublicKey = meshPublicKey,
            devicePublicKey = device.publicKeyBase64,
            deviceId = deviceId,
            nonceB64 = nonceB64,
            deviceSignature = device.sign(body)
        )
    }

    fun approve(
        request: EnrollmentRequest,
        account: CryptoIdentity,
        localAccountId: String,
        generation: Int,
        userApproved: Boolean
    ): DeviceCertificate? {
        if (!userApproved) return null
        if (request.accountId != localAccountId) return null
        if (request.meshPublicKey.isBlank() || request.devicePublicKey.isBlank() || request.nonceB64.isBlank()) {
            return null
        }
        val body = canonical(
            request.accountId,
            request.meshPublicKey,
            request.devicePublicKey,
            request.deviceId,
            request.nonceB64,
            generation = 0
        )
        if (!CryptoIdentity.verify(request.devicePublicKey, body, request.deviceSignature)) return null
        val certBody = canonical(
            request.accountId,
            request.meshPublicKey,
            request.devicePublicKey,
            request.deviceId,
            request.nonceB64,
            generation
        )
        return DeviceCertificate(
            accountId = request.accountId,
            meshPublicKey = request.meshPublicKey,
            devicePublicKey = request.devicePublicKey,
            deviceId = request.deviceId,
            nonceB64 = request.nonceB64,
            generation = generation,
            accountSignature = account.sign(certBody)
        )
    }

    fun accept(
        certificate: DeviceCertificate,
        accountPublicKey: String,
        expected: EnrollmentRequest
    ): Boolean {
        if (certificate.accountId != expected.accountId) return false
        if (certificate.meshPublicKey != expected.meshPublicKey) return false
        if (certificate.devicePublicKey != expected.devicePublicKey) return false
        if (certificate.deviceId != expected.deviceId) return false
        if (certificate.nonceB64 != expected.nonceB64) return false
        val body = canonical(
            certificate.accountId,
            certificate.meshPublicKey,
            certificate.devicePublicKey,
            certificate.deviceId,
            certificate.nonceB64,
            certificate.generation
        )
        return CryptoIdentity.verify(accountPublicKey, body, certificate.accountSignature)
    }
}

/** Enrollment QR. Deliberately not [com.tharmesh.identity.QrCodec]'s invite form. */
object EnrollmentQr {
    const val REQUEST_PREFIX = "tharmesh://enroll"
    const val CERT_PREFIX = "tharmesh://enroll-cert"

    fun encodeRequest(request: EnrollmentRequest): String {
        return REQUEST_PREFIX + "?" + listOf(
            "acct" to request.accountId,
            "mesh" to request.meshPublicKey,
            "dev" to request.devicePublicKey,
            "did" to request.deviceId,
            "nonce" to request.nonceB64,
            "sig" to request.deviceSignature
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
    }

    fun decodeRequest(raw: String): EnrollmentRequest? {
        if (!raw.startsWith("$REQUEST_PREFIX?")) return null
        val q = query(raw)
        val accountId = q["acct"] ?: return null
        val mesh = q["mesh"] ?: return null
        val dev = q["dev"] ?: return null
        val did = q["did"] ?: return null
        val nonce = q["nonce"] ?: return null
        val sig = q["sig"] ?: return null
        if (accountId.isBlank() || mesh.isBlank() || sig.isBlank()) return null
        return EnrollmentRequest(accountId, mesh, dev, did, nonce, sig)
    }

    fun encodeCertificate(certificate: DeviceCertificate): String {
        return CERT_PREFIX + "?" + listOf(
            "acct" to certificate.accountId,
            "mesh" to certificate.meshPublicKey,
            "dev" to certificate.devicePublicKey,
            "did" to certificate.deviceId,
            "nonce" to certificate.nonceB64,
            "gen" to certificate.generation.toString(),
            "sig" to certificate.accountSignature
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
    }

    fun decodeCertificate(raw: String): DeviceCertificate? {
        if (!raw.startsWith("$CERT_PREFIX?")) return null
        val q = query(raw)
        return try {
            DeviceCertificate(
                accountId = q["acct"] ?: return null,
                meshPublicKey = q["mesh"] ?: return null,
                devicePublicKey = q["dev"] ?: return null,
                deviceId = q["did"] ?: return null,
                nonceB64 = q["nonce"] ?: return null,
                generation = q["gen"]?.toInt() ?: return null,
                accountSignature = q["sig"] ?: return null
            )
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun query(raw: String): Map<String, String> {
        val q = raw.substringAfter('?', "")
        val out = HashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq < 0) pair else pair.substring(0, eq)
            val v = if (eq < 0) "" else pair.substring(eq + 1)
            out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return out
    }
}
