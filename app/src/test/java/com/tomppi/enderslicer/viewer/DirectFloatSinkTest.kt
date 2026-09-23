package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DirectFloatSink is the off-heap geometry builder for the nozzle-path
 * renderer: it must grow, round-trip and convert without any heap arrays.
 */
class DirectFloatSinkTest {

    @Test
    fun growsRoundTripsAndConverts() {
        val sink = DirectFloatSink(initialCapacity = 8)
        assertTrue(sink.isEmpty())
        for (i in 0 until 5000) sink += i.toFloat() + 0.5f
        assertEquals(5000, sink.size)
        assertFalse(sink.isEmpty())

        val buffer = sink.toFloatBuffer()
        // Zero-copy conversion: limit is the data length; the backing storage
        // may be a larger power-of-two than the sink capacity.
        assertEquals(5000, buffer.limit())
        for (i in 0 until 5000) {
            assertEquals(i.toFloat() + 0.5f, buffer[i], 0f)
        }

        val array = sink.toFloatArray()
        assertEquals(5000, array.size)
        for (i in 0 until 5000) {
            assertEquals(i.toFloat() + 0.5f, array[i], 0f)
        }
    }

    @Test
    fun emptySinkRoundTrips() {
        val sink = DirectFloatSink()
        assertTrue(sink.isEmpty())
        assertEquals(0, sink.toFloatArray().size)
        assertEquals(0, sink.toFloatBuffer().limit())
    }
}
