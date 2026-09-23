package com.tomppi.enderslicer.harness

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

/**
 * Where the harness lives, and what this app keeps there.
 *
 * The launch token is what authenticates this app to the harness, and the
 * harness prints it exactly once - in the URL the launcher writes to its own
 * console. Nothing publishes it any more: the public `auth.json` this app used
 * to read handed a 30-day session to anyone who could reach the port. The token
 * is held here and written encrypted by [HarnessConfigStore], because this
 * bearer credential has a reader that uses it.
 */
data class HarnessConfig(
    val baseUrl: String = "",
    /**
     * Working directory for sessions this app creates, on the harness host.
     *
     * This decides which skills the agent can see: skills are discovered from
     * the workspace, so a session rooted elsewhere cannot load them however
     * clearly the prompt names them. Left empty, the harness picks its own
     * default and the app's skills are invisible.
     */
    val workspace: String = "",
    /**
     * Session this app talks in.
     *
     * Remembered because sessions are cheap to create and expensive to lose:
     * creating one per launch left the previous conversation orphaned on the
     * harness and started the user from an empty chat every time.
     */
    val sessionId: String = "",
    /**
     * Bearer token from the harness's printed `?token=…` URL.
     *
     * Never written verbatim: [HarnessConfigStore] encrypts it with a key that
     * cannot leave this device.
     */
    val launchToken: String = "",
    /**
     * Set once the user has accepted plain HTTP to a machine other than this
     * one. Loopback is always allowed and never sets it.
     */
    val allowCleartext: Boolean = false,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /**
     * Folds a freshly parsed address into this configuration.
     *
     * The address field holds a bare URL, so a pasted value that parses to an
     * empty address - the field cleared, or a URL that was not an address at
     * all - must not overwrite the stored one the chat is still using. The
     * workspace and session always come from the caller, which is the code that
     * just connected.
     *
     * The token follows the address rather than the call: a token minted by one
     * harness means nothing to another, so pointing the app somewhere else
     * drops the old one instead of carrying a credential across. The cleartext
     * permission is scoped the same way - it was given for one host.
     */
    fun mergedWith(parsed: HarnessConfig, workspace: String, sessionId: String): HarnessConfig {
        val address = parsed.baseUrl.ifBlank { baseUrl }
        val sameHarness = address.equals(baseUrl, ignoreCase = true)
        return copy(
            baseUrl = address,
            workspace = workspace,
            sessionId = sessionId,
            launchToken = when {
                parsed.launchToken.isNotEmpty() -> parsed.launchToken
                sameHarness -> launchToken
                else -> ""
            },
            allowCleartext = when {
                parsed.allowCleartext -> true
                sameHarness -> allowCleartext
                else -> false
            },
        )
    }

    companion object {
        /**
         * Splits what the user pastes into an address and the token it carries.
         *
         * The harness prints a URL carrying its launch token as `?token=…`, so
         * the natural thing to paste is that whole URL. It is the only place the
         * token exists outside the harness, which is why it is kept rather than
         * dropped: every later request needs it to mint the session cookie.
         * Everything else from the query onwards is discarded, and so is any
         * fragment, which never identifies the server either.
         */
        fun parseLaunchUrl(pasted: String): HarnessConfig {
            val withoutFragment = pasted.trim().substringBefore('#')
            if (withoutFragment.isEmpty()) return HarnessConfig()
            return HarnessConfig(
                baseUrl = withoutFragment.substringBefore('?').trimEnd('/'),
                launchToken = queryParameter(withoutFragment, TOKEN_PARAMETER),
            )
        }

        /**
         * One decoded query parameter, or an empty string.
         *
         * Hand-rolled rather than URL-based because the pasted value is often
         * not a URL at all: a bare `100.64.0.10:3080` typed by hand has to keep
         * working.
         */
        private fun queryParameter(value: String, name: String): String {
            val query = value.substringAfter('?', "")
            if (query.isEmpty()) return ""
            return query.split('&')
                .map { it.substringBefore('=') to it.substringAfter('=', "") }
                .firstOrNull { (key, _) -> key == name }
                ?.second
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                ?.takeIf(String::isNotBlank)
                .orEmpty()
        }

        private const val TOKEN_PARAMETER = "token"
    }
}

/**
 * Persists where the harness lives, the launch token, and the session cookie.
 *
 * The token and the cookie are both credentials for a harness that can run code
 * on its host, so neither is written in the clear: each is encrypted with a
 * device-bound Android Keystore key, and each is dropped when the stored
 * address changes, because a credential minted by one harness is not a
 * credential for another. This file is excluded from cloud backup and device
 * transfer, so a restored copy of the ciphertext never exists anywhere.
 */
class HarnessConfigStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): HarnessConfig {
        val baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty()
        return HarnessConfig(
            baseUrl = baseUrl,
            workspace = preferences.getString(KEY_WORKSPACE, "").orEmpty(),
            sessionId = preferences.getString(KEY_SESSION_ID, "").orEmpty(),
            launchToken = loadSecret(KEY_ENCRYPTED_TOKEN, KEY_TOKEN_ORIGIN, baseUrl),
            allowCleartext = loadCleartextPermission(baseUrl),
        )
    }

    fun save(config: HarnessConfig) {
        val baseUrl = config.baseUrl.trimEnd('/')
        val editor = preferences.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_WORKSPACE, config.workspace.trim())
            .putString(KEY_SESSION_ID, config.sessionId)
            .remove(KEY_LEGACY_ENCRYPTED_TOKEN)
        if (config.launchToken.isBlank()) {
            editor.remove(KEY_ENCRYPTED_TOKEN).remove(KEY_TOKEN_ORIGIN)
        } else {
            editor.putString(KEY_ENCRYPTED_TOKEN, encrypt(config.launchToken))
                .putString(KEY_TOKEN_ORIGIN, baseUrl)
        }
        if (config.allowCleartext) {
            editor.putBoolean(KEY_ALLOW_CLEARTEXT, true).putString(KEY_CLEARTEXT_ORIGIN, baseUrl)
        } else {
            editor.remove(KEY_ALLOW_CLEARTEXT).remove(KEY_CLEARTEXT_ORIGIN)
        }
        check(editor.commit()) { "Unable to persist the harness configuration" }
        deleteLegacyTokenKey()
    }

    /**
     * The session cookie a previous run minted, if it is still for [baseUrl].
     *
     * Kept because the harness signs it for 30 days and it outlives a harness
     * restart, while the launch token does not: without this, every restart
     * meant pasting the launch URL again.
     */
    fun loadCookie(baseUrl: String): String = loadSecret(KEY_ENCRYPTED_COOKIE, KEY_COOKIE_ORIGIN, baseUrl)

    fun saveCookie(baseUrl: String, cookie: String) {
        val origin = baseUrl.trimEnd('/')
        check(
            preferences.edit()
                .putString(KEY_ENCRYPTED_COOKIE, encrypt(cookie))
                .putString(KEY_COOKIE_ORIGIN, origin)
                .commit(),
        ) { "Unable to persist the harness session cookie" }
    }

    /**
     * Session id for the modelling conversation.
     *
     * Kept apart from [HarnessConfig.sessionId] because the two chats are two
     * conversations with two agents: modelling a part from scratch and turning
     * a photograph into one share nothing but the harness they run on, and
     * letting them share a session meant the modelling agent inherited a
     * transcript about somebody's photograph.
     */
    fun loadModellingSession(): String =
        preferences.getString(KEY_MODELLING_SESSION_ID, "").orEmpty()

    fun saveModellingSession(sessionId: String) {
        check(
            preferences.edit().putString(KEY_MODELLING_SESSION_ID, sessionId).commit(),
        ) { "Unable to persist the modelling session" }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Unable to clear the harness configuration" }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
        deleteLegacyTokenKey()
    }

    /** True when the user accepted cleartext for exactly this address. */
    private fun loadCleartextPermission(baseUrl: String): Boolean {
        val origin = preferences.getString(KEY_CLEARTEXT_ORIGIN, null)
        if (!preferences.getBoolean(KEY_ALLOW_CLEARTEXT, false) || origin != baseUrl.trimEnd('/')) {
            return false
        }
        return baseUrl.isNotBlank()
    }

    /**
     * One encrypted value, or an empty string.
     *
     * A value whose recorded origin is not the configured address is removed
     * rather than returned: it belongs to a different harness. A value that no
     * longer decrypts - the Keystore key is gone after a restore to another
     * device - is removed too, because nothing can ever read it again.
     */
    private fun loadSecret(valueKey: String, originKey: String, baseUrl: String): String {
        val encoded = preferences.getString(valueKey, null) ?: return ""
        val origin = preferences.getString(originKey, null)
        if (baseUrl.isBlank() || origin != baseUrl.trimEnd('/')) {
            preferences.edit().remove(valueKey).remove(originKey).commit()
            return ""
        }
        return runCatching { decrypt(encoded) }.getOrElse {
            preferences.edit().remove(valueKey).remove(originKey).commit()
            ""
        }
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

    /**
     * Removes the Keystore key an earlier build encrypted the launch token with.
     *
     * That build's format is not this one's, and it has nothing left to
     * decrypt; leaving it behind would keep a credential's key alive for no
     * reader. A device that never ran that build has no such entry, which
     * [KeyStore.deleteEntry] treats as a no-op.
     */
    private fun deleteLegacyTokenKey() {
        runCatching { keyStore().deleteEntry(LEGACY_TOKEN_KEY_ALIAS) }
    }

    private companion object {
        const val PREFERENCES_NAME = "harness_config"
        const val KEY_BASE_URL = "base_url"
        const val KEY_WORKSPACE = "workspace"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_MODELLING_SESSION_ID = "modelling_session_id"
        const val KEY_ENCRYPTED_TOKEN = "launch_token_ciphertext"
        const val KEY_TOKEN_ORIGIN = "launch_token_origin"
        const val KEY_ENCRYPTED_COOKIE = "session_cookie_ciphertext"
        const val KEY_COOKIE_ORIGIN = "session_cookie_origin"
        const val KEY_ALLOW_CLEARTEXT = "allow_cleartext"
        const val KEY_CLEARTEXT_ORIGIN = "allow_cleartext_origin"
        /** Token ciphertext written by a build that stored the launch token; dropped by [save]. */
        const val KEY_LEGACY_ENCRYPTED_TOKEN = "encrypted_token"
        const val LEGACY_TOKEN_KEY_ALIAS = "enderslicer_harness_token"
        const val KEY_ALIAS = "enderslicercura_harness_launch_token"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_PREFIX = "v1:"
    }
}
