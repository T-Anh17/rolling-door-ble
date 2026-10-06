package com.trananh.rollingdoor.protocol

// QR text printed by the firmware: RDOOR1:<BLE MAC, 12 hex>:<secret, base32>.
class SetupCode(
    val mac: String,      // "AA:BB:CC:DD:EE:FF", the format BluetoothAdapter expects
    val secret: ByteArray, // DoorProtocol.SECRET_LENGTH bytes
) {
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
