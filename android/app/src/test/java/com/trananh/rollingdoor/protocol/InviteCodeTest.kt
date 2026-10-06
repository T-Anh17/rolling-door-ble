package com.trananh.rollingdoor.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteCodeTest {
    @Test
    fun normalizesTypedDigits() {
        assertEquals("12345678", InviteCode.normalize("1234 5678"))
        assertEquals("12345678", InviteCode.normalize(" 1234-5678 "))
        assertNull(InviteCode.normalize("1234567"))
        assertNull(InviteCode.normalize("123456789"))
        assertNull(InviteCode.normalize("1234567a"))
        assertNull(InviteCode.normalize("RDOOR1:001122334455:AAAA"))
    }

    @Test
    fun displaysInTwoGroups() {
        assertEquals("1234 5678", InviteCode.display("12345678"))
    }

    @Test
    fun secretMatchesFirmware() {
        // HMAC-SHA256(key = "12345678", "RDINVITE"), first 16 bytes, as pairing.cpp derives it.
        assertArrayEquals(hex("20e14230c898cb0825997913a40d5d35"), InviteCode.secret("12345678"))
    }

    @Test
    fun unmasksDigitsWithTheAdminKeysMask() {
        // Fake admin key 07 x 32 and salt 00..0F; the mask is HMAC-SHA256(key, "RDINVITE" + salt).
        val value = hex("02" + "000102030405060708090a0b0c0d0e0f" + "f78a51ae740fd2b6")
        val salt = InviteCode.salt(value)
        assertArrayEquals(hex("000102030405060708090a0b0c0d0e0f"), salt)
        assertArrayEquals("RDINVITE".encodeToByteArray() + salt!!, InviteCode.maskMessage(salt))
        val mask = hex("c3b2639f4436e18153b494b4541102d40d9b0f7eee3d33cedbf29048d7373cf3")
        assertEquals("48210937", InviteCode.unmask(value, mask))
    }

    @Test
    fun otherPairingValuesAreNotInvites() {
        assertNull(InviteCode.salt(ByteArray(0)))
        assertNull(InviteCode.salt(hex("00" + "00".repeat(24))))
        assertNull(InviteCode.salt(hex("02" + "00".repeat(16))))
    }
}
