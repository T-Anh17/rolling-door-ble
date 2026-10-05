package com.trananh.rollingdoor.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SetupCodeTest {
    // Secret 00 01 .. 0f, encoded with: python -c "import base64; print(base64.b32encode(bytes(range(16))).decode().rstrip('='))"
    private val validText = "RDOOR1:246F28A1B2C3:AAAQEAYEAUDAOCAJBIFQYDIOB4"

    @Test
    fun parsesValidCode() {
        val code = SetupCode.parse(validText)
        assertNotNull(code)
        assertEquals("24:6F:28:A1:B2:C3", code!!.mac)
        assertArrayEquals(ByteArray(16) { it.toByte() }, code.secret)
    }

    @Test
    fun acceptsSurroundingWhitespaceAndLowercase() {
        val code = SetupCode.parse("  ${validText.lowercase()}\n")
        assertEquals("24:6F:28:A1:B2:C3", code?.mac)
    }

    @Test
    fun rejectsWrongPrefix() {
        assertNull(SetupCode.parse(validText.replace("RDOOR1", "RDOOR2")))
        assertNull(SetupCode.parse(validText.removePrefix("RDOOR1:")))
    }

    @Test
    fun rejectsBadMac() {
        assertNull(SetupCode.parse("RDOOR1:246F28A1B2:AAAQEAYEAUDAOCAJBIFQYDIOB4"))
        assertNull(SetupCode.parse("RDOOR1:246F28A1B2CG:AAAQEAYEAUDAOCAJBIFQYDIOB4"))
    }

    @Test
    fun rejectsWrongSecretLength() {
        assertNull(SetupCode.parse("RDOOR1:246F28A1B2C3:AAAQEAYEAUDAOCAJBIFQYDIO"))
        assertNull(SetupCode.parse("RDOOR1:246F28A1B2C3:AAAQEAYEAUDAOCAJBIFQYDIOB4AA"))
        assertNull(SetupCode.parse("RDOOR1:246F28A1B2C3:"))
    }

    @Test
    fun rejectsBadBase32() {
        // 0, 1, 8, 9 are not in the RFC 4648 alphabet; padding is never written.
        assertNull(SetupCode.parse("RDOOR1:246F28A1B2C3:AAAQEAYEAUDAOCAJBIFQYDIOB1"))
        assertNull(SetupCode.parse("RDOOR1:246F28A1B2C3:AAAQEAYEAUDAOCAJBIFQYDIOB4======"))
        // Last character carries 2 spare bits that must be zero: B4 -> B7 sets them.
        assertNull(SetupCode.parse("RDOOR1:246F28A1B2C3:AAAQEAYEAUDAOCAJBIFQYDIOB7"))
    }

    @Test
    fun rejectsExtraFields() {
        assertNull(SetupCode.parse("$validText:00"))
    }

    @Test
    fun base32MatchesFirmwareEncoding() {
        // Every byte value round-trips through the encoder in device_secret.cpp.
        val data = ByteArray(256) { it.toByte() }
        assertArrayEquals(data, Base32.decode(encode(data)))
    }

    // Port of base32() in device_secret.cpp.
    private fun encode(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var buffer = 0L
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(alphabet[((buffer shr (bits - 5)) and 0x1F).toInt()])
                bits -= 5
            }
        }
        if (bits > 0) out.append(alphabet[((buffer shl (5 - bits)) and 0x1F).toInt()])
        return out.toString()
    }
}
