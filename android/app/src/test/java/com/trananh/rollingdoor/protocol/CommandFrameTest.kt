package com.trananh.rollingdoor.protocol

import com.trananh.rollingdoor.crypto.CommandSigner
import com.trananh.rollingdoor.crypto.hmacSha256
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CommandFrameTest {
    private val key = ByteArray(32) { it.toByte() }                // 00 01 .. 1f
    private val nonce = ByteArray(16) { (0xA0 + it).toByte() }     // a0 a1 .. af
    private val signer = CommandSigner { hmacSha256(key, it) }

    @Test
    fun pingWithoutArgs() {
        // python -c "import hmac,hashlib; print(hmac.new(bytes(range(32)), bytes(range(0xa0,0xb0))+b'\x00', hashlib.sha256).hexdigest()[:32])"
        val frame = CommandFrame.build(keyId = 2, command = Command.Ping, nonce = nonce, signer = signer)
        assertArrayEquals(hex("0200" + "efc243d24f7b9d18502928c00e2bab0d"), frame)
    }

    @Test
    fun pressButtonOne() {
        // python -c "import hmac,hashlib; print(hmac.new(bytes(range(32)), bytes(range(0xa0,0xb0))+b'\x0b\x01', hashlib.sha256).hexdigest()[:32])"
        val frame = CommandFrame.build(keyId = 2, command = Command.Press, args = hex("01"), nonce = nonce, signer = signer)
        assertArrayEquals(hex("020b01" + "bac138b136ba5c27b0a137e3e892146a"), frame)
    }

    @Test
    fun argsAreSignedAndSent() {
        // python -c "import hmac,hashlib; print(hmac.new(bytes(range(32)), bytes(range(0xa0,0xb0))+b'\x06\xde\xad', hashlib.sha256).hexdigest()[:32])"
        val frame = CommandFrame.build(
            keyId = 0,
            command = Command.OpenPairing,
            args = hex("dead"),
            nonce = nonce,
            signer = signer,
        )
        assertArrayEquals(hex("0006" + "dead" + "dce46f7ace373852d6938488b2fe332a"), frame)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortNonce() {
        CommandFrame.build(0, Command.Ping, nonce = ByteArray(15), signer = signer)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLongArgs() {
        CommandFrame.build(0, Command.Ping, args = ByteArray(35), nonce = nonce, signer = signer)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsKeyIdOutsideTable() {
        CommandFrame.build(8, Command.Ping, nonce = nonce, signer = signer)
    }
}

internal fun hex(text: String): ByteArray =
    text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
