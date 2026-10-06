package com.trananh.rollingdoor.crypto

// HMAC-SHA256 with the phone key. The real signer keeps the key inside Android Keystore,
// so the key itself is never handed to protocol code.
fun interface CommandSigner {
    fun hmacSha256(data: ByteArray): ByteArray
}
