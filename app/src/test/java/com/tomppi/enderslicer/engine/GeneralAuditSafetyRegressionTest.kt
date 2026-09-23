package com.tomppi.enderslicer.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralAuditSafetyRegressionTest {
    @Test
    fun finalPolicyRejectsPersistentAndMachineControlMCodes() {
        listOf("M92 X1000", "M206 X10", "M211 S0", "M428", "M500", "M502", "M42 P1 S255").forEach { line ->
            val command = requireNotNull(GcodeCommand.parse(line))
            val error = runCatching { GcodeCommandPolicy.requirePublishedSafe(command, null, 1) }.exceptionOrNull()
            assertTrue("Expected rejection for $line", error != null)
        }
    }

    @Test
    fun finalPolicyAllowsModeledCuraAndCalibrationCommands() {
        listOf(
            "M82", "M104 S200", "M109 R200", "M140 S60", "M190 S60", "M106 S128", "M107",
            "M204 P500 T1000", "M205 X8 Y8 J0.02", "M207 S1 F1500", "M220 S50", "M221 S100",
            "M420 S1 Z10", "M572 D0 S0.05", "M900 K0.04", "M84",
        ).forEach { line ->
            GcodeCommandPolicy.requirePublishedSafe(requireNotNull(GcodeCommand.parse(line)), null, 1)
        }
    }

    @Test
    fun sanitizerRejectsUnparsedMacrosButAllowsStrictKlipperCalibrationCommands() {
        val directory = Files.createTempDirectory("general-command-policy").toFile()
        try {
            val unsafe = File(directory, "unsafe.gcode").apply {
                writeText(";LAYER:0\nRUN_SHELL_COMMAND CMD=boom\nG1 X1 Y1 Z0.2 E1 F1200\n")
            }
            assertTrue(runCatching { GcodeSanitizer.validateAndRepair(unsafe) }.isFailure)

            val safe = File(directory, "safe.gcode").apply {
                writeText(
                    ";LAYER:0\nSET_PRESSURE_ADVANCE ADVANCE=0.04\n" +
                        "SET_RETRACTION RETRACT_LENGTH=1 RETRACT_SPEED=25\nG1 X1 Y1 Z0.2 E1 F1200\n",
                )
            }
            GcodeSanitizer.validateAndRepair(
                safe,
                printerEnvelope = PrinterEnvelope(220.0, 220.0, 250.0, "rectangular", false, "Klipper"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun layerPreviewAndSanitizerHonorM220() {
        val directory = Files.createTempDirectory("general-m220").toFile()
        try {
            val file = File(directory, "speed.gcode").apply {
                writeText(
                    ";TIME_ELAPSED:3\nG90\nM83\n;LAYER:0\n" +
                        "G1 X10 E1 F600\nM220 S50\nG1 X20 E1\nM220 S200\nG1 X30 E1\n",
                )
            }
            val preview = GcodeLayerPreviewParser.parse(file)
            assertEquals(10f, preview.layers.single().segments[4], 0.001f)
            assertEquals(5f, preview.layers.single().segments[10], 0.001f)
            assertEquals(20f, preview.layers.single().segments[16], 0.001f)
            val summary = GcodeSanitizer.validateAndRepair(file)
            assertEquals(4, summary.estimatedSeconds)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun reprapToolSettingG10IsNotRetraction() {
        val firmware = CalibrationFirmwareEncoder.fromFlavor("RepRapFirmware")
        assertTrue(!firmware.isFirmwareRetract(requireNotNull(GcodeCommand.parse("G10 P0 S200 R150"))))
        assertTrue(firmware.isFirmwareRetract(requireNotNull(GcodeCommand.parse("G10"))))
        assertTrue(firmware.isFirmwareUnretract(requireNotNull(GcodeCommand.parse("G11"))))
    }
}
