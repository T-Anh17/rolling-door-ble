package com.trananh.rollingdoor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.os.SystemClock
import com.trananh.rollingdoor.protocol.DoorProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// Pairing only: finds the device from the QR code. The control screen connects by MAC directly,
// so daily use needs no scan (and no location permission on Android 11 and below).
@SuppressLint("MissingPermission")
class DeviceScanner(private val adapter: BluetoothAdapter) {
    class Found(val device: BluetoothDevice, val pairingOpen: Boolean)

    // Null if the device is not seen within timeoutMs.
    suspend fun find(mac: String, timeoutMs: Long = SCAN_TIMEOUT_MS): Found? {
        if (!adapter.isEnabled) throw BleException(LinkError.BluetoothOff)
        val scanner = adapter.bluetoothLeScanner ?: throw BleException(LinkError.BluetoothOff)
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(DoorProtocol.SERVICE_UUID))
            .setDeviceAddress(mac)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val results = callbackFlow {
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    trySend(result)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { trySend(it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    close(BleException(LinkError.ScanFailed, "scan failed, error $errorCode"))
                }
            }
            try {
                scanner.startScan(listOf(filter), settings, callback)
            } catch (e: SecurityException) {
                throw BleException(LinkError.NoPermission, e.message ?: "BLUETOOTH_SCAN missing")
            }
            // stopScan throws if Bluetooth was turned off in the meantime.
            awaitClose { runCatching { scanner.stopScan(callback) } }
        }

        // The pairing flag is in the scan response, which can come a little after the first
        // sighting; wait a moment for it before reporting the window as closed.
        var firstSeenAt = 0L
        val result = withTimeoutOrNull(timeoutMs) {
            results.first { result ->
                val now = SystemClock.elapsedRealtime()
                if (firstSeenAt == 0L) firstSeenAt = now
                result.isPairingOpen() || now - firstSeenAt >= FLAG_GRACE_MS
            }
        } ?: return null
        return Found(result.device, result.isPairingOpen())
    }

    private fun ScanResult.isPairingOpen(): Boolean {
        val data = scanRecord?.getManufacturerSpecificData(DoorProtocol.MANUFACTURER_ID)
        return data != null && data.isNotEmpty() && data[0] == DoorProtocol.PAIRING_OPEN_FLAG
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 10_000L
        const val FLAG_GRACE_MS = 1_500L
    }
}
