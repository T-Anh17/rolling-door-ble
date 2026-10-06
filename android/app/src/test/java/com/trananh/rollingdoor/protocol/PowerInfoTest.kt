package com.trananh.rollingdoor.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerInfoTest {
    @Test
    fun mainsWithoutBattery() {
        assertEquals(PowerInfo(PowerSource.Mains, null), PowerInfo.parse(hex("00ff")))
    }

    @Test
    fun batteryWithPercent() {
        assertEquals(PowerInfo(PowerSource.Battery, 80), PowerInfo.parse(hex("0150")))
        assertEquals(PowerInfo(PowerSource.Battery, 0), PowerInfo.parse(hex("0100")))
        assertEquals(PowerInfo(PowerSource.Mains, 100), PowerInfo.parse(hex("0064")))
    }

    @Test
    fun unknownValuesAreRejected() {
        assertNull(PowerInfo.parse(hex("02ff")))   // unknown source
        assertNull(PowerInfo.parse(hex("0065")))   // 101 %
        assertNull(PowerInfo.parse(hex("00")))     // too short
        assertNull(PowerInfo.parse(hex("00ff00"))) // too long
    }
}
