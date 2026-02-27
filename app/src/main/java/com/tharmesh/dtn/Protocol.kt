package com.tharmesh.dtn

import org.json.JSONArray
import org.json.JSONObject

enum class ProtocolType {
    HELLO,
    INV,
    HAVE,
    GET,
    BUNDLE,
    ACK_RELAY,
    ACK_FINAL
}

data class HelloPacket(val userId: String, val name: String, val pubKeyHash: String)
data class InvPacket(val bundleIds: List<String>)
data class HavePacket(val bundleIds: List<String>)
data class GetPacket(val bundleIds: List<String>)
data class AckRelayPacket(val bundleId: String, val relayUserId: String, val ts: Long, val sig: String)
data class AckFinalPacket(val bundleId: String, val toUserId: String, val ts: Long, val sig: String)

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

    fun encodeInv(packet: InvPacket): String = encodeIds(packet.bundleIds)
    fun encodeHave(packet: HavePacket): String = encodeIds(packet.bundleIds)
    fun encodeGet(packet: GetPacket): String = encodeIds(packet.bundleIds)

    fun encodeAckRelay(packet: AckRelayPacket): String {
        val json = JSONObject()
        json.put("bundleId", packet.bundleId)
        json.put("relayUserId", packet.relayUserId)
        json.put("ts", packet.ts)
        json.put("sig", packet.sig)
        return json.toString()
    }

    fun encodeAckFinal(packet: AckFinalPacket): String {
        val json = JSONObject()
        json.put("bundleId", packet.bundleId)
        json.put("toUserId", packet.toUserId)
        json.put("ts", packet.ts)
        json.put("sig", packet.sig)
        return json.toString()
    }

    private fun encodeIds(ids: List<String>): String {
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        return arr.toString()
    }

    fun decodeIds(payload: String): List<String> {
        return try {
            val arr = JSONArray(payload)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotBlank()) out.add(id)
            }
            out
        } catch (ignored: Throwable) {
            emptyList()
        }
    }

    fun decodeAckRelay(payload: String): AckRelayPacket? {
        return try {
            val json = JSONObject(payload)
            AckRelayPacket(
                bundleId = json.getString("bundleId"),
                relayUserId = json.optString("relayUserId", ""),
                ts = json.optLong("ts", 0L),
                sig = json.optString("sig", "")
            )
        } catch (ignored: Throwable) {
            null
        }
    }

    fun decodeAckFinal(payload: String): AckFinalPacket? {
        return try {
            val json = JSONObject(payload)
            AckFinalPacket(
                bundleId = json.getString("bundleId"),
                toUserId = json.optString("toUserId", ""),
                ts = json.optLong("ts", 0L),
                sig = json.optString("sig", "")
            )
        } catch (ignored: Throwable) {
            null
        }
    }
}
