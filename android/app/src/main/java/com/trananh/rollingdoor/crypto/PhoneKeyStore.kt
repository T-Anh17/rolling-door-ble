package com.trananh.rollingdoor.crypto

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

// Home of the 32-byte phone key. Only one device is paired at a time, so there is one key.
interface PhoneKeyStore {
    fun importKey(key: ByteArray)

    // Null when no key is stored.
    fun signer(): CommandSigner?

    fun deleteKey()
}

// Imports the key into Android Keystore as an HMAC-SHA256 key; after that it never leaves Keystore.
// No StrongBox: it adds tens of milliseconds to every signature.
class AndroidPhoneKeyStore : PhoneKeyStore {
    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun importKey(key: ByteArray) {
        keyStore.setEntry(
            ALIAS,
            KeyStore.SecretKeyEntry(SecretKeySpec(key, KeyProperties.KEY_ALGORITHM_HMAC_SHA256)),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
    }

    override fun signer(): CommandSigner? {
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: return null
        return CommandSigner { data ->
            Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
                init(key)
                doFinal(data)
            }
        }
    }

    override fun deleteKey() {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "door-key"
    }
}
