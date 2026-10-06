package com.trananh.rollingdoor.crypto

import com.trananh.rollingdoor.protocol.DoorProtocol
import com.trananh.rollingdoor.protocol.Role
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PairedKey(val keyId: Int, val role: Role, val phoneKey: ByteArray)

class PairingException(message: String, cause: Throwable? = null) : Exception(message, cause)

// App side of firmware pairing.cpp. One instance per attempt: it holds an ephemeral P-256 key pair.
class PairingCrypto {
    private val keyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    private val params: ECParameterSpec = (keyPair.public as ECPublicKey).params

    // Uncompressed point 04 || X || Y.
    val publicKey: ByteArray = P256.encode(keyPair.public as ECPublicKey)

    // tagA = HMAC-SHA256(secret, "RDPAIR-A" + nonce + appPublicKey), first 16 bytes.
    fun tagA(secret: ByteArray, nonce: ByteArray): ByteArray {
        require(nonce.size == DoorProtocol.NONCE_LENGTH) { "nonce must be 16 bytes" }
        return hmacSha256(secret, TAG_LABEL + nonce + publicKey).copyOf(DoorProtocol.MAC_LENGTH)
    }

    // PAIRING write: [0x01][app public key][tagA]
    fun request(secret: ByteArray, nonce: ByteArray): ByteArray =
        byteArrayOf(DoorProtocol.PAIRING_REQUEST) + publicKey + tagA(secret, nonce)

    // Opens the PAIRING read [0x00][device public key][iv][ciphertext][tag]:
    // Z = ECDH, K = HKDF-SHA256(salt = nonce, ikm = Z + secret, info), AES-256-GCM(aad = appPublic + devicePublic).
    fun open(response: ByteArray, secret: ByteArray, nonce: ByteArray): PairedKey {
        if (response.size != DoorProtocol.PAIRING_RESPONSE_LENGTH || response[0] != 0.toByte()) {
            throw PairingException("unexpected pairing response (${response.size} bytes)")
        }
        var offset = 1
        val devicePublic = response.copyOfRange(offset, offset + DoorProtocol.PUBLIC_KEY_LENGTH)
        offset += DoorProtocol.PUBLIC_KEY_LENGTH
        val iv = response.copyOfRange(offset, offset + DoorProtocol.IV_LENGTH)
        offset += DoorProtocol.IV_LENGTH
        val sealed = response.copyOfRange(offset, response.size)

        var shared = ByteArray(0)
        var ikm = ByteArray(0)
        var sessionKey = ByteArray(0)
        var plain = ByteArray(0)
        try {
            shared = KeyAgreement.getInstance("ECDH").run {
                init(keyPair.private)
                doPhase(P256.decode(devicePublic, params), true)
                generateSecret()
            }
            ikm = shared + secret
            sessionKey = Hkdf.derive(nonce, ikm, KDF_INFO, 32)
            plain = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(sessionKey, "AES"),
                    GCMParameterSpec(DoorProtocol.GCM_TAG_LENGTH * 8, iv),
                )
                updateAAD(publicKey + devicePublic)
                doFinal(sealed)
            }
            if (plain.size != DoorProtocol.PAIRING_PLAIN_LENGTH) {
                throw PairingException("unexpected plaintext length ${plain.size}")
            }
            val keyId = plain[0].toInt() and 0xFF
            val role = Role.fromCode(plain[1])
            if (keyId >= DoorProtocol.SLOT_COUNT || role == null) {
                throw PairingException("invalid key id or role")
            }
            return PairedKey(keyId, role, plain.copyOfRange(2, plain.size))
        } catch (e: GeneralSecurityException) {
            throw PairingException("cannot open pairing response", e)
        } finally {
            shared.fill(0)
            ikm.fill(0)
            sessionKey.fill(0)
            plain.fill(0)
        }
    }

    private companion object {
        val TAG_LABEL = "RDPAIR-A".toByteArray(Charsets.US_ASCII)
        val KDF_INFO = "RDPAIR v1".toByteArray(Charsets.US_ASCII)
    }
}

// Uncompressed SEC1 encoding of P-256 public keys, as mbedtls_ecp_point_{read,write}_binary.
internal object P256 {
    private const val COORDINATE_LENGTH = 32

    fun encode(key: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + key.w.affineX.toFixed() + key.w.affineY.toFixed()

    // Rejects anything that is not a point on the curve (invalid-curve attack).
    fun decode(bytes: ByteArray, params: ECParameterSpec): ECPublicKey {
        if (bytes.size != DoorProtocol.PUBLIC_KEY_LENGTH || bytes[0] != 0x04.toByte()) {
            throw PairingException("malformed public key")
        }
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORDINATE_LENGTH))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORDINATE_LENGTH, bytes.size))
        val curve = params.curve
        val p = (curve.field as ECFieldFp).p
        val rhs = x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(p)
        if (x >= p || y >= p || y.multiply(y).mod(p) != rhs) {
            throw PairingException("public key is not on P-256")
        }
        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    private fun BigInteger.toFixed(): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size > COORDINATE_LENGTH -> bytes.copyOfRange(bytes.size - COORDINATE_LENGTH, bytes.size)
            else -> ByteArray(COORDINATE_LENGTH - bytes.size) + bytes
        }
    }
}
