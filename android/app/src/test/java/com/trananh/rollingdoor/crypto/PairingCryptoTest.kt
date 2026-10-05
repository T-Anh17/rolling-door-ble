package com.trananh.rollingdoor.crypto

import com.trananh.rollingdoor.protocol.DoorProtocol
import com.trananh.rollingdoor.protocol.Role
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PairingCryptoTest {
    private val random = SecureRandom()
    private val secret = ByteArray(16).also(random::nextBytes)
    private val nonce = ByteArray(16).also(random::nextBytes)
    private val phoneKey = ByteArray(32).also(random::nextBytes)

    @Test
    fun requestLayoutAndTagMatchFirmware() {
        val app = PairingCrypto()
        val request = app.request(secret, nonce)

        assertEquals(DoorProtocol.PAIRING_REQUEST_LENGTH, request.size)
        assertEquals(0x01.toByte(), request[0])
        assertArrayEquals(app.publicKey, request.copyOfRange(1, 66))
        // verifyTag() in pairing.cpp
        val expected = hmacSha256(secret, "RDPAIR-A".toByteArray() + nonce + app.publicKey).copyOf(16)
        assertArrayEquals(expected, request.copyOfRange(66, 82))
    }

    @Test
    fun publicKeyIsUncompressedPoint() {
        // Repeat so coordinates with leading zero bytes are covered too.
        repeat(50) {
            val app = PairingCrypto()
            assertEquals(65, app.publicKey.size)
            assertEquals(0x04.toByte(), app.publicKey[0])
            val params = (generateKeyPair().public as ECPublicKey).params
            assertArrayEquals(app.publicKey, P256.encode(P256.decode(app.publicKey, params)))
        }
    }

    @Test
    fun opensSealedKey() {
        val app = PairingCrypto()
        val response = FakeFirmware.seal(app.publicKey, secret, nonce, plain(keyId = 3, role = Role.Admin))

        val key = app.open(response, secret, nonce)

        assertEquals(3, key.keyId)
        assertEquals(Role.Admin, key.role)
        assertArrayEquals(phoneKey, key.phoneKey)
    }

    @Test
    fun opensNormalRole() {
        val app = PairingCrypto()
        val response = FakeFirmware.seal(app.publicKey, secret, nonce, plain(keyId = 7, role = Role.Normal))
        assertEquals(Role.Normal, app.open(response, secret, nonce).role)
    }

    @Test(expected = PairingException::class)
    fun wrongSecretFails() {
        val app = PairingCrypto()
        val response = FakeFirmware.seal(app.publicKey, secret, nonce, plain())
        app.open(response, secret.copyOf().also { it[0] = (it[0] + 1).toByte() }, nonce)
    }

    @Test(expected = PairingException::class)
    fun wrongNonceFails() {
        val app = PairingCrypto()
        val response = FakeFirmware.seal(app.publicKey, secret, nonce, plain())
        app.open(response, secret, nonce.copyOf().also { it[15] = (it[15] + 1).toByte() })
    }

    @Test(expected = PairingException::class)
    fun tamperedGcmTagFails() {
        val app = PairingCrypto()
        val response = FakeFirmware.seal(app.publicKey, secret, nonce, plain())
        response[response.size - 1] = (response[response.size - 1] + 1).toByte()
        app.open(response, secret, nonce)
    }

    @Test(expected = PairingException::class)
    fun responseForAnotherAppKeyFails() {
        val response = FakeFirmware.seal(PairingCrypto().publicKey, secret, nonce, plain())
        PairingCrypto().open(response, secret, nonce)
    }

    @Test(expected = PairingException::class)
    fun errorStatusByteFails() {
        val app = PairingCrypto()
        val response = FakeFirmware.seal(app.publicKey, secret, nonce, plain())
        response[0] = 0x06
        app.open(response, secret, nonce)
    }

    @Test(expected = PairingException::class)
    fun shortResponseFails() {
        PairingCrypto().open(byteArrayOf(0x06), secret, nonce)
    }

    @Test(expected = PairingException::class)
    fun devicePointOffCurveFails() {
        val app = PairingCrypto()
        val response = FakeFirmware.seal(app.publicKey, secret, nonce, plain())
        response[65] = (response[65] + 1).toByte() // last byte of Y
        app.open(response, secret, nonce)
    }

    @Test(expected = PairingException::class)
    fun invalidKeyIdFails() {
        val app = PairingCrypto()
        val response = FakeFirmware.seal(app.publicKey, secret, nonce, plain(keyId = 8))
        app.open(response, secret, nonce)
    }

    private fun plain(keyId: Int = 0, role: Role = Role.Admin) =
        byteArrayOf(keyId.toByte(), role.code) + phoneKey

    private fun generateKeyPair() = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    // Same steps as seal() in firmware pairing.cpp, with its own ephemeral device key.
    private object FakeFirmware {
        fun seal(appPublic: ByteArray, secret: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
            val device = KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
            val devicePublic = P256.encode(device.public as ECPublicKey)
            val appKey = P256.decode(appPublic, (device.public as ECPublicKey).params)

            val shared = KeyAgreement.getInstance("ECDH").run {
                init(device.private)
                doPhase(appKey, true)
                generateSecret()
            }
            val sessionKey = Hkdf.derive(nonce, shared + secret, "RDPAIR v1".toByteArray(), 32)
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
                updateAAD(appPublic + devicePublic)
                doFinal(plain)
            }
            return byteArrayOf(0x00) + devicePublic + iv + sealed
        }
    }
}
