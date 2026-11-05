package com.tomppi.enderslicer.engine

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
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
        val firmware = com.tomppi.enderslicer.engine.gcode.GcodeRoute.forFlavor("RepRapFirmware")
        assertTrue(!firmware.isFirmwareRetract(requireNotNull(GcodeCommand.parse("G10 P0 S200 R150"))))
        assertTrue(firmware.isFirmwareRetract(requireNotNull(GcodeCommand.parse("G10"))))
        assertTrue(firmware.isFirmwareUnretract(requireNotNull(GcodeCommand.parse("G11"))))
    }

    /**
     * The pause pair this app writes into a Klipper file itself has to publish: the route's
     * own PAUSE - a PAUSE or FILAMENT_CHANGE layer event, and the probe-pause safety step -
     * is textual, so the sanitizer sends it to this fail-closed policy, which used to reject
     * it and made those features unbuildable on a Klipper printer. Both commands are bare:
     * anything with arguments is somebody's macro and stays refused.
     */
    @Test
    fun theKlipperPauseAndResumeTheAppWritesArePublishedText() {
        GcodeCommandPolicy.requirePublishedTextSafe("PAUSE", "Klipper", lineNumber = 1)
        GcodeCommandPolicy.requirePublishedTextSafe("RESUME", "Klipper", lineNumber = 1)
        listOf("PAUSE X1", "RESUME VELOCITY=5", "CLEAR_PAUSE", "PAUSE_BASE").forEach { line ->
            val error = runCatching {
                GcodeCommandPolicy.requirePublishedTextSafe(line, "Klipper", lineNumber = 1)
            }.exceptionOrNull()
            assertTrue("Expected rejection for $line", error is IllegalArgumentException)
        }
        val otherFlavor = runCatching {
            GcodeCommandPolicy.requirePublishedTextSafe("PAUSE", "Marlin", lineNumber = 1)
        }.exceptionOrNull()
        assertTrue("Expected rejection on a Marlin printer", otherFlavor is IllegalArgumentException)
    }

    /**
     * The whole production path, not just the encoder: materialize writes the pause and the
     * published sanitizer reads the file back. Traced failure: every Klipper slice with a
     * pause event died at the text policy after the events had been applied.
     */
    @Test
    fun aKlipperPauseEventSurvivesThePublishedPipeline() {
        val directory = Files.createTempDirectory("klipper-pause-event").toFile()
        try {
            val base = File(directory, "base.gcode").apply {
                writeText(";LAYER:0\nG1 X1 Y1 Z0.2 E1 F1200\n;LAYER:1\nG1 X2 Y2 Z0.4 E1 F1200\n")
            }
            val output = File(directory, "print.gcode")
            GcodeLayerEventProcessor.materialize(
                base,
                output,
                listOf(
                    LayerEvent(id = "pause-1", layerNumber = 1, zMm = 0.4f, type = LayerEventType.PAUSE),
                    LayerEvent(id = "change-1", layerNumber = 1, zMm = 0.4f, type = LayerEventType.FILAMENT_CHANGE),
                ),
                com.tomppi.enderslicer.engine.gcode.GcodeRoute.forFlavor("Klipper"),
            )
            GcodeSanitizer.validateAndRepair(output, printerEnvelope = envelope())
            assertTrue(output.readLines().any { it == "PAUSE" })
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * docs/non-planar.md: lease acquisition fails for released artifacts. release() writes
     * .released while a reader holds a lease, and that leftover print.gcode must never be
     * exported or uploaded after its publication contract has been lost.
     */
    @Test
    fun aReleasedArtifactCannotBeLeasedAgain() {
        val root = Files.createTempDirectory("released-artifact").toFile()
        val staging = Files.createTempDirectory("released-staging").toFile()
        try {
            val gcode = File(staging, "output.gcode").apply { writeText("validated-output") }
            val base = File(staging, "base.gcode").apply { writeText("validated-base") }
            val publisher = SliceArtifactPublisher(root)
            val artifact = publisher.publish("slice-released", gcode, base, envelope())
            SliceArtifactPublisher.acquireLease(artifact.gcodeFile).use {
                publisher.release(artifact.id)
                assertTrue("the released file stays on disk under its lease", artifact.gcodeFile.isFile)
                val error = runCatching {
                    SliceArtifactPublisher.acquireLease(artifact.gcodeFile, "slice-released")
                }.exceptionOrNull()
                assertTrue("a released artifact must not lease", error is IllegalArgumentException)
            }
        } finally {
            staging.deleteRecursively()
            root.deleteRecursively()
        }
    }

    /**
     * The refusal above must not blind the invalidation pass: a directory release() only
     * marked because a lease still holds it is left on disk, and the caller is told so.
     */
    @Test
    fun aReleasedArtifactAReaderStillHoldsIsReportedAsLeftOnDisk() {
        val root = Files.createTempDirectory("stuck-artifact").toFile()
        val staging = Files.createTempDirectory("stuck-staging").toFile()
        try {
            val gcode = File(staging, "output.gcode").apply { writeText("validated-output") }
            val base = File(staging, "base.gcode").apply { writeText("validated-base") }
            val publisher = SliceArtifactPublisher(root)
            val artifact = publisher.publish("slice-stuck", gcode, base, envelope())
            SliceArtifactPublisher.acquireLease(artifact.gcodeFile).use {
                val message = runBlocking { invalidatePublishedSliceArtifacts(root) }
                assertTrue(
                    "the leased directory is still on disk and must be named: $message",
                    message != null && "still on disk" in message,
                )
            }
        } finally {
            staging.deleteRecursively()
            root.deleteRecursively()
        }
    }

    /**
     * The event API's retraction-speed ceiling is the SET_RETRACTION policy bound: 501..1000
     * mm/s used to pass event validation and then fail the whole application at the
     * published-text check with a misleading policy error.
     */
    @Test
    fun retractionEventSpeedsAreCappedToTheSetRetractionPolicyBound() {
        val directory = Files.createTempDirectory("retraction-speed-bound").toFile()
        try {
            val base = File(directory, "base.gcode").apply {
                writeText(";LAYER:0\nG1 X1 Y1 Z0.2 E1 F1200\n;LAYER:1\nG1 X2 Y2 Z0.4 E1 F1200\n")
            }
            fun event(speed: Double) = LayerEvent(
                id = "retract-$speed",
                layerNumber = 1,
                zMm = 0.4f,
                type = LayerEventType.RETRACTION,
                value = 1.2,
                secondaryValue = speed,
            )
            val output = File(directory, "print.gcode")
            GcodeLayerEventProcessor.materialize(
                base,
                output,
                listOf(event(GcodeCommandPolicy.KLIPPER_RETRACT_SPEED_MAX)),
                com.tomppi.enderslicer.engine.gcode.GcodeRoute.forFlavor("Klipper"),
            )
            GcodeSanitizer.validateAndRepair(output, printerEnvelope = envelope())

            val error = runCatching {
                GcodeLayerEventProcessor.materialize(
                    base,
                    output,
                    listOf(event(GcodeCommandPolicy.KLIPPER_RETRACT_SPEED_MAX + 1.0)),
                    com.tomppi.enderslicer.engine.gcode.GcodeRoute.forFlavor("Klipper"),
                )
            }.exceptionOrNull()
            assertTrue("the event API must refuse the speed", error is IllegalArgumentException)
            val message = requireNotNull(error).message.orEmpty()
            assertTrue("the refusal names the retraction speed: $message", "Retraction speed" in message)
            assertTrue("not the published-text policy error", "Unsupported textual" !in message)
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
        gcodeFlavor = "Klipper",
    )
}
