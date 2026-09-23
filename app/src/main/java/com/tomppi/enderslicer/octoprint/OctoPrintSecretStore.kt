package com.tomppi.enderslicer.octoprint

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What reading the stored API key produced. */
sealed interface ApiKeyLoad {
    /** The stored credential, decrypted. */
    data class Loaded(val apiKey: String) : ApiKeyLoad

    /** No credential is stored for the configured origin. */
    object Missing : ApiKeyLoad

    /**
     * Ciphertext is stored but cannot be turned back into a key right now.
     *
     * Deliberately not a "forget it" signal: the causes are transient or
     * recoverable (a Keystore that is momentarily unavailable, a backup
     * restored without the device-bound key), and the ciphertext is the only
     * copy of a credential the user has to re-approve in OctoPrint's own web
     * UI if it is thrown away.
     */
    data class Unreadable(val cause: Throwable) : ApiKeyLoad
}

class OctoPrintSecretStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadConfig(): OctoPrintConfig = OctoPrintConfig(
        baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
        username = preferences.getString(KEY_USERNAME, "").orEmpty(),
        snapshotUrlOverride = preferences.getString(KEY_SNAPSHOT_URL, "").orEmpty(),
        pollIntervalSeconds = preferences.getInt(KEY_POLL_SECONDS, DEFAULT_POLL_SECONDS).coerceIn(1, 30),
    )

    fun saveConfig(config: OctoPrintConfig) {
        val oldOrigin = preferences.getString(KEY_API_KEY_ORIGIN, null)
        val editor = preferences.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_SNAPSHOT_URL, config.snapshotUrlOverride)
            .putInt(KEY_POLL_SECONDS, config.pollIntervalSeconds.coerceIn(1, 30))
        if (oldOrigin != null && oldOrigin != config.baseUrl) {
            editor.remove(KEY_ENCRYPTED_API_KEY).remove(KEY_API_KEY_ORIGIN)
        }
        check(editor.commit()) { "Unable to persist OctoPrint configuration" }
    }

    fun saveConfiguration(config: OctoPrintConfig, apiKey: String) {
        require(config.isConfigured) { "OctoPrint server configuration is incomplete" }
        require(apiKey.isNotBlank()) { "OctoPrint API key cannot be empty" }
        check(
            preferences.edit()
                .putString(KEY_BASE_URL, config.baseUrl)
                .putString(KEY_USERNAME, config.username)
                .putString(KEY_SNAPSHOT_URL, config.snapshotUrlOverride)
                .putInt(KEY_POLL_SECONDS, config.pollIntervalSeconds.coerceIn(1, 30))
                .putString(KEY_ENCRYPTED_API_KEY, encrypt(apiKey.trim()))
                .putString(KEY_API_KEY_ORIGIN, config.baseUrl)
                .putLong(KEY_CONFIGURATION_GENERATION, preferences.getLong(KEY_CONFIGURATION_GENERATION, 0L) + 1L)
                .commit(),
        ) { "Unable to atomically persist OctoPrint credentials" }
    }

    fun hasApiKey(): Boolean = loadApiKey() is ApiKeyLoad.Loaded

    /**
     * Reads the stored API key.
     *
     * A decrypt failure no longer deletes anything. This runs from the
     * repository's constructor, so every visit to the OctoPrint screen could
     * destroy a credential over a Keystore that was momentarily unavailable, or
     * over a ciphertext restored from backup without its device-bound key - and
     * the user then had to re-approve the app in OctoPrint's web UI to get a new
     * one. The failure is reported instead, and the ciphertext is removed only
     * when the credential is explicitly cleared or its origin changes.
     */
    fun loadApiKey(): ApiKeyLoad {
        val encoded = preferences.getString(KEY_ENCRYPTED_API_KEY, null) ?: return ApiKeyLoad.Missing
        val origin = preferences.getString(KEY_API_KEY_ORIGIN, null)
        val configuredOrigin = preferences.getString(KEY_BASE_URL, "").orEmpty()
        if (origin.isNullOrBlank() || origin != configuredOrigin) {
            preferences.edit().remove(KEY_ENCRYPTED_API_KEY).remove(KEY_API_KEY_ORIGIN).commit()
            return ApiKeyLoad.Missing
        }
        val decrypted = runCatching { decrypt(encoded) }
            .getOrElse { return ApiKeyLoad.Unreadable(it) }
        return decrypted.takeIf(String::isNotBlank)
            ?.let { ApiKeyLoad.Loaded(it) }
            ?: ApiKeyLoad.Missing
    }

    fun saveApiKey(apiKey: String) {
        val config = loadConfig()
        saveConfiguration(config, apiKey)
    }

    fun clearApiKey() {
        check(preferences.edit().remove(KEY_ENCRYPTED_API_KEY).remove(KEY_API_KEY_ORIGIN).commit()) {
            "Unable to clear the OctoPrint API key"
        }
    }

    fun clearAll() {
        check(preferences.edit().clear().commit()) { "Unable to clear OctoPrint configuration" }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
        return FORMAT_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        require(value.startsWith(FORMAT_PREFIX)) { "Unsupported credential format" }
        val payload = Base64.decode(value.removePrefix(FORMAT_PREFIX), Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(payload)
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..32 && buffer.remaining() > ivSize) { "Corrupt encrypted credential" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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

    private companion object {
        const val PREFERENCES_NAME = "octoprint_client"
        const val KEY_ALIAS = "enderslicercura_octoprint_api_key"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_PREFIX = "v1:"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val KEY_SNAPSHOT_URL = "snapshot_url"
        const val KEY_POLL_SECONDS = "poll_seconds"
        const val KEY_ENCRYPTED_API_KEY = "encrypted_api_key"
        const val KEY_API_KEY_ORIGIN = "api_key_origin"
        const val KEY_CONFIGURATION_GENERATION = "configuration_generation"
        const val DEFAULT_POLL_SECONDS = 3
    }
}
