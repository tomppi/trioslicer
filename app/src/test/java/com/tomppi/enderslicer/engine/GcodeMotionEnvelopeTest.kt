package com.tomppi.enderslicer.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeMotionEnvelopeTest {
    @Test
    fun rejectsPureTravelOutsideMachineEnvelopeBeforePublication() {
        val directory = Files.createTempDirectory("gcode-motion-envelope").toFile()
        try {
            val gcode = File(directory, "output.gcode").apply {
                writeText(
                    """
                    ;FLAVOR:Marlin
                    G90
                    G0 X10 Y10 Z1 F6000
                    G0 X500 Y10 Z1 F6000
                    """.trimIndent(),
                )
            }
            val original = gcode.readBytes()

            val failure = runCatching {
                GcodeSanitizer.validateAndRepair(gcode, printerEnvelope = envelope())
            }.exceptionOrNull()

            assertTrue(requireNotNull(failure).message.orEmpty().contains("Motion end"))
            assertArrayEquals(original, gcode.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun envelope(): PrinterEnvelope = PrinterEnvelope(
        widthMm = 220.0,
        depthMm = 220.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        gcodeFlavor = "marlin",
    )
}
