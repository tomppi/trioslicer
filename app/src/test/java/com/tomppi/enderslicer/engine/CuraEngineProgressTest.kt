package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CuraEngine reports progress over its command socket, which is what feeds
 * Cura's own progress bar; the '-p' flag is the terminal equivalent the header
 * documents, and the Android build implements it (see
 * scripts/build-curaengine-android.sh). Its line is what the runner parses.
 */
class CuraEngineProgressTest {

    @Test
    fun theEnginesOwnLineIsRead() {
        assertEquals(42, curaProgressFrom("Slice progress: 42%"))
        assertEquals(0, curaProgressFrom("Slice progress: 0%"))
        assertEquals(100, curaProgressFrom("Slice progress: 100%"))
    }

    /** The engine prefixes its lines with a timestamp and a level. */
    @Test
    fun aDecoratedLineIsReadToo() {
        assertEquals(
            7,
            curaProgressFrom("[2026-09-22 20:31:44.123] [info] Slice progress: 7%"),
        )
    }

    /**
     * The engine's stage lines start with "Progress:" as well, so the marker has
     * to be the whole phrase or the bar would jump on every stage boundary.
     */
    @Test
    fun theStageLinesAreNotProgress() {
        assertNull(curaProgressFrom("Progress: inset+skin accomplished in 12.345s"))
        assertNull(curaProgressFrom("Starting support..."))
        assertNull(curaProgressFrom("Total print time: 754"))
    }

    @Test
    fun aBrokenLineIsIgnored() {
        assertNull(curaProgressFrom("Slice progress: abc%"))
        assertNull(curaProgressFrom("Slice progress: %"))
    }

    /** Nothing the engine prints may push the UI past a full bar. */
    @Test
    fun anOutOfRangeValueIsClamped() {
        assertEquals(100, curaProgressFrom("Slice progress: 140%"))
    }
}
