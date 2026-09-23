package com.tomppi.enderslicer.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class M105SafetyRegressionTest {
    @Test
    fun documentedReadOnlyReportFormsAreAllowedByEverySharedSafetyConsumer() {
        listOf(
            "M105",
            "M105 R",
            "M105 T0",
            "M105 R T0",
            "M114",
            "M114 D",
            "M114 E R",
            "M114 D E R",
            "M115",
            "M119",
            "M31",
            "M27",
            "M27 C",
            "M503",
            "M503 S",
            "M503 S0",
            "M503 S1",
        ).forEach(::requireEverySharedConsumer)
    }

    @Test
    fun stateChangingUnknownAndMalformedReportArgumentsRemainRejected() {
        listOf(
            "M105 S1",
            "M105 T33",
            "M105 T0.5",
            "M105 R1",
            "M114 X",
            "M114 D1",
            "M114 D D",
            "M115 X1",
            "M119 S1",
            "M31 S1",
            "M27 S0",
            "M27 C1",
            "M503 C",
            "M503 S2",
        ).forEach(::requireRejectedByEverySharedConsumer)
    }

    @Test
    fun sanitizerPublishesReadOnlyReportsWithoutWeakeningDangerousCommandRejection() {
        val directory = Files.createTempDirectory("readonly-report-policy").toFile()
        try {
            val safe = File(directory, "safe.gcode").apply {
                writeText(
                    "G90\nM105 R T0\nM114 D E R\nM115\nM119\nM31\nM27 C\nM503 S1\n" +
                        ";LAYER:0\nG1 X1 Y1 Z0.2 E1 F1200\n",
                )
            }
            GcodeSanitizer.validateAndRepair(safe)

            val unsafe = File(directory, "unsafe.gcode").apply {
                writeText("G90\nM503 C\n;LAYER:0\nG1 X1 Y1 Z0.2 E1 F1200\n")
            }
            assertTrue(runCatching { GcodeSanitizer.validateAndRepair(unsafe) }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun requireEverySharedConsumer(line: String) {
        val command = requireNotNull(GcodeCommand.parse(line))
        GcodeCommandPolicy.requireNonPlanarSupported(command, inPrintableLayers = false)
        GcodeCommandPolicy.requireNonPlanarSupported(command, inPrintableLayers = true)
        GcodeCommandPolicy.requirePublishedSafe(command, currentLayer = 0, lineNumber = 1)
        GcodeCommandPolicy.requirePreviewSafe(command, spatialMovesSeen = 10)
    }

    private fun requireRejectedByEverySharedConsumer(line: String) {
        val command = requireNotNull(GcodeCommand.parse(line))
        assertTrue("Non-planar slicing should reject $line", runCatching {
            GcodeCommandPolicy.requireNonPlanarSupported(command, inPrintableLayers = false)
        }.isFailure)
        assertTrue("Published G-code should reject $line", runCatching {
            GcodeCommandPolicy.requirePublishedSafe(command, currentLayer = null, lineNumber = 1)
        }.isFailure)
        assertTrue("Nozzle Path should reject $line", runCatching {
            GcodeCommandPolicy.requirePreviewSafe(command, spatialMovesSeen = 0)
        }.isFailure)
    }
}
