package com.tomppi.enderslicer.printer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A camera URL, from the host's point of view to the app's.
 *
 * This is the part that fails silently: a URL left as the host wrote it is a request the phone
 * makes to itself, which times out or shows the phone's own camera, and nothing says why.
 */
class KlipperWebcamTest {
    private val host = "192.168.3.212"

    @Test
    fun aPathBelongsToTheHostTheAppReached() {
        // How this printer is configured: relative on purpose, so the camera keeps working when
        // the machine's address changes.
        assertEquals("http://192.168.3.212/webcam/snapshot", KlipperWebcam.resolve("/webcam/snapshot", host))
        assertEquals("http://192.168.3.212/webcam/stream", KlipperWebcam.resolve("/webcam/stream", host))
    }

    @Test
    fun anAbsoluteLoopbackAddressBecomesTheHost() {
        // The camera on the host's own machine, which the phone cannot reach by that name. The
        // port is the camera's and stays; only the machine was wrong.
        assertEquals(
            "http://192.168.3.212:8081/snapshot",
            KlipperWebcam.resolve("http://127.0.0.1:8081/snapshot", host),
        )
        assertEquals(
            "http://192.168.3.212:8081/stream",
            KlipperWebcam.resolve("http://localhost:8081/stream", host),
        )
    }

    @Test
    fun aCameraSomewhereElseIsLeftWhereItIs() {
        val url = "http://192.168.3.50:8080/?action=stream"
        assertEquals(url, KlipperWebcam.resolve(url, host))
        val rtsp = "rtsp://camera.local:554/stream"
        assertEquals(rtsp, KlipperWebcam.resolve(rtsp, host))
    }

    @Test
    fun whatMoonrakerSendsBecomesWhatTheAppUses() {
        val item = JSONObject()
            .put("name", "printer")
            .put("enabled", true)
            .put("service", "ustreamer")
            .put("stream_url", "/webcam/stream")
            .put("snapshot_url", "/webcam/snapshot")
        val camera = KlipperWebcam.fromMoonraker(item, host)!!
        assertEquals("printer", camera.name)
        assertEquals("http://192.168.3.212/webcam/snapshot", camera.snapshotUrl)
        assertEquals("http://192.168.3.212/webcam/stream", camera.streamUrl)
    }

    @Test
    fun aCameraWithNoSnapshotOrSwitchedOffIsNotShown() {
        assertNull(
            KlipperWebcam.fromMoonraker(
                JSONObject().put("name", "printer").put("stream_url", "/webcam/stream"),
                host,
            ),
        )
        assertNull(
            KlipperWebcam.fromMoonraker(
                JSONObject()
                    .put("name", "printer")
                    .put("enabled", false)
                    .put("snapshot_url", "/webcam/snapshot"),
                host,
            ),
        )
    }
}
