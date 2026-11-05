package com.tomppi.enderslicer.scanruler.engine

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where ScanRuler's agent-facing files go.
 *
 * The app writes its own exports into MediaStore's Downloads/ScanRuler/ collection - no
 * storage permission, no arbitrary phone path - and returns the content URI to the agent.
 * A result object carrying binary values (the page sends them in a binary side frame) is
 * rewritten value by value: each ByteArray is written and becomes {name, mimeType, size, uri}.
 */
object ScanRulerFileExports {

    const val COLLECTION = "ScanRuler"

    data class Written(val name: String, val mimeType: String, val size: Long, val uri: String)

    fun write(
        context: Context,
        bytes: ByteArray,
        name: String,
        mimeType: String = "application/octet-stream",
    ): Written {
        val safeName = name.replace('/', '_').replace('\\', '_').ifBlank { "scanruler-export" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + COLLECTION)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("The app could not create a Download/$COLLECTION entry.")
        // MediaStore owns the stored name: a collision becomes "name (1).ext", and the result
        // reports the name the user will actually find in Download/ScanRuler.
        val displayName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: safeName
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("The app could not open its Download/$COLLECTION entry.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        return Written(displayName, mimeType, bytes.size.toLong(), uri.toString())
    }

    /**
     * Rewrites every ByteArray in [result] to a written-file descriptor, keeping the page's
     * file metadata when it provided one. Returns the number of files written.
     */
    fun rewriteBinaryValues(context: Context, result: JSONObject): Int {
        var written = 0
        fun walk(value: Any?, keyName: String?, parent: JSONObject?, parentKey: String?) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (child is ByteArray) {
                            val name = value.optString("name").ifBlank { "scanruler-export" }
                            val mimeType = value.optString("mimeType").ifBlank { mimeTypeFor(name) }
                            val file = write(context, child, name, mimeType)
                            value.put(key, JSONObject()
                                .put("name", file.name)
                                .put("mimeType", file.mimeType)
                                .put("size", file.size)
                                .put("uri", file.uri))
                            written++
                        } else {
                            walk(child, key, value, key)
                        }
                    }
                }
                is JSONArray -> for (index in 0 until value.length()) walk(value.opt(index), null, null, null)
                else -> {}
            }
        }
        walk(result, null, null, null)
        return written
    }

    /** The MIME type the file's extension asks for; anything unknown is the octet stream. */
    fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "step", "stp" -> "application/step"
        "stl" -> "model/stl"
        "3mf" -> "model/3mf"
        "svg" -> "image/svg+xml"
        "dxf" -> "image/vnd.dxf"
        "csv" -> "text/csv"
        "png" -> "image/png"
        "json" -> "application/json"
        "scanruler" -> "application/x-scanruler"
        else -> "application/octet-stream"
    }
}
