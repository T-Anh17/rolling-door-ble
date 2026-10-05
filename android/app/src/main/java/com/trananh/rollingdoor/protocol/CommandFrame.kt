package com.trananh.rollingdoor.protocol

import com.trananh.rollingdoor.crypto.CommandSigner

// COMMAND payload: [key id][command][args 0-16][HMAC-SHA256(key, nonce + command + args), first 16 bytes]
object CommandFrame {
    fun build(
        keyId: Int,
        command: Command,
        args: ByteArray = ByteArray(0),
        nonce: ByteArray,
        signer: CommandSigner,
    ): ByteArray {
        require(keyId in 0 until DoorProtocol.SLOT_COUNT) { "key id out of range: $keyId" }
        require(args.size <= DoorProtocol.MAX_ARGS_LENGTH) { "args too long: ${args.size}" }
        require(nonce.size == DoorProtocol.NONCE_LENGTH) { "nonce must be 16 bytes" }

        val mac = signer.hmacSha256(nonce + command.code + args)
        check(mac.size >= DoorProtocol.MAC_LENGTH) { "signer returned ${mac.size} bytes" }
        return byteArrayOf(keyId.toByte(), command.code) + args +
            mac.copyOf(DoorProtocol.MAC_LENGTH)
    }
}
