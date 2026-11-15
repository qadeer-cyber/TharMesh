// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary

package com.tharmesh.transport.nearby

import com.tharmesh.transport.TransportEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Correlates Nearby outbound payload IDs to engine sendIds. Emits
 * [TransportEvent.PayloadSent] only after [PayloadTransferUpdate.Status.SUCCESS],
 * not when `sendPayload` merely queues bytes (JVM-testable without Play Services).
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
     * @return PayloadSent when the transfer fully succeeded; null if still in flight or unknown.
     */
    fun onPayloadTransferUpdate(
        payloadId: Long,
        status: Int,
        bytesTransferred: Long,
        totalBytes: Long
    ): TransportEvent.PayloadSent? {
        val outbound = byPayloadId[payloadId] ?: return null
        if (status != STATUS_SUCCESS) return null
        if (totalBytes > 0L && bytesTransferred < totalBytes) return null
        if (!confirmedPayloadIds.add(payloadId)) return null
        byPayloadId.remove(payloadId)
        val bytes = totalBytes.takeIf { it > 0 } ?: outbound.totalBytes
        return TransportEvent.PayloadSent(outbound.peerId, outbound.sendId, bytes.toInt())
    }

    fun inflightCount(): Int = byPayloadId.size

    fun onTransferFailure(payloadId: Long, reason: String): TransportEvent.Error? {
        val outbound = byPayloadId.remove(payloadId) ?: return null
        return TransportEvent.Error(outbound.peerId, outbound.sendId, reason)
    }

    companion object {
        /** Mirrors [com.google.android.gms.nearby.connection.PayloadTransferUpdate.Status.SUCCESS]. */
        const val STATUS_SUCCESS: Int = 3
        const val STATUS_FAILURE: Int = 2
    }
}