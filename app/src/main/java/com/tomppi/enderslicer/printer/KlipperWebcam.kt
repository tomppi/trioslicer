package com.tomppi.enderslicer.printer

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A camera the Klipper host publishes, as Moonraker describes it.
 *
 * The URLs are made absolute here, against the host the app is talking to, because Moonraker
 * hands out whatever was configured for it: a path like /webcam/snapshot means "the machine you
 * reached", an absolute 127.0.0.1 means the camera on that machine's own loopback, and anything
 * else is a camera somewhere the person configuring it intended. Reading the first as-is would
 * have the phone photograph itself.
 */
/**
 * Public because the state the screens read carries it, and a state that is public cannot have
 * an internal member in its constructor. Nothing outside this app has any use for it.
 */
data class KlipperWebcam(
    val name: String,
    /** For a viewer that can play a stream - a browser, or a WebView one day. */
    val streamUrl: String,
    /** One frame, which is what this app asks for. */
    val snapshotUrl: String,
) {
    companion object {
        /** One entry of Moonraker's webcam list, or null if it is not one to show. */
        fun fromMoonraker(item: JSONObject, host: String): KlipperWebcam? {
            if (!item.optBoolean("enabled", true)) return null
            val snapshot = item.optString("snapshot_url").takeIf { it.isNotBlank() } ?: return null
            val stream = item.optString("stream_url").takeIf { it.isNotBlank() } ?: snapshot
            return KlipperWebcam(
                name = item.optString("name").ifBlank { "Camera" },
                streamUrl = resolve(stream, host),
                snapshotUrl = resolve(snapshot, host),
            )
        }

        /**
         * A camera URL, from the host's point of view to the app's.
         *
         * Three cases, and each is a real configuration somebody has:
         *
         *  - a path, which belongs to whatever web server the host answers on - the machine the
         *    app already reached, and the reason a camera keeps working when the address of the
         *    host changes;
         *  - an absolute loopback address, which is the camera on the host's own machine and
         *    means nothing to a phone: the host is swapped in and the port kept, because the
         *    port is the camera's and only the machine is wrong;
         *  - anything else, which is a camera at an address somebody meant.
         */
        fun resolve(url: String, host: String): String {
            val trimmed = url.trim()
            val clean = host.trim().trimEnd('/')
            return when {
                trimmed.startsWith("/") -> "http://" + clean + trimmed
                isLoopback(trimmed) -> rewriteHost(trimmed, clean)
                else -> trimmed
            }
        }

        private fun isLoopback(url: String): Boolean {
            val authority = authorityOf(url) ?: return false
            val host = authority.substringBefore(':').trim('[', ']').lowercase()
            return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "0.0.0.0"
        }

        /** "http://host:8081/snapshot" with the host swapped and the port left alone. */
        private fun rewriteHost(url: String, host: String): String {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return url
            val scheme = url.substring(0, schemeEnd + 3)
            val rest = url.substring(schemeEnd + 3)
            val authority = rest.substringBefore('/')
            val path = rest.substring(authority.length)
            val port = authority.substringAfter(':', "")
            return scheme + host + (if (port.isNotEmpty()) ":" + port else "") + path
        }

        private fun authorityOf(url: String): String? {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return null
            val rest = url.substring(schemeEnd + 3)
            return rest.substringBefore('/').takeIf { it.isNotBlank() }
        }
    }
}

/**
 * The cameras Moonraker knows about, and one frame at a time from them.
 *
 * Snapshots rather than the stream: the app shows a picture on a printer screen, and the stream
 * is a multipart response that would need a decoder to draw. One request a second is a frame a
 * second, which is a camera you can watch a first layer on and costs nothing to be wrong about.
 */
internal class MoonrakerWebcams(
    private val host: String,
    private val port: Int = MoonrakerTransport.DEFAULT_PORT,
    private val apiKey: String? = null,
) {

    fun list(): List<KlipperWebcam> {
        val answer = get("/server/webcams/list") ?: return emptyList()
        val items = answer.optJSONObject("result")?.optJSONArray("webcams") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let { KlipperWebcam.fromMoonraker(it, host) }
        }
    }

    /** One frame, or null if the camera did not answer. */
    fun snapshot(url: String): ByteArray? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        apiKey?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("X-Api-Key", it) }
        val code = connection.responseCode
        val bytes = if (code in 200..299) connection.inputStream.use { it.readBytes() } else null
        connection.disconnect()
        if (bytes == null) Log.i(TAG, "the camera answered $code for $url")
        bytes
    }.getOrNull()

    private fun get(path: String): JSONObject? = runCatching {
        val connection = URL("http://" + host.trim() + ":" + port + path).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        apiKey?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("X-Api-Key", it) }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        JSONObject(text)
    }.onFailure { Log.i(TAG, "webcam list failed: ${it.message}") }.getOrNull()

    private companion object {
        const val TAG = "KlipperWebcam"
        const val TIMEOUT_MS = 6_000
    }
}
