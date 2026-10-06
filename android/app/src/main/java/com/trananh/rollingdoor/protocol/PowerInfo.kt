package com.trananh.rollingdoor.protocol

// INFO: [00][battery][learned buttons][button list revision], see ButtonInfo. The first byte once
// said mains or battery; the board no longer tells them apart and always sends 00.
// battery: 0-100 percent on battery, FE on USB (charging or full: the board cannot measure the
// cell then), FF no cell.
data class PowerInfo(val batteryPercent: Int?, val charging: Boolean = false) {
    companion object {
        private const val CHARGING = 0xFE
        private const val NO_BATTERY = 0xFF

        // null for a value this app does not understand; Settings then shows no battery.
        fun parse(value: ByteArray): PowerInfo? {
            if (value.size !in 2..4) return null
            return when (val raw = value[1].toInt() and 0xFF) {
                in 0..100 -> PowerInfo(raw)
                CHARGING -> PowerInfo(null, charging = true)
                NO_BATTERY -> PowerInfo(null)
                else -> null
            }
        }
    }
}
