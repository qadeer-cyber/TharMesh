// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer (Qadeer Cyber). All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.account

import java.security.MessageDigest
import kotlin.math.min

/**
 * RFC 7914 scrypt and the PBKDF2-HMAC-SHA256 it is built on.
 *
 * PBKDF2 is implemented here, not via [javax.crypto.SecretKeyFactory], because
 * `PBKDF2WithHmacSHA256` is not available on Android API 24–25. HMAC-SHA256 is.
 */
internal object Scrypt {

    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations >= 1)
        require(dkLen > 0)
        val hLen = 32
        val out = ByteArray(dkLen)
        var offset = 0
        var blockIndex = 1
        while (offset < dkLen) {
            val block = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, block, 0, salt.size)
            block[salt.size] = (blockIndex ushr 24).toByte()
            block[salt.size + 1] = (blockIndex ushr 16).toByte()
            block[salt.size + 2] = (blockIndex ushr 8).toByte()
            block[salt.size + 3] = blockIndex.toByte()
            var u = hmacSha256(password, block)
            val t = u.copyOf()
            var iter = 1
            while (iter < iterations) {
                u = hmacSha256(password, u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
                iter++
            }
            val n = min(hLen, dkLen - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            blockIndex++
        }
        return out
    }

    /** HMAC-SHA256. An empty key is valid (RFC 2104); JCE SecretKeySpec rejects it. */
    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val blockLen = 64
        var k = if (key.size > blockLen) MessageDigest.getInstance("SHA-256").digest(key) else key
        if (k.size < blockLen) {
            val padded = ByteArray(blockLen)
            System.arraycopy(k, 0, padded, 0, k.size)
            k = padded
        }
        val ipad = ByteArray(blockLen) { (k[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(blockLen) { (k[it].toInt() xor 0x5c).toByte() }
        val inner = MessageDigest.getInstance("SHA-256")
        inner.update(ipad)
        inner.update(data)
        val outer = MessageDigest.getInstance("SHA-256")
        outer.update(opad)
        outer.update(inner.digest())
        return outer.digest()
    }

    fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        require(n > 1 && n and (n - 1) == 0) { "scrypt N must be a power of two" }
        require(r >= 1 && p >= 1 && dkLen >= 1)
        val block = 128 * r
        val mixed = pbkdf2(password, salt, 1, p * block)
        var i = 0
        while (i < p) {
            val chunk = mixed.copyOfRange(i * block, (i + 1) * block)
            sMix(chunk, r, n)
            System.arraycopy(chunk, 0, mixed, i * block, block)
            i++
        }
        return pbkdf2(password, mixed, 1, dkLen)
    }

    private fun sMix(block: ByteArray, r: Int, n: Int) {
        val len = 128 * r
        val x = block.copyOf()
        val v = Array(n) { ByteArray(len) }
        for (i in 0 until n) {
            System.arraycopy(x, 0, v[i], 0, len)
            blockMix(x, r)
        }
        val mask = n.toLong() - 1L
        for (i in 0 until n) {
            val j = (integerify(x, r) and mask).toInt()
            xorInto(x, 0, v[j], 0, len)
            blockMix(x, r)
        }
        System.arraycopy(x, 0, block, 0, len)
    }

    /** Writes BlockMix_salsa20/8(block) back into [block]. */
    private fun blockMix(block: ByteArray, r: Int) {
        val y = ByteArray(128 * r)
        val x = ByteArray(64)
        System.arraycopy(block, (2 * r - 1) * 64, x, 0, 64)
        for (i in 0 until (2 * r)) {
            xorInto(x, 0, block, i * 64, 64)
            salsa20Core8(x)
            System.arraycopy(x, 0, y, i * 64, 64)
        }
        for (i in 0 until r) {
            System.arraycopy(y, (i * 2) * 64, block, i * 64, 64)
        }
        for (i in 0 until r) {
            System.arraycopy(y, (i * 2 + 1) * 64, block, (i + r) * 64, 64)
        }
    }

    private fun integerify(block: ByteArray, r: Int): Long {
        val off = (2 * r - 1) * 64
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((block[off + i].toLong() and 0xffL) shl (8 * i))
        }
        return v
    }

    private fun xorInto(dest: ByteArray, destOff: Int, src: ByteArray, srcOff: Int, len: Int) {
        for (i in 0 until len) {
            dest[destOff + i] = (dest[destOff + i].toInt() xor src[srcOff + i].toInt()).toByte()
        }
    }

    private fun salsa20Core8(b: ByteArray) {
        val x = IntArray(16)
        val w = IntArray(16)
        for (i in 0 until 16) {
            val word = loadLe32(b, i * 4)
            w[i] = word
            x[i] = word
        }
        var rounds = 8
        while (rounds > 0) {
            x[4] = x[4] xor rotl(x[0] + x[12], 7)
            x[8] = x[8] xor rotl(x[4] + x[0], 9)
            x[12] = x[12] xor rotl(x[8] + x[4], 13)
            x[0] = x[0] xor rotl(x[12] + x[8], 18)

            x[9] = x[9] xor rotl(x[5] + x[1], 7)
            x[13] = x[13] xor rotl(x[9] + x[5], 9)
            x[1] = x[1] xor rotl(x[13] + x[9], 13)
            x[5] = x[5] xor rotl(x[1] + x[13], 18)

            x[14] = x[14] xor rotl(x[10] + x[6], 7)
            x[2] = x[2] xor rotl(x[14] + x[10], 9)
            x[6] = x[6] xor rotl(x[2] + x[14], 13)
            x[10] = x[10] xor rotl(x[6] + x[2], 18)

            x[3] = x[3] xor rotl(x[15] + x[11], 7)
            x[7] = x[7] xor rotl(x[3] + x[15], 9)
            x[11] = x[11] xor rotl(x[7] + x[3], 13)
            x[15] = x[15] xor rotl(x[11] + x[7], 18)

            x[1] = x[1] xor rotl(x[0] + x[3], 7)
            x[2] = x[2] xor rotl(x[1] + x[0], 9)
            x[3] = x[3] xor rotl(x[2] + x[1], 13)
            x[0] = x[0] xor rotl(x[3] + x[2], 18)

            x[6] = x[6] xor rotl(x[5] + x[4], 7)
            x[7] = x[7] xor rotl(x[6] + x[5], 9)
            x[4] = x[4] xor rotl(x[7] + x[6], 13)
            x[5] = x[5] xor rotl(x[4] + x[7], 18)

            x[11] = x[11] xor rotl(x[10] + x[9], 7)
            x[8] = x[8] xor rotl(x[11] + x[10], 9)
            x[9] = x[9] xor rotl(x[8] + x[11], 13)
            x[10] = x[10] xor rotl(x[9] + x[8], 18)

            x[12] = x[12] xor rotl(x[15] + x[14], 7)
            x[13] = x[13] xor rotl(x[12] + x[15], 9)
            x[14] = x[14] xor rotl(x[13] + x[12], 13)
            x[15] = x[15] xor rotl(x[14] + x[13], 18)
            rounds -= 2
        }
        for (i in 0 until 16) {
            storeLe32(b, i * 4, w[i] + x[i])
        }
    }

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun loadLe32(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)
    }

    private fun storeLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }
}
