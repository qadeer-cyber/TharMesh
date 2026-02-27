package com.tharmesh.dtn

import org.json.JSONArray
import org.json.JSONObject

enum class ProtocolType {
    HELLO,
    INV,
    GET,
    BUNDLE,
    ACK
}

data class HelloPacket(val userId: String, val name: String, val pubKeyHash: String)
data class InvPacket(val bundleIds: List<String>)
data class GetPacket(val bundleIds: List<String>)
data class AckPacket(val bundleId: String, val to: String, val ts: Long, val sig: String)

data class ProtocolFrame(
    val type: ProtocolType,
    val fromPeerId: String,
    val payload: String
)

object ProtocolCodec {
    fun encode(frame: ProtocolFrame): ByteArray {
        val json = JSONObject()
        json.put("type", frame.type.name)
        json.put("from", frame.fromPeerId)
        json.put("payload", frame.payload)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): ProtocolFrame? {
        return try {
            val raw = String(bytes, Charsets.UTF_8)
            val json = JSONObject(raw)
            ProtocolFrame(
                type = ProtocolType.valueOf(json.getString("type")),
                fromPeerId = json.getString("from"),
                payload = json.getString("payload")
            )
        } catch (ignored: Throwable) {
            null
        }
    }

    fun encodeHello(packet: HelloPacket): String {
        val json = JSONObject()
        json.put("userId", packet.userId)
        json.put("name", packet.name)
        json.put("pubKeyHash", packet.pubKeyHash)
        return json.toString()
    }

    fun encodeInv(packet: InvPacket): String {
        val arr = JSONArray()
        for (id in packet.bundleIds) arr.put(id)
        return arr.toString()
    }

    fun encodeGet(packet: GetPacket): String {
        val arr = JSONArray()
        for (id in packet.bundleIds) arr.put(id)
        return arr.toString()
    }

    fun encodeAck(packet: AckPacket): String {
        val json = JSONObject()
        json.put("bundleId", packet.bundleId)
        json.put("to", packet.to)
        json.put("ts", packet.ts)
        json.put("sig", packet.sig)
        return json.toString()
    }

    fun decodeIds(payload: String): List<String> {
        return try {
            val arr = JSONArray(payload)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            out
        } catch (ignored: Throwable) {
            emptyList()
        }
    }

    fun decodeAck(payload: String): AckPacket? {
        return try {
            val json = JSONObject(payload)
            AckPacket(
                bundleId = json.getString("bundleId"),
                to = json.optString("to", ""),
                ts = json.optLong("ts", 0L),
                sig = json.optString("sig", "")
            )
        } catch (ignored: Throwable) {
            null
        }
    }
}
