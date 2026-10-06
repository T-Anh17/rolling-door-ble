package com.trananh.rollingdoor.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.trananh.rollingdoor.crypto.CommandSigner
import com.trananh.rollingdoor.crypto.PairedKey
import com.trananh.rollingdoor.crypto.PhoneKeyStore
import com.trananh.rollingdoor.crypto.hmacSha256
import com.trananh.rollingdoor.protocol.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeviceRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val keyStore = FakePhoneKeyStore()
    private lateinit var repository: DeviceRepository

    private val mac = "24:6F:28:A1:B2:C3"
    private val phoneKey = ByteArray(32) { (it * 7).toByte() }

    @Before
    fun setUp() {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(folder.root, "device.preferences_pb")
        }
        repository = DeviceRepository(dataStore, keyStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun emptyByDefault() = runTest {
        assertNull(repository.device.first())
        assertNull(repository.signer())
    }

    @Test
    fun pendingDeviceIsHiddenButCanSign() = runTest {
        repository.savePending(mac, PairedKey(3, Role.Admin, phoneKey.copyOf()))

        assertNull(repository.device.first())
        val data = byteArrayOf(1, 2, 3)
        assertArrayEquals(hmacSha256(phoneKey, data), repository.signer()!!.hmacSha256(data))
    }

    @Test
    fun savePendingWipesKeyBytes() = runTest {
        val key = PairedKey(0, Role.Admin, phoneKey.copyOf())
        repository.savePending(mac, key)
        assertArrayEquals(ByteArray(32), key.phoneKey)
    }

    @Test
    fun confirmedDeviceIsVisible() = runTest {
        repository.savePending(mac, PairedKey(3, Role.Normal, phoneKey.copyOf()))
        repository.confirm()

        assertEquals(SavedDevice(mac, 3, Role.Normal), repository.device.first())
    }

    @Test
    fun roleFollowsHandOver() = runTest {
        repository.savePending(mac, PairedKey(3, Role.Normal, phoneKey.copyOf()))
        repository.confirm()

        repository.setRole(Role.Admin)

        assertEquals(SavedDevice(mac, 3, Role.Admin), repository.device.first())
    }

    @Test
    fun setRoleWithoutDeviceDoesNothing() = runTest {
        repository.setRole(Role.Admin)
        assertNull(repository.device.first())
    }

    @Test(expected = IllegalStateException::class)
    fun confirmWithoutPendingFails() = runTest {
        repository.confirm()
    }

    @Test
    fun discardIncompleteDropsUnconfirmedPairing() = runTest {
        repository.savePending(mac, PairedKey(0, Role.Admin, phoneKey.copyOf()))

        repository.discardIncomplete()

        assertNull(keyStore.key)
        repository.savePending(mac, PairedKey(0, Role.Admin, phoneKey.copyOf()))
        repository.confirm()
        assertNotNull(repository.device.first())
    }

    @Test
    fun discardIncompleteKeepsConfirmedDevice() = runTest {
        repository.savePending(mac, PairedKey(1, Role.Admin, phoneKey.copyOf()))
        repository.confirm()

        repository.discardIncomplete()

        assertEquals(SavedDevice(mac, 1, Role.Admin), repository.device.first())
        assertNotNull(keyStore.key)
    }

    @Test
    fun discardIncompleteDropsDeviceWhoseKeyIsGone() = runTest {
        repository.savePending(mac, PairedKey(1, Role.Admin, phoneKey.copyOf()))
        repository.confirm()
        keyStore.deleteKey()

        repository.discardIncomplete()

        assertNull(repository.device.first())
    }

    @Test
    fun forgetClearsMetadataAndKey() = runTest {
        repository.savePending(mac, PairedKey(2, Role.Admin, phoneKey.copyOf()))
        repository.confirm()

        repository.forget()

        assertNull(repository.device.first())
        assertNull(repository.signer())
    }

    @Test
    fun savePendingReplacesPreviousDevice() = runTest {
        repository.savePending(mac, PairedKey(2, Role.Admin, phoneKey.copyOf()))
        repository.confirm()

        repository.savePending("AA:BB:CC:DD:EE:FF", PairedKey(5, Role.Normal, ByteArray(32) { 1 }))

        assertNull(repository.device.first())
        repository.confirm()
        assertEquals(SavedDevice("AA:BB:CC:DD:EE:FF", 5, Role.Normal), repository.device.first())
    }

    // Keeps the key in memory and signs with SecretKeySpec instead of Android Keystore.
    private class FakePhoneKeyStore : PhoneKeyStore {
        var key: ByteArray? = null

        override fun importKey(key: ByteArray) {
            this.key = key.copyOf()
        }

        override fun signer(): CommandSigner? =
            key?.let { stored -> CommandSigner { hmacSha256(stored, it) } }

        override fun deleteKey() {
            key = null
        }
    }
}
