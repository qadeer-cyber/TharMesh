package com.tharmesh.dtn

import org.json.JSONArray
import org.json.JSONObject

data class MeshBundle(
    val bundleId: String,
    val srcId: String,
    val destId: String,
    val payloadCiphertext: String,
    val ttlUntil: Long,
    val hopCount: Int,
    val maxHops: Int,
    val signature: String,
    val status: String,
    val attemptCount: Int,
    val nextRetryAt: Long
)

object BundleCodec {

    fun encode(bundle: MeshBundle): String {
        val json = JSONObject()
        json.put("bundleId", bundle.bundleId)
        json.put("srcId", bundle.srcId)
        json.put("destId", bundle.destId)
        json.put("payload", bundle.payloadCiphertext)
        json.put("ttl", bundle.ttlUntil)
        json.put("hopCount", bundle.hopCount)
        json.put("maxHops", bundle.maxHops)
        json.put("sig", bundle.signature)
        json.put("status", bundle.status)
        json.put("attemptCount", bundle.attemptCount)
        json.put("nextRetryAt", bundle.nextRetryAt)
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
                hopCount = json.optInt("hopCount", 0),
                maxHops = json.optInt("maxHops", 8),
                signature = json.optString("sig", ""),
                status = json.optString("status", "QUEUED"),
                attemptCount = json.optInt("attemptCount", 0),
                nextRetryAt = json.optLong("nextRetryAt", 0L)
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
