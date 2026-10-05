package com.trananh.rollingdoor.crypto

import com.trananh.rollingdoor.protocol.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Test

// RFC 5869 appendix A, test cases 1-3 (SHA-256).
class HkdfTest {
    @Test
    fun rfc5869Case1() {
        val okm = Hkdf.derive(
            salt = hex("000102030405060708090a0b0c"),
            ikm = hex("0b".repeat(22)),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun rfc5869Case2() {
        val okm = Hkdf.derive(
            salt = range(0x60, 0xb0),
            ikm = range(0x00, 0x50),
            info = range(0xb0, 0x100),
            length = 82,
        )
        assertArrayEquals(
            hex(
                "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                    "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                    "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            ),
            okm,
        )
    }

    @Test
    fun rfc5869Case3EmptySaltAndInfo() {
        val okm = Hkdf.derive(salt = ByteArray(0), ikm = hex("0b".repeat(22)), info = ByteArray(0), length = 42)
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            okm,
        )
    }

    private fun range(from: Int, until: Int) = ByteArray(until - from) { (from + it).toByte() }
}
