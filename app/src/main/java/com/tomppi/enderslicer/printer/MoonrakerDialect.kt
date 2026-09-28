package com.tomppi.enderslicer.printer

import org.json.JSONArray
import org.json.JSONObject

/**
 * klippy's API as Moonraker serves it.
 *
 * The app's client was written against klippy's own socket: method names without a namespace,
 * and notifications whose payload is an object. Moonraker serves the same objects over the same
 * JSON-RPC, with three differences that matter here, each checked against the Moonraker running
 * beside this printer and against Mainsail, which is a Moonraker client:
 *
 *  - the methods are namespaced: "info" is "printer.info", "objects/query" is
 *    "printer.objects.query", and so on;
 *  - a status update arrives as "notify_status_update" with [status, eventtime], where klippy
 *    sends {eventtime, status} as one object;
 *  - console output arrives as "notify_gcode_response" with the line at params[0], not through
 *    the response template the app asks klippy for.
 *
 * The translation is one way and total: everything the client sends is expressed as Moonraker
 * serves it, and everything Moonraker pushes is expressed as the client already reads it. That is
 * why the transport can hide in the byte pipe and not one screen has to know.
 */
internal object MoonrakerDialect {

    /** Answers the client gets without a round trip, because only its own socket has them. */
    private val answeredLocally = setOf("gcode/subscribe_output")

    /**
     * The method Moonraker knows, for the one the client sends.
     *
     * Every method the client calls is here; a test walks that list, so a new call cannot be
     * added without a mapping and a silent "No registered callback for path" cannot happen.
     */
    fun methodFor(local: String): String? = when (local) {
        "info" -> "printer.info"
        "objects/query" -> "printer.objects.query"
        "objects/subscribe" -> "printer.objects.subscribe"
        "objects/list" -> "printer.objects.list"
        "gcode/help" -> "printer.gcode.help"
        "gcode/script" -> "printer.gcode.script"
        "emergency_stop" -> "printer.emergency_stop"
        // Moonraker serves the restart as a route of its own, but a script is the same
        // thing to klippy and needs no second protocol under the first.
        "gcode/firmware_restart" -> "printer.gcode.script"
        else -> null
    }

    /** True when the client is asking for something only its own socket provides. */
    fun isAnsweredLocally(local: String): Boolean = local in answeredLocally

    /**
     * A request the client wrote, as Moonraker wants it - or null when it needs no round trip.
     *
     * klippy's framing has no protocol version and Moonraker's does, so the envelope is rebuilt
     * rather than edited: a request that works against one must not be sent half-translated to
     * the other.
     */
    fun request(local: JSONObject): JSONObject? {
        val method = local.optString("method")
        if (isAnsweredLocally(method)) return null
        val mapped = methodFor(method) ?: return null
        val params = local.optJSONObject("params") ?: JSONObject()
        val rewritten = when (method) {
            "gcode/firmware_restart" -> JSONObject().put("script", "FIRMWARE_RESTART")
            else -> params
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", local.optInt("id"))
            .put("method", mapped)
            .put("params", rewritten)
    }

    /**
     * A Moonraker notification in the shape the client reads - or null if it is none of its
     * business.
     *
     * Returning a list because one Moonraker notification can carry several console lines, and
     * the client reads one line per message. Dropping the ones the app has no use for (its own
     * announcements, its history) is deliberate: the client's parser judges what it gets, and
     * feeding it everything Moonraker says would put Moonraker's business on a printer screen.
     */
    fun notifications(message: JSONObject): List<JSONObject> {
        val method = message.optString("method")
        val params = message.opt("params")
        return when (method) {
            "notify_status_update" -> {
                val array = params as? JSONArray ?: return emptyList()
                val status = array.optJSONObject(0) ?: return emptyList()
                val eventtime = array.optDouble(1, 0.0)
                listOf(
                    JSONObject().put(
                        "params",
                        JSONObject().put("eventtime", eventtime).put("status", status),
                    ),
                )
            }
            "notify_gcode_response" -> {
                val array = params as? JSONArray ?: return emptyList()
                (0 until array.length()).mapNotNull { index ->
                    val line = array.optString(index, "")
                    if (line.isEmpty()) {
                        null
                    } else {
                        JSONObject()
                            .put("method", KlipperClient.GCODE_RESPONSE_METHOD)
                            .put("params", JSONObject().put("response", line))
                    }
                }
            }
            "notify_klippy_ready",
            "notify_klippy_shutdown",
            "notify_klippy_disconnected",
            -> listOf(JSONObject().put("method", method))
            else -> emptyList()
        }
    }
}
