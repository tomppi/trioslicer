package com.tomppi.enderslicer.printer

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The files on a Klipper host that is not this phone.
 *
 * The app's own route copies a sliced file into its private directory and points klippy at it,
 * because both are on this device. A host on the network has its own directory, so the file has
 * to be sent: Moonraker takes an upload over HTTP, lists what it has, and deletes by name. This is
 * that API, and only that API - the printing itself is still klippy's own command, which works
 * exactly as it does locally once the file is where the host can see it.
 *
 * Endpoints and field names were read off the Moonraker running beside this printer, not
 * remembered: the upload form takes the file in "file", the root in "root", and answers with the
 * item it created; the list is a GET of /server/files/list?root=gcodes returning path, size and a
 * float modification time; metadata is a GET per file, and it is where a slicer's own numbers and
 * the thumbnail live, which is why the app asks for it only when a file is opened.
 */
internal class MoonrakerFiles(
    private val host: String,
    private val port: Int = MoonrakerTransport.DEFAULT_PORT,
    private val apiKey: String? = null,
) {

    /** What the host has, newest first, as the app's own file type. */
    fun list(): List<KlipperGcodeFile> {
        val answer = request(listPath(), "GET") ?: return emptyList()
        val items = answer.optJSONArray("result") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let { fromHost(it) }
        }.sortedByDescending { it.modifiedAtMillis }
    }

    /**
     * Send a file, and say whether the host took it.
     *
     * The name is the one the host will know it by, so it is the name klippy is later told to
     * print - which is why [KlipperPrint.fileName] cleans it on the way in rather than here.
     */
    fun upload(source: File, name: String): Boolean {
        val boundary = "----TrioSlicer" + UUID.randomUUID().toString().replace("-", "")
        val body = uploadBody(boundary, name, source.readBytes())
        val answer = request(
            path = UPLOAD_PATH,
            method = "POST",
            body = body,
            contentType = "multipart/form-data; boundary=" + boundary,
        ) ?: return false
        // Moonraker answers with the item it created; an error arrives as an error instead.
        return answer.optJSONObject("result")?.has("item") == true
    }

    /** Delete one, by the name the host knows it by. */
    fun delete(name: String): Boolean =
        request(filePath(name), "DELETE") != null

    /**
     * A file's own numbers and thumbnail, as the host read them from the file.
     *
     * Asked for one file at a time, on the screen that shows one file: the list call does not
     * carry a slicer's metadata, and fetching it for every file to display none of it would be a
     * request per row for nothing.
     */
    fun metadata(name: String): KlipperGcodeFile? {
        val answer = request(metadataPath(name), "GET") ?: return null
        val result = answer.optJSONObject("result") ?: return null
        val listed = fromHost(
            JSONObject()
                .put("path", name)
                .put("size", result.optLong("size"))
                .put("modified", result.optDouble("modified")),
        )
        return listed.copy(
            slicer = result.optString("slicer"),
            estimatedSeconds = result.optDouble("estimated_time")
                .takeIf { it > 0.0 }?.toInt(),
            filamentMillimetres = result.optDouble("filament_total")
                .takeIf { it > 0.0 },
            layerHeight = result.optDouble("layer_height").takeIf { it > 0.0 },
            layerCount = result.optInt("layer_count").takeIf { it > 0 },
            thumbnail = thumbnailOf(result),
        )
    }

    /**
     * The first thumbnail the host holds for a file, as the app stores them.
     *
     * Base64, because that is what the app's own parser produces from a comment in a file and
     * what the screen decodes; the host serves the PNG, so it is fetched and re-encoded rather
     * than re-derived.
     */
    private fun thumbnailOf(result: JSONObject): String? {
        val thumbnails = result.optJSONArray("thumbnails") ?: return null
        val first = (0 until thumbnails.length())
            .mapNotNull { thumbnails.optJSONObject(it) }
            .maxByOrNull { it.optInt("size") } ?: return null
        val relative = first.optString("relative_path").ifBlank { return null }
        val bytes = bytes("/server/files/gcodes/" + encodePath(relative)) ?: return null
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun request(
        path: String,
        method: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): JSONObject? = runCatching {
        val connection = URL("http://" + host.trim() + ":" + port + path).openConnection()
            as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        apiKey?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("X-Api-Key", it) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            Log.i(TAG, "$method $path answered $code: " + text.take(200))
            null
        } else {
            runCatching { JSONObject(text) }.getOrNull()
        }
    }.onFailure { Log.i(TAG, "$method $path failed: ${it.message}") }.getOrNull()

    /** Raw bytes, for a thumbnail: not everything the host serves is JSON. */
    private fun bytes(path: String): ByteArray? = runCatching {
        val connection = URL("http://" + host.trim() + ":" + port + path).openConnection()
            as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        apiKey?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("X-Api-Key", it) }
        val code = connection.responseCode
        val data = if (code in 200..299) connection.inputStream.use { it.readBytes() } else null
        connection.disconnect()
        data
    }.getOrNull()

    companion object {
        private const val TAG = "KlipperRemoteFiles"

        /** Where a host keeps files it will print. */
        const val ROOT = "gcodes"

        const val UPLOAD_PATH = "/server/files/upload"

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 60_000

        /** The list, as the host serves it. */
        fun listPath(): String = "/server/files/list?root=" + ROOT

        /** One file, by the name the host knows it by. */
        fun filePath(name: String): String = "/server/files/" + ROOT + "/" + encodePath(name)

        /** One file's slicer metadata. */
        fun metadataPath(name: String): String =
            "/server/files/metadata?filename=" + URLEncoder.encode(name, "UTF-8")

        /**
         * One item of the host's list, as the app's own file type.
         *
         * The host names a file by its path under the root, and the app names it by the basename
         * - which is what klippy is told to print and what the delete path takes back.
         */
        fun fromHost(item: JSONObject): KlipperGcodeFile {
            val path = item.optString("path").trimStart('/')
            val name = path.substringAfterLast('/')
            return KlipperGcodeFile(
                name = name,
                sizeBytes = item.optLong("size"),
                // The host reports seconds as a float; the screens want milliseconds.
                modifiedAtMillis = (item.optDouble("modified") * 1000.0).toLong(),
            )
        }

        /**
         * The multipart body Moonraker's upload endpoint reads.
         *
         * Pure, and therefore testable: the boundary has to appear in the header and before each
         * part, the file has to arrive as a file part named "file" with the name the host should
         * keep, and the root has to be a separate field. Getting any of that wrong is a 400 with
         * nothing on screen to say why.
         */
        fun uploadBody(boundary: String, name: String, content: ByteArray): ByteArray {
            val head = StringBuilder()
            head.append("--").append(boundary).append("\r\n")
            head.append("Content-Disposition: form-data; name=\"root\"\r\n\r\n")
            head.append(ROOT).append("\r\n")
            head.append("--").append(boundary).append("\r\n")
            head.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
            head.append(name.replace("\"", "")).append("\"\r\n")
            head.append("Content-Type: application/octet-stream\r\n\r\n")
            val tail = "\r\n--" + boundary + "--\r\n"
            return head.toString().toByteArray(StandardCharsets.UTF_8) +
                content +
                tail.toByteArray(StandardCharsets.UTF_8)
        }

        /** A path segment, percent-encoded so a name with a space survives the trip. */
        fun encodePath(name: String): String =
            URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    }
}
