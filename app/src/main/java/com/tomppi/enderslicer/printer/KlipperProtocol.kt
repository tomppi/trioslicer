package com.tomppi.enderslicer.printer

import org.json.JSONObject

/**
 * The wire format of klippy's API, kept apart from the socket so that it can be
 * tested without one.
 *
 * Every fact here was checked against a real klippy (the same version, on a machine
 * beside the phone) and against Moonraker's own client, because each of them cost a
 * build-install cycle to learn the hard way:
 *
 *  - messages are terminated with ETX in both directions, not with newlines;
 *  - a request that is never terminated is buffered as incomplete and simply never
 *    answered;
 *  - notify_status_update carries its payload as an object, {eventtime, status}, and
 *    reading it as the [status, eventtime] array an older Klipper sent discards every
 *    update without an error anywhere.
 */
internal object KlipperProtocol {
    /** The terminator, in both directions. */
    const val ETX: Byte = 0x03

    /** A request, framed and ready to write. */
    fun encode(id: Int, method: String, params: JSONObject? = null): ByteArray {
        val request = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", params ?: JSONObject())
        return request.toString().toByteArray() + byteArrayOf(ETX)
    }

    /**
     * Complete messages in [buffer], and whatever is left of a partial one.
     *
     * Returning the remainder is the whole point: a socket read lands wherever it
     * lands, so a message can arrive in two pieces and two can arrive together. A
     * malformed message is dropped rather than thrown, because the next one is still
     * a valid message and one bad frame should not end the stream.
     */
    fun decode(buffer: ByteArray): Pair<List<JSONObject>, ByteArray> {
        val messages = mutableListOf<JSONObject>()
        var rest = buffer
        while (true) {
            val end = rest.indexOf(ETX)
            if (end < 0) break
            val part = rest.copyOfRange(0, end)
            rest = rest.copyOfRange(end + 1, rest.size)
            runCatching { JSONObject(String(part)) }.getOrNull()?.let { messages += it }
        }
        return messages to rest
    }

    /** The status a notification carries, or null if it is not one that carries any. */
    fun statusUpdate(message: JSONObject): JSONObject? {
        if (message.optString("method") != "notify_status_update") return null
        return message.optJSONObject("params")?.optJSONObject("status")
    }

    /** klippy announcing a change in its own state, which carries no status. */
    fun lifecycle(message: JSONObject): String? {
        val method = message.optString("method")
        return if (method.startsWith("notify_klippy_")) method else null
    }
}
