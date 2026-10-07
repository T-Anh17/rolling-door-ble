package com.trananh.rollingdoor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

enum class LinkError {
    BluetoothOff,
    NoPermission,
    ScanFailed,
    ConnectFailed,
    Timeout,
    Lost,         // the link dropped after it was up
    Unsupported,  // service or characteristic missing, or Android refused the operation
}

class BleException(val error: LinkError, message: String = error.name) : IOException(message)

// Suspend wrapper around BluetoothGatt. Android runs one GATT operation at a time, so every
// operation holds a mutex until its callback arrives. Permissions are checked by the UI gate.
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val listener: Listener,
) {
    interface Listener {
        fun onNotification(uuid: UUID, value: ByteArray)

        // Only for a link that was up and dropped, never after close().
        fun onDisconnected()
    }

    private enum class Op { Connect, Mtu, Discover, WriteDescriptor, Read, Write }

    private class Pending(val op: Op, val result: CompletableDeferred<Any>)

    private val mutex = Mutex()
    private val lock = Any()
    @Volatile private var pending: Pending? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connected = false
    @Volatile private var closed = false

    // When the last operation's callback arrived (Binder thread), for timing logs: the caller
    // resumes later, once its dispatcher (the main thread for DoorConnection) gets to it.
    @Volatile var lastCallbackAt = 0L
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                complete(Op.Connect, Unit)
                return
            }
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return
            val wasConnected = connected
            connected = false
            release()
            fail(BleException(if (wasConnected) LinkError.Lost else LinkError.ConnectFailed, "status $status"))
            if (wasConnected && !closed) listener.onDisconnected()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // A refused MTU leaves the default (23); long writes still work, so carry on.
            complete(Op.Mtu, mtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            completeOrFail(Op.Discover, status, Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            completeOrFail(Op.WriteDescriptor, status, Unit)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeOrFail(Op.Read, status, value)
        }

        @Deprecated("Called below API 33 only")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            completeOrFail(Op.Read, status, c.value?.copyOf() ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            completeOrFail(Op.Write, status, Unit)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            listener.onNotification(c.uuid, value)
        }

        @Deprecated("Called below API 33 only")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            listener.onNotification(c.uuid, c.value?.copyOf() ?: return)
        }
    }

    // autoConnect = false: direct connection; Android only gives up after ~30 s, so pass a short timeout.
    // autoConnect = true: the controller waits for the device to advertise (allow list, low duty
    // scan, no app scanning); pass timeoutMs = null to wait until it shows up or close() is called.
    suspend fun connect(autoConnect: Boolean, timeoutMs: Long?) {
        mutex.withLock {
            check(gatt == null && !closed) { "connect() called twice" }
            val result = CompletableDeferred<Any>()
            pending = Pending(Op.Connect, result)
            try {
                gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: throw BleException(LinkError.ConnectFailed, "connectGatt returned null")
                if (timeoutMs == null) {
                    result.await()
                } else {
                    withTimeoutOrNull(timeoutMs) { result.await() }
                        ?: throw BleException(LinkError.Timeout, "connect timed out")
                }
            } catch (e: SecurityException) {
                release()
                throw BleException(LinkError.NoPermission, e.message ?: "BLUETOOTH_CONNECT missing")
            } catch (e: Throwable) {
                release()
                throw e
            } finally {
                pending = null
            }
        }
    }

    // Asks for a short connection interval (11.25-15 ms) until the link closes. Not a queued GATT
    // operation and no public callback, so it returns at once; false if Android refused.
    fun requestHighPriority(): Boolean {
        val g = gatt?.takeIf { connected } ?: return false
        return try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } catch (e: SecurityException) {
            throw BleException(LinkError.NoPermission, e.message ?: "BLUETOOTH_CONNECT missing")
        }
    }

    suspend fun requestMtu(mtu: Int): Int = execute(Op.Mtu) { it.requestMtu(mtu) } as Int

    suspend fun discoverServices() {
        execute(Op.Discover) { it.discoverServices() }
    }

    suspend fun enableNotifications(service: UUID, characteristic: UUID) {
        val c = characteristic(service, characteristic)
        val cccd = c.getDescriptor(CCCD_UUID)
            ?: throw BleException(LinkError.Unsupported, "no CCCD on $characteristic")
        execute(Op.WriteDescriptor) { g ->
            if (!g.setCharacteristicNotification(c, true)) return@execute false
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    suspend fun read(service: UUID, characteristic: UUID): ByteArray {
        val c = characteristic(service, characteristic)
        return execute(Op.Read) { it.readCharacteristic(c) } as ByteArray
    }

    // Write with response. Android splits values longer than MTU - 3 into a long write.
    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        val c = characteristic(service, characteristic)
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        execute(Op.Write) { g ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    // Disconnects without calling Listener.onDisconnected.
    fun close() {
        closed = true
        fail(BleException(LinkError.Lost, "closed"))
        gatt?.disconnect()
        release()
    }

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(characteristic)
            ?: throw BleException(LinkError.Unsupported, "missing characteristic $characteristic")

    private suspend fun execute(op: Op, start: (BluetoothGatt) -> Boolean): Any =
        mutex.withLock {
            val g = gatt?.takeIf { connected } ?: throw BleException(LinkError.Lost, "not connected")
            val result = CompletableDeferred<Any>()
            pending = Pending(op, result)
            try {
                val started = try {
                    start(g)
                } catch (e: SecurityException) {
                    throw BleException(LinkError.NoPermission, e.message ?: "BLUETOOTH_CONNECT missing")
                }
                if (!started) throw BleException(LinkError.Unsupported, "$op refused")
                withTimeoutOrNull(OP_TIMEOUT_MS) { result.await() }
                    ?: throw BleException(LinkError.Timeout, "$op timed out")
            } finally {
                pending = null
            }
        }

    private fun complete(op: Op, value: Any) {
        lastCallbackAt = SystemClock.elapsedRealtime()
        pending?.takeIf { it.op == op }?.result?.complete(value)
    }

    private fun completeOrFail(op: Op, status: Int, value: Any) {
        lastCallbackAt = SystemClock.elapsedRealtime()
        if (status == BluetoothGatt.GATT_SUCCESS) {
            complete(op, value)
        } else {
            pending?.takeIf { it.op == op }?.result
                ?.completeExceptionally(BleException(LinkError.Unsupported, "$op failed, status $status"))
        }
    }

    private fun fail(error: BleException) {
        pending?.result?.completeExceptionally(error)
    }

    private fun release() {
        val g = synchronized(lock) { gatt.also { gatt = null } }
        g?.close()
    }

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val OP_TIMEOUT_MS = 5_000L
    }
}
