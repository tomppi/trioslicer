package com.tomppi.enderslicer.cadagent

import android.content.Context
import android.content.SharedPreferences
import com.tomppi.enderslicer.data.AppStateStore
import com.tomppi.enderslicer.harness.HarnessClient
import com.tomppi.enderslicer.storage.SealedValue
import java.net.URL

/**
 * Which agent authors the CAD document.
 *
 * The default is the app's own agent: it calls an OpenAI-compatible endpoint straight from the
 * phone, so the user needs no computer, no harness and no second screen. The harness stays as
 * the advanced path - an agent running on a PC that reaches this phone's engine bridge - and the
 * sheet says which one is driving.
 */
enum class AgentMode(
    /** What the segmented choice in the sheet says. */
    val label: String,
) {
    IN_APP("In-app"),
    HARNESS("Harness"),
    ;

    companion object {
        fun fromStored(stored: String?): AgentMode =
            entries.firstOrNull { it.name == stored } ?: IN_APP
    }
}

/**
 * The endpoint the in-app agent calls, as the sheet renders it.
 *
 * The API key is deliberately not a field: nothing that builds a string, a log line or a chat
 * message can carry a secret it never holds. [hasApiKey] is the only fact about the key the UI
 * gets, and [keyMask] is the only rendering it has. The key itself exists in memory only for the
 * length of one run ([StoredAgentSettings]).
 */
data class AgentSettings(
    val mode: AgentMode = AgentMode.IN_APP,
    /**
     * DeepSeek's OpenAI-compatible API is the shipped default: its chat completions, its
     * search and its fetch live under the same base URL and the same key, so one setting is the
     * whole endpoint. Both fields are editable - a user on another provider changes them.
     */
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val hasApiKey: Boolean = false,
    /** Whether the agent may search the web and read pages through the same endpoint. */
    val webSearch: Boolean = true,
    /**
     * Whether a message that carries a face selection also carries a render of the plate.
     *
     * On by default: "make this face 2 mm thicker" is a request about a face the user picked, and
     * the picked face is tinted amber in the picture, so the model sees which one instead of
     * inferring it from the words. It is a switch because an endpoint that charges per image, or
     * a slow connection, may make the extra pixels a bad trade.
     */
    val lendView: Boolean = true,
) {
    /**
     * True when the endpoint can be called: a base URL that parses and a model name. The key is
     * optional on purpose - a provider on the user's own network (Ollama, vLLM, llama.cpp) often
     * has no auth, and demanding a key there would make the feature unusable for the people who
     * run their own endpoint.
     */
    val isConfigured: Boolean
        get() = model.isNotBlank() && baseUrlProblem() == null

    /** The only rendering the key ever gets in the sheet. */
    val keyMask: String
        get() = if (hasApiKey) MASKED_KEY else "not set"

    /** True when a key would travel in the clear to another machine. */
    val isCleartextRemote: Boolean
        get() = HarnessClient.isUnencryptedRemote(baseUrl)

    /**
     * The backend line the chat header shows: the model and the host it answers on, never the
     * path and never the key - "deepseek-chat at api.deepseek.com", or "not configured".
     */
    fun endpointLabel(): String {
        if (baseUrlProblem() != null || model.isBlank()) return "not configured"
        val url = runCatching { URL(baseUrl.trim()) }.getOrNull() ?: return "not configured"
        val host = url.host ?: return "not configured"
        val port = if (url.port == -1) "" else ":" + url.port
        return model.trim() + " at " + host + port
    }

    /** The named reason this base URL cannot be called yet, or null when it can. */
    fun baseUrlProblem(): String? {
        val address = baseUrl.trim()
        if (address.isEmpty()) return "add the endpoint's base URL, e.g. https://api.openai.com/v1"
        val url = runCatching { URL(address) }.getOrNull()
            ?: return "\"" + address.take(80) + "\" is not a URL"
        if (!url.protocol.equals("http", ignoreCase = true) &&
            !url.protocol.equals("https", ignoreCase = true)
        ) {
            return "the base URL must start with http:// or https://"
        }
        if (url.host.isNullOrBlank()) return "the base URL has no host"
        return null
    }

    /** What the sheet says is driving the document right now. */
    fun drivingSentence(): String = when (mode) {
        AgentMode.IN_APP -> if (isConfigured) {
            "Driving: the app's own agent, calling " + baseUrl.trim() + " (" + model.trim() +
                ") directly from this phone. No computer in the loop."
        } else {
            "Driving: the app's own agent. Set the endpoint below - it is called directly " +
                "from this phone, so nothing has to be running on a desk."
        }
        AgentMode.HARNESS ->
            "Driving: the harness on your computer. The agent runs there and reaches this " +
                "phone's engine bridge - the desk path, kept as the advanced option."
    }

    companion object {
        /** The endpoint shipped as the default; the user can change it. */
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        /** The model shipped as the default; the user can change it. */
        const val DEFAULT_MODEL = "deepseek-chat"

        const val MASKED_KEY = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
    }
}

