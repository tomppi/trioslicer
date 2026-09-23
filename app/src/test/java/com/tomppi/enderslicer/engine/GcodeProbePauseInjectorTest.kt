package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeProbePauseInjectorTest {
    @Test
    fun injectsPauseAfterLastG29InStartGcode() {
        val file = tempFile(
            """
            ;FLAVOR:Marlin
            G28
            G29
            G1 Z2.0 F3000
            ;LAYER:0
            G1 X1 Y1 E1
            """.trimIndent(),
        )
        assertTrue(GcodeProbePauseInjector.inject(file))
        val result = file.readText()
        val probe = result.indexOf("G29")
        val pause = result.indexOf(";ENDERSLICER_PROBE_PAUSE")
        val message = result.indexOf("M117 Tilt probe up, resume")
        val stop = result.indexOf("M0")
        val layer = result.indexOf(";LAYER:0")
        assertTrue(probe >= 0)
        assertTrue(pause > probe && message > pause && stop > message && layer > stop)
    }

    @Test
    fun pausesAfterTheLastG29WhenSeveralArePresent() {
        val file = tempFile(
            """
            G28
            G29 L0
            G29 A
            G1 Z2.0 F3000
            ;LAYER:0
            G1 X1 E1
            """.trimIndent(),
        )
        assertTrue(GcodeProbePauseInjector.inject(file))
        val result = file.readText()
        val lastG29 = result.lastIndexOf("G29")
        val pause = result.indexOf(";ENDERSLICER_PROBE_PAUSE")
        assertTrue(lastG29 >= 0 && pause > lastG29)
    }

    @Test
    fun doesNothingWithoutAProbeCommand() {
        val file = tempFile(
            """
            G28
            G1 Z2.0 F3000
            ;LAYER:0
            G1 X1 E1
            """.trimIndent(),
        )
        assertFalse(GcodeProbePauseInjector.inject(file))
        assertFalse(file.readText().contains("M0"))
    }

    @Test
    fun ignoresG29AfterPrintableMotionBegins() {
        val file = tempFile(
            """
            G28
            ;LAYER:0
            G1 X1 E1
            G29
            """.trimIndent(),
        )
        assertFalse(GcodeProbePauseInjector.inject(file))
    }

    @Test
    fun isIdempotentWhenMarkerAlreadyPresent() {
        val file = tempFile(
            """
            G28
            G29
            ;ENDERSLICER_PROBE_PAUSE
            M117 Tilt probe up, resume
            M0
            ;LAYER:0
            G1 X1 E1
            """.trimIndent(),
        )
        assertFalse(GcodeProbePauseInjector.inject(file))
        assertEquals(1, file.readText().split(";ENDERSLICER_PROBE_PAUSE").size - 1)
    }

    private fun tempFile(content: String): java.io.File =
        kotlin.io.path.createTempFile("enderslicer-probe-pause", ".gcode").toFile().apply {
            writeText(content)
        }
}
