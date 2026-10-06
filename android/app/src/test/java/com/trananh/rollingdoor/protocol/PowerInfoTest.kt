package com.trananh.rollingdoor.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerInfoTest {
    @Test
    fun batteryPercent() {
        assertEquals(PowerInfo(80), PowerInfo.parse(hex("0050")))
        assertEquals(PowerInfo(0), PowerInfo.parse(hex("0000")))
        assertEquals(PowerInfo(100), PowerInfo.parse(hex("0064")))
    }

    @Test
    fun chargingAndNoCell() {
        assertEquals(PowerInfo(null, charging = true), PowerInfo.parse(hex("00fe")))
        assertEquals(PowerInfo(null), PowerInfo.parse(hex("00ff")))
    }

    @Test
    fun firstByteIsIgnored() {
        // Older firmware sent 01 there while on battery.
        assertEquals(PowerInfo(80), PowerInfo.parse(hex("0150")))
    }

    @Test
    fun unknownValuesAreRejected() {
        assertNull(PowerInfo.parse(hex("0065")))       // 101 %
        assertNull(PowerInfo.parse(hex("00fd")))       // not a known code
        assertNull(PowerInfo.parse(hex("00")))         // too short
        assertNull(PowerInfo.parse(hex("00ff000000"))) // too long
    }

    @Test
    fun buttonBytesAreIgnored() {
        assertEquals(PowerInfo(80), PowerInfo.parse(hex("00500f")))
        assertEquals(PowerInfo(80), PowerInfo.parse(hex("00500f2a")))
    }
}
