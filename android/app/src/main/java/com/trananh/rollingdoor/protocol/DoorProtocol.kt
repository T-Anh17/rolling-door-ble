package com.trananh.rollingdoor.protocol

import java.util.UUID

// Mirror of firmware_esp/.../src/config.h and protocol.h. Keep both sides in sync.
object DoorProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("a7930001-966e-4240-b881-5c2e2f2203a8")
    val CHALLENGE_UUID: UUID = UUID.fromString("a7930002-966e-4240-b881-5c2e2f2203a8")
    val COMMAND_UUID: UUID = UUID.fromString("a7930003-966e-4240-b881-5c2e2f2203a8")
    val STATUS_UUID: UUID = UUID.fromString("a7930004-966e-4240-b881-5c2e2f2203a8")
    val INFO_UUID: UUID = UUID.fromString("a7930005-966e-4240-b881-5c2e2f2203a8")
    val PAIRING_UUID: UUID = UUID.fromString("a7930006-966e-4240-b881-5c2e2f2203a8")
    val BUTTONS_UUID: UUID = UUID.fromString("a7930007-966e-4240-b881-5c2e2f2203a8")

    // Scan response manufacturer data while pairing is open: [company id 0xFFFF][0x01].
    const val MANUFACTURER_ID = 0xFFFF
    const val PAIRING_OPEN_FLAG: Byte = 0x01

    const val NONCE_LENGTH = 16
    const val MAC_LENGTH = 16
    const val MAX_ARGS_LENGTH = 34 // SET_BUTTON: id, icon and a 32-byte name
    const val SECRET_LENGTH = 16
    const val KEY_LENGTH = 32
    const val SLOT_COUNT = 8
    const val PUBLIC_KEY_LENGTH = 65
    const val IV_LENGTH = 12
    const val GCM_TAG_LENGTH = 16
    const val PAIRING_PLAIN_LENGTH = 2 + KEY_LENGTH

    // PAIRING write: [0x01][app public key][tagA]
    const val PAIRING_REQUEST: Byte = 0x01
    const val PAIRING_REQUEST_LENGTH = 1 + PUBLIC_KEY_LENGTH + MAC_LENGTH

    // PAIRING read: [0x00][device public key][iv][ciphertext][GCM tag]
    const val PAIRING_RESPONSE_LENGTH =
        1 + PUBLIC_KEY_LENGTH + IV_LENGTH + PAIRING_PLAIN_LENGTH + GCM_TAG_LENGTH

    // STATUS notify is [command][result]; a PAIRING request reports this command code.
    const val STATUS_PAIRING: Byte = 0x80.toByte()

    // A write longer than this needs a larger MTU than the default 23. Only SET_BUTTON is.
    const val DEFAULT_MTU_PAYLOAD = 20

    // STATUS [81][result] when learning started by LearnRf ends: Ok saved, RfError not heard.
    const val STATUS_RF_LEARNED: Byte = 0x81.toByte()
    const val LEARN_CANCEL: Byte = 0x00 // LearnRf argument that stops learning
}

enum class Command(val code: Byte) {
    Ping(0x00),
    OpenPairing(0x06),
    LearnRf(0x09), // admin, args: [button id] to learn, [LEARN_CANCEL] to stop
    ClearRf(0x0A), // admin, args: [button id]
    Press(0x0B), // args: [button id]
    SetButton(0x0C), // admin, args: see ButtonList.setArgs; adds the button or changes it
    DeleteButton(0x0D), // admin, args: [button id]; also clears its RF code
}

enum class CommandResult(val code: Byte) {
    Ok(0x00),
    BadCommand(0x01),
    AuthFailed(0x02),
    RfError(0x03),
    NotPermitted(0x04),
    LockedOut(0x05),
    PairingClosed(0x06),
    TableFull(0x07),
    Unknown(-1);

    companion object {
        fun fromCode(code: Byte): CommandResult =
            entries.firstOrNull { it != Unknown && it.code == code } ?: Unknown
    }
}

enum class Role(val code: Byte) {
    Normal(0x00),
    Admin(0x01);

    companion object {
        fun fromCode(code: Byte): Role? = entries.firstOrNull { it.code == code }
    }
}
