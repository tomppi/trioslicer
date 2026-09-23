package com.tomppi.enderslicer.storage

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import paths all cap what they read; the cap has to hold while reading, so
 * an oversized pick is refused rather than loaded and then rejected.
 */
class PickedDocumentReaderTest {

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    @Test
    fun readsADocumentUnderTheLimit() {
        val text = "[print]\nlayer_height = 0.2\n"
        assertEquals(text, readPickedText(stream(text), 1024L, "PrusaSlicer config"))
    }

    @Test
    fun readsExactlyTheLimit() {
        val text = "abcd"
        assertEquals(text, readPickedText(stream(text), 4L, "OrcaSlicer profile"))
    }

    @Test
    fun refusesADocumentPastTheLimit() {
        val failure = runCatching {
            readPickedText(stream("abcdefgh"), 4L, "configuration snapshot")
        }.exceptionOrNull()

        assertTrue("expected a refusal, was " + failure, failure is IllegalArgumentException)
        assertEquals(
            "The selected configuration snapshot exceeds the 0 MiB safety limit",
            failure?.message,
        )
    }

    @Test
    fun anExportedDocumentIsTruncatedRatherThanRefused() {
        val (text, truncated) = readPickedTextTruncated(stream("abcdefgh"), 4L)

        assertEquals("abcd", text)
        assertTrue("the reader has to say it stopped early", truncated)
    }

    @Test
    fun anExportedDocumentUnderTheLimitIsWhole() {
        val (text, truncated) = readPickedTextTruncated(stream("diagnostic\n"), 1024L)

        assertEquals("diagnostic\n", text)
        assertFalse(truncated)
    }

    @Test
    fun readsBytesWithoutADecodingRoundTrip() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, readPickedBytes(ByteArrayInputStream(bytes), 4L, "archive"))
    }
}
