package com.tomppi.enderslicer.printer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framing and the translation a remote Klipper host needs.
 *
 * Both are places where being slightly wrong is silent: a frame with the wrong length encoding is
 * simply never understood, and a method name that is not namespaced is answered with "No
 * registered callback for path" while the screen shows nothing. So each is pinned here, including
 * that every method the client calls has a mapping.
 */
class MoonrakerDialectTest {

    @Test
    fun everyMethodTheClientCallsIsMapped() {
        // The list is the client's own call sites. A new one without a mapping would reach
        // Moonraker under klippy's name and be refused, so this fails instead.
        val called = listOf(
            "info", "objects/query", "objects/subscribe", "objects/list",
            "gcode/help", "gcode/script", "gcode/firmware_restart", "emergency_stop",
        )
        for (method in called) {
            assertNotNull("$method has no Moonraker name", MoonrakerDialect.methodFor(method))
        }
    }

    @Test
    fun theNamesAreNamespacedTheWayMoonrakerServesThem() {
        assertEquals("printer.info", MoonrakerDialect.methodFor("info"))
        assertEquals("printer.objects.subscribe", MoonrakerDialect.methodFor("objects/subscribe"))
        assertEquals("printer.gcode.script", MoonrakerDialect.methodFor("gcode/script"))
    }

    @Test
    fun anUnknownMethodIsRefusedRatherThanGuessedAt() {
        assertNull(MoonrakerDialect.methodFor("objects/nonsense"))
    }

    @Test
    fun theConsoleSubscriptionIsAnsweredWithoutARoundTrip() {
        // klippy's own socket has this method and Moonraker does not: the console arrives as
        // notifications instead, so the call is answered locally and the output translated.
        assertTrue(MoonrakerDialect.isAnsweredLocally("gcode/subscribe_output"))
        assertNull(MoonrakerDialect.request(JSONObject().put("id", 3).put("method", "gcode/subscribe_output")))
    }

    @Test
    fun aRequestIsRebuiltForMoonraker() {
        val local = JSONObject()
            .put("id", 7)
            .put("method", "objects/subscribe")
            .put("params", JSONObject().put("objects", JSONObject().put("toolhead", JSONObject.NULL)))
        val sent = MoonrakerDialect.request(local)!!
        assertEquals("2.0", sent.getString("jsonrpc"))
        assertEquals(7, sent.getInt("id"))
        assertEquals("printer.objects.subscribe", sent.getString("method"))
        assertTrue(sent.getJSONObject("params").has("objects"))
    }

    @Test
    fun theFirmwareRestartIsAScriptBecauseThatIsWhatKlippyAnswers() {
        val restart = MoonrakerDialect.request(
            JSONObject().put("id", 9).put("method", "gcode/firmware_restart").put("params", JSONObject()),
        )!!
        assertEquals("printer.gcode.script", restart.getString("method"))
        assertEquals("FIRMWARE_RESTART", restart.getJSONObject("params").getString("script"))
    }

    @Test
    fun aStatusUpdateIsTurnedBackIntoTheShapeTheClientReads() {
        // Moonraker: [status, eventtime]. klippy, and this app: {eventtime, status}. Reading the
        // wrong one discards every update without an error anywhere.
        val moonraker = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "notify_status_update")
            .put(
                "params",
                JSONArray().put(JSONObject().put("toolhead", JSONObject().put("max_accel", 5000.0))).put(1234.5),
            )
        val translated = MoonrakerDialect.notifications(moonraker).single()
        val params = translated.getJSONObject("params")
        assertEquals(1234.5, params.getDouble("eventtime"), 1e-9)
        assertEquals(5000.0, params.getJSONObject("status").getJSONObject("toolhead").getDouble("max_accel"), 1e-9)
    }

    @Test
    fun consoleOutputBecomesTheNotificationTheAppAsksKlippyFor() {
        val moonraker = JSONObject()
            .put("method", "notify_gcode_response")
            .put("params", JSONArray().put("// hello").put("!! an error"))
        val lines = MoonrakerDialect.notifications(moonraker)
        assertEquals(2, lines.size)
        assertEquals(KlipperClient.GCODE_RESPONSE_METHOD, lines[0].getString("method"))
        assertEquals("// hello", lines[0].getJSONObject("params").getString("response"))
        assertEquals(KlipperProtocol.gcodeResponse(lines[0]), "// hello")
        assertEquals("!! an error", KlipperProtocol.gcodeResponse(lines[1]))
    }

    @Test
    fun theLifecycleNotificationsKeepTheirNames() {
        val ready = MoonrakerDialect.notifications(JSONObject().put("method", "notify_klippy_ready")).single()
        assertEquals("notify_klippy_ready", KlipperProtocol.lifecycle(ready))
        val shutdown = MoonrakerDialect.notifications(
            JSONObject().put("method", "notify_klippy_shutdown"),
        ).single()
        assertEquals("notify_klippy_shutdown", KlipperProtocol.lifecycle(shutdown))
    }

    @Test
    fun moonrakersOwnBusinessIsNotAClientsBusiness() {
        assertTrue(MoonrakerDialect.notifications(JSONObject().put("method", "notify_job_queue_changed")).isEmpty())
        assertTrue(MoonrakerDialect.notifications(JSONObject().put("method", "notify_history_changed")).isEmpty())
    }
}
