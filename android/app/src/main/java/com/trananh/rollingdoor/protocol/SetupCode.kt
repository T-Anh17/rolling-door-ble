package com.trananh.rollingdoor.protocol

// QR text printed by the firmware: RDOOR1:<BLE MAC, 12 hex>:<secret, base32>. The admin's
// invite QR code has the same form, with the secret derived from the invite's digits.
class SetupCode(
    val mac: String?,      // "AA:BB:CC:DD:EE:FF", the format BluetoothAdapter expects; null for
                           // typed invite digits: pairing then looks for a device with pairing open
    val secret: ByteArray, // DoorProtocol.SECRET_LENGTH bytes
) {
    // The QR text for this code. Needs the MAC.
    fun format(): String {
        val hex = checkNotNull(mac) { "no MAC" }.replace(":", "")
        return "$PREFIX:$hex:${Base32.encode(secret)}"
    }

    companion object {
        private const val PREFIX = "RDOOR1"
        private val MAC_HEX = Regex("[0-9A-F]{12}")

        // Returns null for anything that is not a well-formed setup code.
        fun parse(text: String): SetupCode? {
            val parts = text.trim().uppercase().split(':')
            if (parts.size != 3 || parts[0] != PREFIX || !MAC_HEX.matches(parts[1])) return null
            val secret = Base32.decode(parts[2]) ?: return null
            if (secret.size != DoorProtocol.SECRET_LENGTH) return null
            return SetupCode(parts[1].chunked(2).joinToString(":"), secret)
        }
    }
}

// RFC 4648 base32 without padding, as written by device_secret.cpp.
internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    // Rejects unknown characters and non-zero trailing bits, so every value has one spelling.
    fun decode(text: String): ByteArray? {
        val out = ArrayList<Byte>(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in text) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.add((buffer shr (bits - 8)).toByte())
                bits -= 8
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits >= 5 || buffer != 0) return null
        return out.toByteArray()
    }
}
