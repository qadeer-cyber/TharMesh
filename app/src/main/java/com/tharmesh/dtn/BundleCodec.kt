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
    val status: String
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
                status = json.optString("status", "PENDING")
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