/**
 * The settings plus the key, held only for the length of one run.
 *
 * Its [toString] never prints the key: a data class whose fields leak through string
 * interpolation into a log line is exactly how a credential ends up in a crash report, so the
 * generated toString is replaced rather than trusted.
 */
data class StoredAgentSettings(
    val settings: AgentSettings,
    val apiKey: String = "",
) {
    val isConfigured: Boolean get() = settings.isConfigured

    override fun toString(): String =
        "StoredAgentSettings(mode=" + settings.mode + ", baseUrl=" + settings.baseUrl +
            ", model=" + settings.model + ", apiKey=" +
            (if (apiKey.isBlank()) "absent" else "present") + ")"
}

/**
 * Where the in-app agent's endpoint is kept.
 *
 * Base URL and model are plain preferences in the app's own file. The API key is the one secret:
 * it is sealed with the app's existing Keystore arrangement ([SealedValue] - AES/GCM under a key
 * that never leaves the device), the same pattern as the Moonraker API key, so preferences that
 * go into a cloud backup or a device transfer carry a blob only this device can open.
 *
 * The key is written only when the user saves a new one, read only into memory for a run, and
 * never rendered: [AgentSettings] does not even have a field for it.
 */
class AgentSettingsStore internal constructor(
    private val preferences: SharedPreferences,
    private val seal: (String) -> String,
    private val open: (String) -> String?,
) {
    constructor(context: Context) : this(
        preferences = context.applicationContext.getSharedPreferences(
            AppStateStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
        seal = { value -> SealedValue.seal(KEY_ALIAS, value) },
        open = { stored -> SealedValue.open(KEY_ALIAS, stored) },
    )

    /** The settings with the key, for one run. Callers must not keep this around. */
    fun load(): StoredAgentSettings = StoredAgentSettings(loadSettings(), storedKey())

    /** The settings without the key: everything the sheet may render. */
    fun loadSettings(): AgentSettings = AgentSettings(
        mode = AgentMode.fromStored(preferences.getString(KEY_MODE, null)),
        baseUrl = preferences.getString(KEY_BASE_URL, null)
            ?.takeIf { it.isNotBlank() } ?: AgentSettings.DEFAULT_BASE_URL,
        model = preferences.getString(KEY_MODEL, null)
            ?.takeIf { it.isNotBlank() } ?: AgentSettings.DEFAULT_MODEL,
        hasApiKey = preferences.getString(KEY_API_KEY, "").orEmpty().isNotBlank(),
        webSearch = preferences.getBoolean(KEY_WEB_SEARCH, true),
        lendView = preferences.getBoolean(KEY_LEND_VIEW, true),
    )

    /**
     * Saves the endpoint.
     *
     * [apiKey] null keeps whatever key is stored; an empty string clears it; anything else
     * replaces it. That is what makes the sheet's one field usable without ever loading the
     * stored key back into the text field.
     */
    fun save(settings: AgentSettings, apiKey: String? = null): Boolean {
        val editor = preferences.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putString(KEY_BASE_URL, settings.baseUrl.trim())
            .putString(KEY_MODEL, settings.model.trim())
            .putBoolean(KEY_WEB_SEARCH, settings.webSearch)
            .putBoolean(KEY_LEND_VIEW, settings.lendView)
        if (apiKey != null) {
            val wanted = apiKey.trim()
            if (wanted.isEmpty()) {
                editor.remove(KEY_API_KEY)
            } else {
                editor.putString(KEY_API_KEY, seal(wanted))
            }
        }
        return editor.commit()
    }

    /** The segmented choice is persisted the moment it is tapped. */
    fun saveMode(mode: AgentMode): Boolean =
        preferences.edit().putString(KEY_MODE, mode.name).commit()

    fun clearApiKey(): Boolean = preferences.edit().remove(KEY_API_KEY).commit()

    /**
     * The key, or empty when there is none this device can use.
     *
     * A plaintext value can only be one written before this was sealed, or a restored
     * preferences file: it is used as it is. A sealed blob this device cannot open - a restored
     * backup, a Keystore entry that is gone - is not the key: sending it earns a 401, and saving
     * it again would seal the blob and destroy what was there. Empty is the honest answer and
     * the sheet asks for the key again.
     */
    private fun storedKey(): String {
        val stored = preferences.getString(KEY_API_KEY, "").orEmpty()
        if (stored.isBlank()) return ""
        if (!stored.startsWith(SealedValue.FORMAT_PREFIX)) return stored
        return open(stored).orEmpty()
    }

    companion object {
        internal const val KEY_MODE = "cad-agent-mode"
        internal const val KEY_BASE_URL = "cad-agent-base-url"
        internal const val KEY_MODEL = "cad-agent-model"
        internal const val KEY_API_KEY = "cad-agent-api-key"
        internal const val KEY_WEB_SEARCH = "cad-agent-web-search"
        internal const val KEY_LEND_VIEW = "cad-agent-lend-view"

        /** The Keystore entry the key is sealed with: one per use, never shared. */
        private const val KEY_ALIAS = "enderslicercura_cad_agent_api_key"
    }
}
