// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import com.tharmesh.identity.CryptoIdentity

/**
 * Method A from QR text to an accepted certificate.
 * Both phones must confirm. The new phone accepts only a certificate that
 * matches the request it put in the QR.
 */
object DeviceTransfer {

    enum class Outcome {
        ACCEPTED,
        NEW_DEVICE_DECLINED,
        OLD_DEVICE_DECLINED,
        SUBSTITUTED_KEY,
        WRONG_ACCOUNT
    }

    data class Exchange(
        val outcome: Outcome,
        val requestQr: String?,
        val certificateQr: String?
    )

    fun exchange(
        account: CryptoIdentity,
        mesh: CryptoIdentity,
        device: DeviceSigner,
        accountId: String,
        newDeviceConfirms: Boolean,
        oldDeviceApproves: Boolean,
        substituteMeshKey: Boolean,
        presentedAccountId: String = accountId,
        nonce: ByteArray = ByteArray(16) { (it + 1).toByte() }
    ): Exchange {
        if (!newDeviceConfirms) {
            return Exchange(Outcome.NEW_DEVICE_DECLINED, null, null)
        }
        val request = DeviceEnrollment.createRequest(
            presentedAccountId, mesh.publicKeyBase64, device, mesh.fingerprint, nonce
        )
        val requestQr = EnrollmentQr.encodeRequest(request)
        val decoded = EnrollmentQr.decodeRequest(requestQr) ?: return Exchange(Outcome.OLD_DEVICE_DECLINED, requestQr, null)
        if (presentedAccountId != accountId) {
            val refused = DeviceEnrollment.approve(
                decoded, account, accountId, 1, userApproved = oldDeviceApproves
            )
            if (refused != null) return Exchange(Outcome.ACCEPTED, requestQr, null)
            return Exchange(Outcome.WRONG_ACCOUNT, requestQr, null)
        }
        if (!oldDeviceApproves) {
            val refused = DeviceEnrollment.approve(decoded, account, accountId, 1, userApproved = false)
            if (refused != null) return Exchange(Outcome.ACCEPTED, requestQr, null)
            return Exchange(Outcome.OLD_DEVICE_DECLINED, requestQr, null)
        }
        val cert = DeviceEnrollment.approve(decoded, account, accountId, 1, userApproved = true)
            ?: return Exchange(Outcome.OLD_DEVICE_DECLINED, requestQr, null)
        val published = if (substituteMeshKey) {
            cert.copy(meshPublicKey = CryptoIdentity.generate().publicKeyBase64)
        } else {
            cert
        }
        val certQr = EnrollmentQr.encodeCertificate(published)
        val parsed = EnrollmentQr.decodeCertificate(certQr) ?: return Exchange(Outcome.SUBSTITUTED_KEY, requestQr, certQr)
        val ok = DeviceEnrollment.accept(parsed, account.publicKeyBase64, request)
        return Exchange(
            outcome = if (ok) Outcome.ACCEPTED else Outcome.SUBSTITUTED_KEY,
            requestQr = requestQr,
            certificateQr = certQr
        )
    }
}
