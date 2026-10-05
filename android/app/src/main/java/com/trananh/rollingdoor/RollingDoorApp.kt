package com.trananh.rollingdoor

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.trananh.rollingdoor.ble.DeviceScanner
import com.trananh.rollingdoor.ble.PairingSession
import com.trananh.rollingdoor.crypto.AndroidPhoneKeyStore
import com.trananh.rollingdoor.data.DeviceRepository
import com.trananh.rollingdoor.data.deviceDataStore

class RollingDoorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

// Hand-made dependency container: one per process, built in RollingDoorApp.onCreate.
class AppContainer(private val context: Context) {
    // Null only on hardware without Bluetooth, which the manifest's uses-feature already excludes.
    val bluetoothAdapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    val repository = DeviceRepository(context.deviceDataStore, AndroidPhoneKeyStore())

    fun pairingSession(): PairingSession {
        val adapter = checkNotNull(bluetoothAdapter) { "no Bluetooth adapter" }
        return PairingSession(context, DeviceScanner(adapter), repository)
    }
}
