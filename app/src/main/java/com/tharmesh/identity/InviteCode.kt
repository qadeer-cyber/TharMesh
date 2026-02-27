package com.tharmesh.identity

import java.util.Locale

object InviteCode {

    fun generate(userId: String): String {
        val normalized = userId.trim().uppercase(Locale.US)
        val checksum = checksum(normalized)
        return "$normalized-$checksum"
    }

    fun parse(code: String): ParsedInvite? {
        val cleaned = code.trim().uppercase(Locale.US)
        val parts = cleaned.split("-")
        if (parts.size < 2) {
            return null
        }
        val userId = parts.dropLast(1).joinToString("-")
        val expected = checksum(userId)
        val actual = parts.last()
        return if (expected == actual) ParsedInvite(userId, true) else ParsedInvite(userId, false)
    }

    private fun checksum(value: String): String {
        var acc = 0
        for (ch in value) {
            acc = (acc + ch.code) % 97
        }
        return acc.toString().padStart(2, '0')
    }
}

data class ParsedInvite(
    val userId: String,
    val isValid: Boolean
)
