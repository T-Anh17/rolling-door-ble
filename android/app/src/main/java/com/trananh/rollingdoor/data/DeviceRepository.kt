package com.trananh.rollingdoor.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trananh.rollingdoor.crypto.CommandSigner
import com.trananh.rollingdoor.crypto.PairedKey
import com.trananh.rollingdoor.crypto.PhoneKeyStore
import com.trananh.rollingdoor.protocol.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

val Context.deviceDataStore: DataStore<Preferences> by preferencesDataStore(name = "device")

data class SavedDevice(val mac: String, val keyId: Int, val role: Role)

// The paired device: metadata in DataStore, the phone key in PhoneKeyStore.
//
// Pairing order, so a crash never leaves a device that looks paired but is not:
//   savePending() (key in Keystore, confirmed = false) -> Ping answered 00 00 -> confirm().
// discardIncomplete() runs at startup and drops whatever did not reach confirm().
class DeviceRepository(
    private val dataStore: DataStore<Preferences>,
    private val keyStore: PhoneKeyStore,
) {
    // Only confirmed devices; a pairing in progress is not visible here.
    val device: Flow<SavedDevice?> = dataStore.data
        .map { prefs -> if (prefs[CONFIRMED] == true) prefs.toDevice() else null }
        .distinctUntilChanged()

    // Replaces any saved device. Wipes key.phoneKey once it is in the key store.
    suspend fun savePending(mac: String, key: PairedKey) {
        withContext(Dispatchers.IO) {
            keyStore.importKey(key.phoneKey)
            key.phoneKey.fill(0)
        }
        dataStore.edit { prefs ->
            prefs.clear()
            prefs[MAC] = mac
            prefs[KEY_ID] = key.keyId
            prefs[ROLE] = key.role.code.toInt()
            prefs[CONFIRMED] = false
        }
    }

    suspend fun confirm() {
        dataStore.edit { prefs ->
            check(prefs[MAC] != null) { "no pending device to confirm" }
            prefs[CONFIRMED] = true
        }
    }

    // Signs with the stored phone key, pending or confirmed.
    suspend fun signer(): CommandSigner? = withContext(Dispatchers.IO) { keyStore.signer() }

    // Drops an unconfirmed pairing, or metadata whose key is gone from the key store.
    suspend fun discardIncomplete() {
        val prefs = dataStore.data.first()
        val complete = prefs[CONFIRMED] == true && prefs.toDevice() != null && signer() != null
        if (!complete) forget()
    }

    // The device's last BUTTONS value, so the control screen shows the buttons before it
    // connects. Cleared with the device.
    suspend fun cachedButtons(): ByteArray? =
        dataStore.data.first()[BUTTONS]?.let { Base64.decode(it, Base64.NO_WRAP) }

    suspend fun saveButtons(value: ByteArray) {
        dataStore.edit { prefs ->
            if (prefs[MAC] != null) prefs[BUTTONS] = Base64.encodeToString(value, Base64.NO_WRAP)
        }
    }

    // Local only: the key slot on the ESP32 stays until an admin revokes it or the device is wiped.
    suspend fun forget() {
        withContext(Dispatchers.IO) { keyStore.deleteKey() }
        dataStore.edit { it.clear() }
    }

    private fun Preferences.toDevice(): SavedDevice? {
        val mac = this[MAC] ?: return null
        val keyId = this[KEY_ID] ?: return null
        val role = this[ROLE]?.let { Role.fromCode(it.toByte()) } ?: return null
        return SavedDevice(mac, keyId, role)
    }

    private companion object {
        val MAC = stringPreferencesKey("mac")
        val KEY_ID = intPreferencesKey("key_id")
        val ROLE = intPreferencesKey("role")
        val CONFIRMED = booleanPreferencesKey("confirmed")
        val BUTTONS = stringPreferencesKey("buttons")
    }
}
