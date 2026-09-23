package com.tomppi.enderslicer.ui

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeExportNameTest {
    @Test
    fun generatedNameKeepsGcodeAsTheFinalExtension() {
        val name = GcodeExportName.suggest(LocalDateTime.of(2026, 7, 19, 20, 30, 15, 123_000_000))

        assertEquals("print_20260719_203015_123.gcode", name)
        assertTrue(name.endsWith(".gcode"))
    }
}
