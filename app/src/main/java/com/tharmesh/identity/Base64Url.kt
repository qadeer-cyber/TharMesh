package com.tharmesh.identity

/**
 * Minimal standard-alphabet (RFC 4648 §4) Base64 codec. We can NOT use
 * `android.util.Base64` because [com.tharmesh] unit tests run with
 * `unitTests.returnDefaultValues = true` in app/build.gradle, which means all
 * `android.util.*` calls return null/0 — signatures would silently come back as
 * empty strings in tests and give false-positive "sig verified" outcomes.
 *
 * `java.util.Base64` is not available on our minSdk 24 runtime either (it needs
 * API 26), so a hand-rolled codec is the simplest path that is correct on every
 * target: Kotlin/JVM unit tests, Android API 24+, and the macOS High Sierra
 * dev toolchain.
 *
 * Output is a single line with no line breaks (we never need the MIME variant).
 * Decode is lenient on whitespace so callers can round-trip through logs.
 */
internal object Base64Url {

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE_TABLE: IntArray = IntArray(128) { -1 }.also { table ->
        for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder(4 * ((bytes.size + 2) / 3))
        var i = 0
        while (i + 2 < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt() and 0xFF
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(ALPHABET[(triple shr 18) and 0x3F])
            out.append(ALPHABET[(triple shr 12) and 0x3F])
            out.append(ALPHABET[(triple shr 6) and 0x3F])
            out.append(ALPHABET[triple and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val b0 = bytes[i].toInt() and 0xFF
                out.append(ALPHABET[(b0 shr 2) and 0x3F])
                out.append(ALPHABET[(b0 shl 4) and 0x3F])
                out.append('=')
                out.append('=')
            }
            2 -> {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = bytes[i + 1].toInt() and 0xFF
                out.append(ALPHABET[(b0 shr 2) and 0x3F])
                out.append(ALPHABET[((b0 shl 4) or (b1 shr 4)) and 0x3F])
                out.append(ALPHABET[(b1 shl 2) and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }

    /** Returns the decoded bytes, or null if [s] is not valid base64. */
    fun decode(s: String): ByteArray? {
        if (s.isEmpty()) return ByteArray(0)
        // Strip whitespace (tolerate pretty-printed / log-copied inputs).
        val clean = StringBuilder(s.length)
        for (c in s) if (!c.isWhitespace()) clean.append(c)
        val str = clean.toString()
        if (str.length % 4 != 0) return null
        val pad = when {
            str.endsWith("==") -> 2
            str.endsWith("=") -> 1
            else -> 0
        }
        val outLen = (str.length / 4) * 3 - pad
        val out = ByteArray(outLen)
        var oi = 0
        var i = 0
        while (i < str.length) {
            val c0 = str[i].code
            val c1 = str[i + 1].code
            val c2 = str[i + 2].code
            val c3 = str[i + 3].code
            if (c0 >= 128 || c1 >= 128 || c2 >= 128 || c3 >= 128) return null
            val d0 = DECODE_TABLE[c0]
            val d1 = DECODE_TABLE[c1]
            val d2 = if (str[i + 2] == '=') 0 else DECODE_TABLE[c2]
            val d3 = if (str[i + 3] == '=') 0 else DECODE_TABLE[c3]
            if (d0 < 0 || d1 < 0 || d2 < 0 || d3 < 0) return null
            val triple = (d0 shl 18) or (d1 shl 12) or (d2 shl 6) or d3
            if (oi < outLen) out[oi++] = ((triple shr 16) and 0xFF).toByte()
            if (oi < outLen) out[oi++] = ((triple shr 8) and 0xFF).toByte()
            if (oi < outLen) out[oi++] = (triple and 0xFF).toByte()
            i += 4
        }
        return out
    }
}
