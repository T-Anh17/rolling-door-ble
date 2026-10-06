package com.trananh.rollingdoor.protocol

enum class PowerSource { Mains, Battery }

// INFO: [power source: 00 mains, 01 battery][battery percent 0-100, FF = not measured]
// [learned buttons][button list revision], see ButtonInfo.
// The door itself has no battery, so on battery the board stays up but the door cannot move.
data class PowerInfo(val source: PowerSource, val batteryPercent: Int?) {
    val onBattery: Boolean get() = source == PowerSource.Battery

    companion object {
        private const val BATTERY_UNKNOWN = 0xFF

        // null for a value this app does not understand; the screen then shows no power status.
        fun parse(value: ByteArray): PowerInfo? {
            if (value.size !in 2..4) return null
            val source = when (value[0].toInt()) {
                0x00 -> PowerSource.Mains
                0x01 -> PowerSource.Battery
                else -> return null
            }
            val percent = when (val raw = value[1].toInt() and 0xFF) {
                in 0..100 -> raw
                BATTERY_UNKNOWN -> null
                else -> return null
            }
            return PowerInfo(source, percent)
        }
    }
}
