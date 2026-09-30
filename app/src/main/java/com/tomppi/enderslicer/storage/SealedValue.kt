package com.tomppi.enderslicer.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One preference value, sealed with a key that does not leave the device.
 *
 * SharedPreferences go into cloud backups and device transfers in the clear, and the Moonraker
 * API key is the whole authorization for a printer. This is the same AES/GCM arrangement the
 * OctoPrint and harness credentials use, with the alias as a parameter so a caller does not
 * need its own copy of the cipher code.
 */
internal object SealedValue {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** Distinguishes a sealed value from the plaintext an older build wrote. */
    const val FORMAT_PREFIX = "sealed:"

    fun seal(alias: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
        return FORMAT_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /** The value back, or null when it is not sealed with this device's key. */
    fun open(alias: String, sealed: String): String? = runCatching {
        require(sealed.startsWith(FORMAT_PREFIX)) { "not sealed" }
        val payload = Base64.decode(sealed.removePrefix(FORMAT_PREFIX), Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(payload)
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..32 && buffer.remaining() > ivSize) { "corrupt" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(alias: String): SecretKey {
        val store = keyStore()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
}
