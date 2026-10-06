package com.trananh.rollingdoor.protocol

// Icons the admin picks for a button. The code is stored on the board (firmware
// remote_buttons.h checks only the range), so codes never change and new icons go at the end.
enum class ButtonIcon(val code: Int) {
    Up(0),
    Down(1),
    Lock(2),
    Unlock(3),
    Stop(4),
    Gate(5),
    Garage(6),
    Light(7),
    Power(8),
    Bell(9);

    companion object {
        fun fromCode(code: Int): ButtonIcon? = entries.firstOrNull { it.code == code }
    }
}

// One button on the control screen. id 1-8 is the board's slot for its RF code.
// An empty name shows the icon's own name, in the phone's language.
data class RemoteButton(val id: Int, val icon: ButtonIcon, val name: String = "")

// The board's button list, in display order. revision changes whenever the admin adds, edits
// or deletes a button; INFO reports it, so the list is read again only when it changed.
data class ButtonList(val revision: Int, val buttons: List<RemoteButton>) {
    val isFull: Boolean get() = buttons.size >= MAX_BUTTONS

    // Lowest id with no button, for a new one; null when the list is full.
    fun freeId(): Int? = (1..MAX_BUTTONS).firstOrNull { id -> buttons.none { it.id == id } }

    operator fun get(id: Int): RemoteButton? = buttons.firstOrNull { it.id == id }

    companion object {
        const val MAX_BUTTONS = 8
        const val MAX_NAME_BYTES = 32

        // What a new board starts with, shown until the real list has been read once.
        val DEFAULT = ButtonList(
            revision = -1,
            buttons = listOf(
                RemoteButton(1, ButtonIcon.Up),
                RemoteButton(2, ButtonIcon.Down),
                RemoteButton(3, ButtonIcon.Lock),
                RemoteButton(4, ButtonIcon.Unlock),
            ),
        )

        // BUTTONS: [revision] then, per button, [id][icon][name length][name UTF-8].
        // null if the value is malformed or has an icon this app does not know.
        fun parse(value: ByteArray): ButtonList? {
            if (value.isEmpty()) return null
            val buttons = mutableListOf<RemoteButton>()
            var i = 1
            while (i < value.size) {
                if (i + 3 > value.size) return null
                val id = value[i].toInt() and 0xFF
                val icon = ButtonIcon.fromCode(value[i + 1].toInt() and 0xFF) ?: return null
                val nameLength = value[i + 2].toInt() and 0xFF
                i += 3
                if (id !in 1..MAX_BUTTONS || nameLength > MAX_NAME_BYTES || i + nameLength > value.size) {
                    return null
                }
                if (buttons.any { it.id == id }) return null
                buttons += RemoteButton(id, icon, value.decodeToString(i, i + nameLength))
                i += nameLength
            }
            if (buttons.size > MAX_BUTTONS) return null
            return ButtonList(value[0].toInt() and 0xFF, buttons)
        }

        // SET_BUTTON args: [id][icon][name UTF-8]. The name must already fit (see fitName).
        fun setArgs(button: RemoteButton): ByteArray {
            val name = button.name.encodeToByteArray()
            require(button.id in 1..MAX_BUTTONS) { "id out of range: ${button.id}" }
            require(name.size <= MAX_NAME_BYTES) { "name too long: ${name.size} bytes" }
            return byteArrayOf(button.id.toByte(), button.icon.code.toByte()) + name
        }

        // Drops control characters and cuts the name to MAX_NAME_BYTES of UTF-8, never in the
        // middle of a character.
        fun fitName(name: String): String {
            val clean = name.filterNot { it.isISOControl() }
            var bytes = 0
            var end = 0
            while (end < clean.length) {
                val codePoint = clean.codePointAt(end)
                val size = when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + size > MAX_NAME_BYTES) break
                bytes += size
                end += Character.charCount(codePoint)
            }
            return clean.substring(0, end)
        }
    }
}

// INFO: [power source][battery percent][learned buttons][button list revision], see PowerInfo.
object ButtonInfo {
    // Bit n of the third byte is set when button n + 1 has an RF code. Only says which
    // buttons have a code, never the code. null on firmware that does not report it.
    fun learned(info: ByteArray): Set<Int>? {
        if (info.size < 3) return null
        val mask = info[2].toInt() and 0xFF
        return (1..ButtonList.MAX_BUTTONS).filter { mask and (1 shl (it - 1)) != 0 }.toSet()
    }

    // null on firmware without a button list.
    fun revision(info: ByteArray): Int? = if (info.size < 4) null else info[3].toInt() and 0xFF
}
