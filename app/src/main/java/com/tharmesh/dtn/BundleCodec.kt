package com.tharmesh.dtn

import org.json.JSONArray
import org.json.JSONObject

data class MeshBundle(
    val bundleId: String,
    val srcId: String,
    val destId: String,
    val payloadCiphertext: String,
    val ttlUntil: Long,
    val hopsLeft: Int,
    val signature: String,
    val status: String,
    /**
     * Base64(X.509 DER) of the originator's ECDSA P-256 public key. Empty when the
     * bundle predates Stage 4.6 (legacy-compat path only) or came from an engine
     * constructed without a [com.tharmesh.identity.CryptoIdentity]. Used by
     * receivers to verify [signature] against the canonical signing blob (see
     * [com.tharmesh.identity.CryptoIdentity.canonicalBundleBytes]) and to feed the
     * trust-on-first-use store ([com.tharmesh.identity.PeerTrustStore]).
     */
    val srcPubKey: String = ""
)

object BundleCodec {

    fun encode(bundle: MeshBundle): String {
        val json = JSONObject()
        json.put("bundleId", bundle.bundleId)
        json.put("srcId", bundle.srcId)
        json.put("destId", bundle.destId)
        json.put("payload", bundle.payloadCiphertext)
        json.put("ttl", bundle.ttlUntil)
        json.put("hops", bundle.hopsLeft)
        json.put("sig", bundle.signature)
        json.put("status", bundle.status)
        // Only serialize srcPubKey when we actually have one — keeps legacy wire
        // format byte-identical for pre-Stage-4.6 bundles and lets receivers
        // distinguish "signed bundle whose pubkey is missing" (reject) from
        // "legacy unsigned bundle" (accept only under allowLegacyUnsigned).
        if (bundle.srcPubKey.isNotEmpty()) {
            json.put("pk", bundle.srcPubKey)
        }
        return json.toString()
    }

    fun decode(raw: String): MeshBundle? {
        return try {
            val json = JSONObject(raw)
            MeshBundle(
                bundleId = json.optString("bundleId", ""),
                srcId = json.optString("srcId", ""),
                destId = json.optString("destId", ""),
                payloadCiphertext = json.optString("payload", ""),
                ttlUntil = json.optLong("ttl", 0L),
                hopsLeft = json.optInt("hops", 0),
                signature = json.optString("sig", ""),
                status = json.optString("status", "PENDING"),
                srcPubKey = json.optString("pk", "")
            )
        } catch (ignored: Throwable) {
            null
        }
    }

    fun encodeInventory(bundleIds: List<String>): String {
        val arr = JSONArray()
        for (id in bundleIds) {
            arr.put(id)
        }
        return arr.toString()
    }

    fun decodeInventory(raw: String): List<String> {
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                out.add(arr.optString(i))
            }
            out
        } catch (ignored: Throwable) {
            emptyList()
        }
    }
}
