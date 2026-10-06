package com.trananh.rollingdoor.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneListTest {
    @Test
    fun parsesPhonesWithNames() {
        // "Máy" is 4 bytes of UTF-8.
        val value = hex("02" + "0001" + "04" + "4dc3a179" + "0300" + "00")
        assertEquals(
            listOf(PairedPhone(0, Role.Admin, "Máy"), PairedPhone(3, Role.Normal)),
            PhoneList.parse(value)?.phones,
        )
    }

    @Test
    fun findsPhoneByKeyId() {
        val list = PhoneList.parse(hex("01" + "050100"))
        assertEquals(PairedPhone(5, Role.Admin), list?.get(5))
        assertNull(list?.get(0))
    }

    @Test
    fun malformedListsAreRejected() {
        assertNull(PhoneList.parse(ByteArray(0)))                   // before LIST_PHONES
        assertNull(PhoneList.parse(hex("01" + "0001")))             // cut in the header
        assertNull(PhoneList.parse(hex("01" + "000102" + "41")))    // name shorter than its length
        assertNull(PhoneList.parse(hex("01" + "080000")))           // key id 8
        assertNull(PhoneList.parse(hex("01" + "000200")))           // unknown role
        assertNull(PhoneList.parse(hex("02" + "000100" + "000000"))) // same key id twice
        assertNull(PhoneList.parse(hex("02" + "000100")))           // count does not match
    }

    @Test
    fun renameArgsAreKeyIdThenName() {
        assertArrayEquals(hex("03" + "4dc3a179"), PhoneList.renameArgs(3, "Máy"))
        assertArrayEquals(hex("00"), PhoneList.renameArgs(0, ""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun renameArgsRejectLongNames() {
        PhoneList.renameArgs(0, "a".repeat(33))
    }
}
