package com.trananh.rollingdoor.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// HKDF-SHA256 (RFC 5869), same as mbedtls_hkdf on the firmware.
object Hkdf {
    private const val HASH_LENGTH = 32

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LENGTH) { "invalid HKDF length: $length" }
        // An empty salt means HashLen zero bytes; SecretKeySpec rejects an empty key.
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt, ikm)
        val okm = ByteArray(length)
        var block = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            block = hmacSha256(prk, block + info + counter.toByte())
            val n = minOf(block.size, length - offset)
            block.copyInto(okm, offset, 0, n)
            offset += n
            counter++
        }
        prk.fill(0)
        block.fill(0)
        return okm
    }
}

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }
