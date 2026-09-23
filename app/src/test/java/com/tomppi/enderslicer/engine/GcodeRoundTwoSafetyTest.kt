package com.tomppi.enderslicer.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeRoundTwoSafetyTest {
    @Test
    fun canonicalizesAliasesAndRejectsTransportFramingAtSafetyBoundaries() {
        val linear = requireNotNull(GcodeCommand.parse("N42 g001X10.5 Y2*77 ; framed"))
        assertEquals("G1", linear.opcode)
        assertTrue(linear.hasLineNumber)
        assertTrue(linear.hasChecksum)
        assertEquals(10.5, requireNotNull(linear.value('X')), 0.0)

        assertEquals("G2", requireNotNull(GcodeCommand.parse("G02 X1 Y1 I.5")).opcode)
        assertEquals("G92", requireNotNull(GcodeCommand.parse("G092 E0")).opcode)
        assertEquals("M83", requireNotNull(GcodeCommand.parse("m083")).opcode)
        assertEquals(null, GcodeCommand.parse("G1.5 X1"))

        listOf(
            { GcodeCommandPolicy.requireNonPlanarSupported(linear, inPrintableLayers = true) },
            { GcodeCommandPolicy.requirePublishedSafe(linear, currentLayer = 0, lineNumber = 42) },
            { GcodeCommandPolicy.requirePreviewSafe(linear, spatialMovesSeen = 0) },
        ).forEach { consumer ->
            val failure = runCatching(consumer).exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(requireNotNull(failure).message.orEmpty().contains("framing"))
        }
    }

    @Test
    fun customLayerEventsUseAStateNeutralAllowlist() {
        listOf("G01 X300 E50", "G5 X300 I1 J1", "G20", "M082", "M083", "N1 M117 hi*2").forEach { line ->
            val failure = runCatching {
                GcodeLayerEventProcessor.commands(
                    LayerEvent(
                        id = "custom",
                        layerNumber = 1,
                        zMm = 0.2f,
                        type = LayerEventType.CUSTOM_GCODE,
                        text = line,
                    ),
                )
            }.exceptionOrNull()
            assertTrue("Expected rejection for $line", failure is IllegalArgumentException)
        }

        val safe = GcodeLayerEventProcessor.commands(
            LayerEvent(
                id = "message",
                layerNumber = 1,
                zMm = 0.2f,
                type = LayerEventType.CUSTOM_GCODE,
                text = "M117 layer reached",
            ),
        )
        assertEquals(listOf("M117 layer reached"), safe)
    }

    @Test
    fun nozzlePathTracksM220AndRejectsHiddenMotionAliases() {
        val directory = Files.createTempDirectory("path-m220").toFile()
        try {
            val gcode = File(directory, "print.gcode").apply {
                writeText(
                    """
                    G90
                    M83
                    G1 X10 F1200
                    M220 S50
                    G1 X20 E1
                    M220 S200
                    G1 X30 E1
                    """.trimIndent(),
                )
            }
            val path = GcodeNozzlePathParser.parse(gcode)
            fun speed(index: Int): Float = path.moves[index * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.SPEED]
            assertEquals(20f, speed(0), 0.0001f)
            assertEquals(10f, speed(1), 0.0001f)
            assertEquals(40f, speed(2), 0.0001f)

            gcode.writeText("G90\nG01 X10 F1200\nG05 X20 I1 J1\n")
            val failure = runCatching { GcodeNozzlePathParser.parse(gcode) }.exceptionOrNull()
            assertTrue(requireNotNull(failure).message.orEmpty().contains("G5"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun acceptsThePrusaM74LayerMarkerInGrams() {
        // PrusaSlicer sends M74 W[extruded_weight_total], i.e. the extruded
        // filament WEIGHT in grams, so an ordinary 1 kg print reaches W1000 and
        // the old 0..100 "percent" bound rejected the slice after the engine had
        // already spent minutes slicing it.
        listOf("M74 W0", "M74 W25.4", "M74 W100", "M74 W1000", "M74 W12500.5").forEach { line ->
            GcodeCommandPolicy.requirePublishedSafe(
                requireNotNull(GcodeCommand.parse(line)),
                currentLayer = 3,
                lineNumber = 7,
            )
        }

        val failure = runCatching {
            GcodeCommandPolicy.requirePublishedSafe(
                requireNotNull(GcodeCommand.parse("M74 W100001")),
                currentLayer = 3,
                lineNumber = 7,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun incompleteArtifactsCannotAcquireANoopLease() {
        val directory = Files.createTempDirectory("incomplete-artifact").toFile()
        try {
            val file = File(directory, SliceArtifactPublisher.GCODE_FILE_NAME).apply { writeText("G1 X1") }
            assertFalse(SliceArtifactPublisher.isCompleteGcode(file))
            val failure = runCatching { SliceArtifactPublisher.acquireLease(file) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        } finally {
            directory.deleteRecursively()
        }
    }
}
