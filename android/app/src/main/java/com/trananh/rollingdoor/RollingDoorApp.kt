package com.trananh.rollingdoor

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.trananh.rollingdoor.ble.DeviceScanner
import com.trananh.rollingdoor.ble.DoorConnection
import com.trananh.rollingdoor.ble.PairingSession
import com.trananh.rollingdoor.crypto.AndroidPhoneKeyStore
import com.trananh.rollingdoor.crypto.CommandSigner
import com.trananh.rollingdoor.data.DeviceRepository
import com.trananh.rollingdoor.data.SavedDevice
import com.trananh.rollingdoor.data.deviceDataStore
import kotlinx.coroutines.CoroutineScope

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

    private val phoneKeyStore = AndroidPhoneKeyStore()

    val repository = DeviceRepository(context.deviceDataStore, phoneKeyStore)

    fun pairingSession(): PairingSession {
        val adapter = checkNotNull(bluetoothAdapter) { "no Bluetooth adapter" }
        return PairingSession(context, DeviceScanner(adapter), repository)
    }

    // scope must run on the main thread (see DoorConnection). The phone key is looked up when a
    // command is signed, off the main thread, so connecting never waits for Keystore. Signing
    // throws IllegalStateException if the key is gone.
    fun doorConnection(device: SavedDevice, scope: CoroutineScope): DoorConnection {
        val adapter = checkNotNull(bluetoothAdapter) { "no Bluetooth adapter" }
        val signer = CommandSigner { data ->
            checkNotNull(phoneKeyStore.signer()) { "phone key missing" }.hmacSha256(data)
        }
        val buttonCache = object : DoorConnection.ButtonCache {
            override suspend fun load() = repository.cachedButtons()
            override suspend fun save(value: ByteArray) = repository.saveButtons(value)
        }
        return DoorConnection(context, adapter, device, signer, buttonCache, scope)
    }
}
