package com.tomppi.enderslicer.printer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload's multipart, split so the file part can be streamed.
 *
 * upload() sends the head and the tail with the file written between them, straight from disk,
 * because reading a large slice into the heap and copying it twice more is how an upload ran out
 * of memory. The bytes on the wire have to be exactly what uploadBody() builds.
 */
class UploadStreamingTest {

    @Test
    fun headPlusContentPlusTailIsTheWholeBody() {
        val content = ByteArray(4096) { (it % 251).toByte() }
        val boundary = "----TrioSlicerTest"
        val whole = MoonrakerFiles.uploadBody(boundary, "benchy.gcode", content, "gcodes")
        val split = MoonrakerFiles.uploadHead(boundary, "benchy.gcode", "gcodes") +
            content +
            MoonrakerFiles.uploadTail(boundary)
        assertArrayEquals("the streamed form must be byte for byte the same", whole, split)
    }

    @Test
    fun theChunkIsLargeEnoughToBeWorthStreaming() {
        assertTrue(
            "a small chunk would make the streaming pointless",
            MoonrakerFiles.UPLOAD_CHUNK_BYTES >= 64 * 1024,
        )
    }
}
