package com.tharmesh.dtn

import android.util.Log

/**
 * Relay-layer logging. Emits structured lines under tag "MeshRelay" so real-device
 * debugging of multi-hop flows via `adb logcat -s MeshRelay` is cheap and scannable.
 *
 * Lines are plain `key=value` pairs (no JSON) so `grep bundleId=...` works across
 * the 3 relay devices simultaneously. `android.util.Log` is a no-op stub in unit
 * tests (returns 0 without touching I/O) so these calls are free during
 * `testDebugUnitTest` too.
 */
internal object MeshLog {
    const val TAG: String = "MeshRelay"

    fun received(bundleId: String, fromPeer: String, destId: String, hopsLeft: Int, firstArrival: Boolean) {
        Log.d(TAG, "received bundleId=$bundleId from=$fromPeer dest=$destId hops=$hopsLeft first=$firstArrival")
    }

    fun droppedTtl(bundleId: String, fromPeer: String) {
        Log.d(TAG, "dropped bundleId=$bundleId from=$fromPeer reason=ttl_expired")
    }

    fun droppedHops(bundleId: String, fromPeer: String, hopsLeft: Int) {
        Log.d(TAG, "dropped bundleId=$bundleId from=$fromPeer reason=hops_exhausted hops=$hopsLeft")
    }

    fun droppedDuplicate(bundleId: String, fromPeer: String) {
        Log.d(TAG, "dropped bundleId=$bundleId from=$fromPeer reason=duplicate")
    }

    fun forwarded(bundleId: String, toPeer: String, hopsLeft: Int) {
        Log.d(TAG, "forwarded bundleId=$bundleId to=$toPeer hops=$hopsLeft")
    }

    fun delivered(bundleId: String) {
        Log.d(TAG, "delivered bundleId=$bundleId")
    }

    fun acked(bundleId: String, fromPeer: String) {
        Log.d(TAG, "acked bundleId=$bundleId from=$fromPeer")
    }

    fun sending(bundleId: String, toPeer: String) {
        Log.d(TAG, "sending bundleId=$bundleId to=$toPeer")
    }

    fun noConnectedPeers(bundleId: String) {
        Log.d(TAG, "queued bundleId=$bundleId reason=no_connected_peers")
    }

    fun skippedAntiSender(bundleId: String, peerId: String) {
        Log.d(TAG, "skipped bundleId=$bundleId peer=$peerId reason=anti_sender")
    }

    fun restored(count: Int) {
        Log.d(TAG, "restored count=$count source=persistent_bundle_store")
    }

    // --- Stage 4.6: signature + identity -----------------------------------

    fun identityReady(fingerprint: String) {
        Log.d(TAG, "identity_ready fp=$fingerprint")
    }

    fun bundleSigned(bundleId: String) {
        Log.d(TAG, "signed bundleId=$bundleId")
    }

    fun signatureVerified(bundleId: String, srcId: String, fp: String) {
        Log.d(TAG, "sig_verified bundleId=$bundleId src=$srcId fp=$fp")
    }

    fun signatureFailed(bundleId: String, srcId: String, reason: String) {
        Log.w(TAG, "sig_failed bundleId=$bundleId src=$srcId reason=$reason")
    }

    fun peerKeyFirstSeen(userId: String, fp: String) {
        Log.d(TAG, "peer_key_first_seen userId=$userId fp=$fp")
    }

    fun peerKeyMismatch(userId: String, storedFp: String, presentedFp: String) {
        Log.w(TAG, "peer_key_mismatch userId=$userId stored_fp=$storedFp presented_fp=$presentedFp")
    }
}
