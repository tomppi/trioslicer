package com.tomppi.enderslicer.scanruler.engine

import org.json.JSONObject

/**
 * The page link's frames (upstream src/commands/framing.ts, mirrored here).
 * A message that carries file bytes names where they belong at its top-level "binary":
 * "result.file.bytes"; the next frame is binary and holds them, while the JSON carries
 * a {size} object in their place.
 */
internal object ScanRulerFrames {

    sealed class Frame {
        data class Text(val text: String) : Frame()
        data class Binary(val bytes: ByteArray) : Frame()
    }

    private val UNSAFE = setOf("__proto__", "constructor", "prototype")

    private fun keys(path: String): List<String> {
        val parts = path.split('.')
        require(parts.isNotEmpty() && parts.none { it.isEmpty() || it in UNSAFE }) {
            "Unsafe binary path: $path."
        }
        return parts
    }

    private fun getAt(root: JSONObject, path: String): Any? {
        var at: Any? = root
        for (key in keys(path)) {
            at = if (at is JSONObject) at.opt(key) else return null
        }
        return at
    }

    private fun setAt(root: JSONObject, path: String, value: Any?) {
        val parts = keys(path)
        var at = root
        for (key in parts.dropLast(1)) {
            at = at.optJSONObject(key) ?: JSONObject().also { at.put(key, it) }
        }
        at.put(parts.last(), value)
    }

    fun encode(message: JSONObject, binaryPath: String? = null): List<Frame> {
        if (binaryPath == null) return listOf(Frame.Text(message.toString()))
        val bytes = getAt(message, binaryPath) as? ByteArray
            ?: throw IllegalArgumentException("No bytes at $binaryPath.")
        val head = JSONObject(message.toString())
        setAt(head, binaryPath, JSONObject().put("size", bytes.size))
        head.put("binary", binaryPath)
        return listOf(Frame.Text(head.toString()), Frame.Binary(bytes))
    }

    class Decoder {
        private var waiting: JSONObject? = null

        fun text(text: String): JSONObject? {
            check(waiting == null) { "Expected bytes, got text." }
            val message = JSONObject(text)
            val path = message.optString("binary")
            if (path.isEmpty()) return message
            keys(path)
            waiting = message
            return null
        }

        fun binary(bytes: ByteArray): JSONObject {
            val message = waiting ?: throw IllegalStateException("Bytes arrived that no message announced.")
            waiting = null
            val path = message.getString("binary")
            message.remove("binary")
            setAt(message, path, bytes)
            return message
        }

        val awaitingBytes: Boolean get() = waiting != null
    }
}
