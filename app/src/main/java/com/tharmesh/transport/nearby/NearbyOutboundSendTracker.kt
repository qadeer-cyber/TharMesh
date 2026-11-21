// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.transport.nearby

import com.tharmesh.transport.TransportEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Correlates Nearby outbound payload IDs to engine sendIds. Emits
 * [TransportEvent.PayloadSent] only after the Nearby layer reports a *completed*
 * transfer, not when `sendPayload` merely queues bytes (JVM-testable without
 * Play Services).
 *
 * Prefer [onTransferSuccess] / [onTransferFailure] from the transport: they take
 * no status code, so the caller cannot mis-map a Play Services constant. The
 * numeric [onPayloadTransferUpdate] entry point exists for callers that only
 * have the raw `PayloadTransferUpdate.status` int; its mirrors in [Companion]
 * must match `com.google.android.gms.nearby.connection.PayloadTransferUpdate.Status`
 * exactly (SUCCESS = 1, FAILURE = 2, IN_PROGRESS = 3, CANCELED = 4).
 */
class NearbyOutboundSendTracker {
    private data class Outbound(val peerId: String, val sendId: Long, val totalBytes: Long)

    private val byPayloadId: MutableMap<Long, Outbound> = ConcurrentHashMap()
    private val confirmedPayloadIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun register(payloadId: Long, peerId: String, sendId: Long, totalBytes: Int) {
        if (sendId == 0L) return
        byPayloadId[payloadId] = Outbound(peerId, sendId, totalBytes.toLong())
    }

    fun remove(payloadId: Long) {
        byPayloadId.remove(payloadId)
    }

    /**
     * Status-code-free success path. Call this only from the branch that already
     * matched `PayloadTransferUpdate.Status.SUCCESS`.
     *
     * @return PayloadSent when the transfer fully completed for a registered
     *   outbound payload; null if the payload is unknown (e.g. an inbound
     *   transfer), the byte counts say the transfer is short, or SUCCESS was
     *   already observed for this payload.
     */
    fun onTransferSuccess(
        payloadId: Long,
        bytesTransferred: Long,
        totalBytes: Long
    ): TransportEvent.PayloadSent? {
        val outbound = byPayloadId[payloadId] ?: return null
        if (totalBytes > 0L && bytesTransferred < totalBytes) return null
        if (!confirmedPayloadIds.add(payloadId)) return null
        byPayloadId.remove(payloadId)
        val bytes = totalBytes.takeIf { it > 0 } ?: outbound.totalBytes
        return TransportEvent.PayloadSent(outbound.peerId, outbound.sendId, bytes.toInt())
    }

    /**
     * Numeric form of [onTransferSuccess] for callers holding a raw
     * `PayloadTransferUpdate.status`. Anything other than [STATUS_SUCCESS] —
     * including [STATUS_IN_PROGRESS] — returns null and emits nothing.
     */
    fun onPayloadTransferUpdate(
        payloadId: Long,
        status: Int,
        bytesTransferred: Long,
        totalBytes: Long
    ): TransportEvent.PayloadSent? {
        if (status != STATUS_SUCCESS) return null
        return onTransferSuccess(payloadId, bytesTransferred, totalBytes)
    }

    fun inflightCount(): Int = byPayloadId.size

    fun onTransferFailure(payloadId: Long, reason: String): TransportEvent.Error? {
        val outbound = byPayloadId.remove(payloadId) ?: return null
        return TransportEvent.Error(outbound.peerId, outbound.sendId, reason)
    }

    companion object {
        /**
         * Mirrors of `com.google.android.gms.nearby.connection.PayloadTransferUpdate.Status`.
         * Verified against the Play Services reference: SUCCESS = 1, FAILURE = 2,
         * IN_PROGRESS = 3, CANCELED = 4. A mismatch here silently stops every
         * delivery receipt, so [NearbyOutboundSendTrackerStatusContractTest] and
         * [NearbyConnectionsTransport]'s compile-time use of the real constants
         * both guard it.
         */
        const val STATUS_SUCCESS: Int = 1
        const val STATUS_FAILURE: Int = 2
        const val STATUS_IN_PROGRESS: Int = 3
        const val STATUS_CANCELED: Int = 4
    }
}
