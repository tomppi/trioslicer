package com.tomppi.enderslicer.printer

import android.content.Context
import com.tomppi.enderslicer.storage.SealedValue

/**
 * Which Klipper host this app is driving.
 *
 * Two, and only one of them can hold the printer: klippy inside the phone, reached over its unix
 * socket, and klippy on a computer, reached over Moonraker. The screens are the same for both, so
 * the choice is a transport and a label - see [transportFor].
 */
internal enum class KlipperHostMode { DEVICE, PC }

/**
 * The API key to use, given what the preferences hold.
 *
 * A key written before this was sealed is plaintext and has to keep working: it is used as it
 * is, and sealed the next time the choice is saved. A value that cannot be opened is treated
 * the same way - one this app cannot read is not a reason to refuse to connect.
 */
internal fun apiKeyFromStored(stored: String, open: (String) -> String?): String {
    if (stored.isBlank()) return ""
    // Plaintext, from a build before this was sealed: used as it is and sealed on the next save.
    if (!stored.startsWith(SealedValue.FORMAT_PREFIX)) return stored
    // Sealed, and this device cannot open it - a restored backup, a Keystore entry that is gone.
    // The blob is not the key: sending it earns a 401, and saving would seal the blob again and
    // destroy what was there. Empty is better than something wrong, and the user is asked.
    return open(stored).orEmpty()
}

/** The host the app is pointed at, and how to reach it. */
internal data class KlipperHostChoice(
    val mode: KlipperHostMode = KlipperHostMode.DEVICE,
    val host: String = "",
    val port: Int = MoonrakerTransport.DEFAULT_PORT,
    val apiKey: String = "",
) {
    val isRemote: Boolean get() = mode == KlipperHostMode.PC

    /** True when this choice is complete enough to connect with. */
    val isUsable: Boolean get() = !isRemote || host.isNotBlank()

    /** What a screen calls this host, in the few characters there are room for. */
    val label: String get() = if (isRemote) host.ifBlank { "not set" } else "this device"
}

/**
 * The transport for a choice.
 *
 * A function rather than a method on the store so that both arms can be exercised without a
 * device: the local one is klippy's socket, the remote one is Moonraker's WebSocket, and which
 * of them is built is the whole of what the mode decides.
 */
internal fun KlipperHostChoice.transportFor(socketPath: String): KlipperTransport = when (mode) {
    KlipperHostMode.PC -> MoonrakerTransport(
        host = host.trim(),
        port = port,
        apiKey = apiKey.takeIf { it.isNotBlank() },
    )
    KlipperHostMode.DEVICE -> LocalSocketTransport(socketPath)
}

/**
 * Where the choice is kept.
 *
 * Plain preferences, and deliberately dull: it is a host name, a port and a key, and the only
 * thing worth getting right is that an empty host is never treated as a host.
 */
internal class KlipperHostChoiceStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): KlipperHostChoice {
        val mode = runCatching {
            KlipperHostMode.valueOf(preferences.getString(KEY_MODE, null) ?: KlipperHostMode.DEVICE.name)
        }.getOrDefault(KlipperHostMode.DEVICE)
        return KlipperHostChoice(
            mode = mode,
            host = preferences.getString(KEY_HOST, "").orEmpty(),
            port = preferences.getInt(KEY_PORT, MoonrakerTransport.DEFAULT_PORT),
            apiKey = apiKeyFromStored(preferences.getString(KEY_API_KEY, "").orEmpty()) {
                SealedValue.open(API_KEY_ALIAS, it)
            },
        )
    }

    fun save(choice: KlipperHostChoice) {
        preferences.edit()
            .putString(KEY_MODE, choice.mode.name)
            .putString(KEY_HOST, choice.host.trim())
            .putInt(KEY_PORT, choice.port)
            .putString(
                KEY_API_KEY,
                if (choice.apiKey.isBlank()) "" else SealedValue.seal(API_KEY_ALIAS, choice.apiKey.trim()),
            )
            .apply()
    }

    private companion object {
        const val PREFERENCES = "klipper-host"
        const val KEY_MODE = "mode"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_API_KEY = "apiKey"

        /** The Keystore entry the key is sealed with: one per use, never shared. */
        const val API_KEY_ALIAS = "enderslicercura_moonraker_api_key"
    }
}
