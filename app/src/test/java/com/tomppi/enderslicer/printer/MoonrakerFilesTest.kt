package com.tomppi.enderslicer.printer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file API of a host on the network, as far as it can be checked without one.
 *
 * The parts pinned here are the ones that fail invisibly: a multipart body whose boundary does
 * not match its header is a 400 with nothing on screen, a name that is not percent-encoded is a
 * 404 for one file and not another, and a modification time read as seconds rather than a float
 * of seconds is a date in 1970 that looks like a real date.
 */
class MoonrakerFilesTest {

    @Test
    fun theEndpointsAreTheOnesMoonrakerServes() {
        assertEquals("/server/files/list?root=gcodes", MoonrakerFiles.listPath())
        assertEquals("/server/files/gcodes/benchy.gcode", MoonrakerFiles.filePath("benchy.gcode"))
        assertEquals(
            "/server/files/metadata?filename=benchy.gcode",
            MoonrakerFiles.metadataPath("benchy.gcode"),
        )
    }

    @Test
    fun aNameWithASpaceSurvivesTheTrip() {
        // A space in a URL is not a space: unencoded it is the end of the path, and the host
        // answers 404 for a file that is there.
        assertEquals("my%20part.gcode", MoonrakerFiles.encodePath("my part.gcode"))
        assertEquals("/server/files/gcodes/my%20part.gcode", MoonrakerFiles.filePath("my part.gcode"))
    }

    @Test
    fun theUploadBodyIsTheMultipartMoonrakerReads() {
        val boundary = "----TrioSlicerTEST"
        val body = String(
            MoonrakerFiles.uploadBody(boundary, "benchy.gcode", "G28\nG1 X10\n".toByteArray()),
            Charsets.UTF_8,
        )
        // The boundary appears in the header the transport sends and before every part.
        assertTrue(body.startsWith("--" + boundary + "\r\n"))
        assertEquals(3, body.split("--$boundary").size - 1)
        // The file arrives as a file part named "file", with the name the host should keep.
        assertTrue(body.contains("name=\"file\"; filename=\"benchy.gcode\""))
        // The root is a field of its own, and it is where a printable file has to go.
        assertTrue(body.contains("name=\"root\"\r\n\r\n" + MoonrakerFiles.ROOT))
        // The contents survive, and the body ends the way a multipart body has to.
        assertTrue(body.contains("G28\nG1 X10\n"))
        assertTrue(body.endsWith("--" + boundary + "--\r\n"))
    }

    @Test
    fun aQuoteInANameCannotBreakTheHeader() {
        val body = String(
            MoonrakerFiles.uploadBody("b", "od\"d.gcode", ByteArray(0)),
            Charsets.UTF_8,
        )
        assertTrue("the quote is removed, not emitted", body.contains("filename=\"odd.gcode\""))
    }

    @Test
    fun aListedFileBecomesTheAppsOwnType() {
        val item = JSONObject()
            .put("path", "parts/bracket.gcode")
            .put("size", 2048)
            .put("modified", 1758700000.5)
        val file = MoonrakerFiles.fromHost(item)
        // The host names a file by its path under the root; klippy and the delete path want the
        // name on its own.
        assertEquals("bracket.gcode", file.name)
        assertEquals(2048L, file.sizeBytes)
        assertEquals(1758700000500L, file.modifiedAtMillis)
    }
}
