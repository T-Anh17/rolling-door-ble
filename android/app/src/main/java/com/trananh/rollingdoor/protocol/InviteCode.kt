package com.trananh.rollingdoor.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

// The admin's invite for a new phone: 8 digits the board makes, valid once for 5 minutes. The
// new phone types them or scans the admin's QR code; both stand in for the device's setup secret.
// Mirror of firmware pairing.cpp.
object InviteCode {
    const val DIGITS = 8
    const val VALID_MS = 5 * 60 * 1000L

    // PAIRING read after INVITE: [0x02][salt 16][digits XOR mask 8].
    const val RESPONSE: Byte = 0x02
    const val RESPONSE_LENGTH = 1 + DoorProtocol.NONCE_LENGTH + DIGITS
    private val LABEL = "RDINVITE".encodeToByteArray()

    // The digits from what the user typed, allowing spaces and dashes; null if it is not 8 digits.
    fun normalize(text: String): String? {
        val digits = text.filterNot { it.isWhitespace() || it == '-' }
        return digits.takeIf { it.length == DIGITS && it.all { c -> c in '0'..'9' } }
    }

    // "1234 5678", easier to read out and type.
    fun display(digits: String): String = digits.chunked(DIGITS / 2).joinToString(" ")

    // The pairing secret: HMAC-SHA256(key = the digits in ASCII, "RDINVITE"), first 16 bytes.
    fun secret(digits: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(digits.encodeToByteArray(), "HmacSHA256"))
        return mac.doFinal(LABEL).copyOf(DoorProtocol.SECRET_LENGTH)
    }

    // The salt the mask is computed from, or null if value is not an invite response.
    fun salt(value: ByteArray): ByteArray? =
        if (value.size == RESPONSE_LENGTH && value[0] == RESPONSE) value.copyOfRange(1, 1 + DoorProtocol.NONCE_LENGTH) else null

    // Message for the mask: HMAC-SHA256(admin key, "RDINVITE" + salt), which the phone key signs.
    fun maskMessage(salt: ByteArray): ByteArray = LABEL + salt

    // Unmasks the digits with the mask's first 8 bytes; null if they are not digits.
    fun unmask(value: ByteArray, mask: ByteArray): String? {
        val masked = value.copyOfRange(1 + DoorProtocol.NONCE_LENGTH, RESPONSE_LENGTH)
        val digits = ByteArray(DIGITS) { masked[it] xor mask[it] }.decodeToString()
        return normalize(digits)
    }
}
