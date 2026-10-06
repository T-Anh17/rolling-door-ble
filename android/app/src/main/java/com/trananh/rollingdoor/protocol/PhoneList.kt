package com.trananh.rollingdoor.protocol

// One paired phone on the board. An empty name shows "Phone <keyId + 1>" in the phone's language.
data class PairedPhone(val keyId: Int, val role: Role, val name: String = "")

// The board's phones by key id, read from PHONES after LIST_PHONES.
data class PhoneList(val phones: List<PairedPhone>) {
    operator fun get(keyId: Int): PairedPhone? = phones.firstOrNull { it.keyId == keyId }

    companion object {
        // Same limit and rules as button names; fit names with ButtonList.fitName.
        const val MAX_NAME_BYTES = ButtonList.MAX_NAME_BYTES

        // PHONES: [count] then, per phone, [key id][role][name length][name UTF-8].
        // null if the value is malformed, including the empty value the board gives before
        // LIST_PHONES.
        fun parse(value: ByteArray): PhoneList? {
            if (value.isEmpty()) return null
            val phones = mutableListOf<PairedPhone>()
            var i = 1
            while (i < value.size) {
                if (i + 3 > value.size) return null
                val keyId = value[i].toInt() and 0xFF
                val role = Role.fromCode(value[i + 1]) ?: return null
                val nameLength = value[i + 2].toInt() and 0xFF
                i += 3
                if (keyId >= DoorProtocol.SLOT_COUNT || nameLength > MAX_NAME_BYTES || i + nameLength > value.size) {
                    return null
                }
                if (phones.any { it.keyId == keyId }) return null
                phones += PairedPhone(keyId, role, value.decodeToString(i, i + nameLength))
                i += nameLength
            }
            if (phones.size != (value[0].toInt() and 0xFF)) return null
            return PhoneList(phones)
        }

        // RENAME_PHONE args: [key id][name UTF-8]. The name must already fit (see ButtonList.fitName).
        fun renameArgs(keyId: Int, name: String): ByteArray {
            val bytes = name.encodeToByteArray()
            require(keyId in 0 until DoorProtocol.SLOT_COUNT) { "key id out of range: $keyId" }
            require(bytes.size <= MAX_NAME_BYTES) { "name too long: ${bytes.size} bytes" }
            return byteArrayOf(keyId.toByte()) + bytes
        }
    }
}
