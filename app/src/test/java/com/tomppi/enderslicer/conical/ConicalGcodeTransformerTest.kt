package com.tomppi.enderslicer.conical

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.PrinterEnvelope
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConicalGcodeTransformerTest {
    @Test
    fun warpedExtrusionMoveRestoresOriginalCoordinates() {
        val directory = createTempDirectory("conical-test-").toFile()
        val gcode = File(directory, "output.gcode")
        val settings = ConicalSettings(
            enabled = true,
            coneAngleDegrees = 16.0,
            coneType = ConeType.OUTWARD,
            firstLayerHeightMm = 0.2,
        )
        // Warp the intended endpoints with the forward transform, then emit the
        // "sliced" G-code that CuraEngine would produce from the warped STL.
        val start = ConicalTransform.forward(0.0, 0.0, 0.0, 0.0, 0.0, settings)
        val end = ConicalTransform.forward(10.0, 0.0, 0.0, 0.0, 0.0, settings)
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            ;Generated with Cura
            G90
            M82
            G92 E0
            ;LAYER:0
            G1 X${start[0]} Y${start[1]} Z${start[2]} E0 F1200
            G1 X${end[0]} Y${end[1]} Z${end[2]} E1 F1200
            ;End of Gcode
            """.trimIndent(),
        )
        writePrepared(directory, settings)

        val result = ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        assertNotNull(result)
        val diagnostics = requireNotNull(result)
        assertTrue(diagnostics.emittedMoves > diagnostics.sourceMoves)
        assertTrue(diagnostics.minimumZmm >= 0.0)

        val moves = gcode.readLines().mapNotNull(GcodeCommand::parse)
        val finalSpatial = moves
            .filter { it.opcode == "G1" && it.has('X') && it.has('E') }
            .last()
        assertEquals(10.0, finalSpatial.value('X')!!, 0.001)
        assertEquals(0.2, finalSpatial.value('Z')!!, 0.001)
        assertTrue(gcode.readLines().any { it.startsWith(";ENDERSLICER_CONICAL:EasyConical-Android-v1") })
    }

    @Test
    fun absoluteExtrusionIsCompensatedForTheTruePathLength() {
        val directory = createTempDirectory("conical-e-test-").toFile()
        val gcode = File(directory, "output.gcode")
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            G90
            M82
            G92 E0
            ;LAYER:0
            G1 X0 Y5 Z0 E0 F1200
            G1 X10 Y5 Z0 E1 F1200
            ;End of Gcode
            """.trimIndent(),
        )
        writePrepared(directory, ConicalSettings(enabled = true, coneAngleDegrees = 16.0, firstLayerHeightMm = 0.0))

        ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        val finalE = gcode.readLines()
            .mapNotNull(GcodeCommand::parse)
            .filter { it.opcode == "G1" && it.has('X') && it.has('E') }
            .mapNotNull { it.value('E') }
            .last()
        // Horizontal distance shrinks by cos(16 deg); the compensated absolute E
        // is below the planar value of 1.0 but still positive.
        assertTrue("Compensated extrusion must shrink", finalE < 1.0)
        assertTrue("Compensated extrusion must stay positive", finalE > 0.5)
    }

    @Test
    fun relativeExtrusionIsSupported() {
        val directory = createTempDirectory("conical-m83-test-").toFile()
        val gcode = File(directory, "output.gcode")
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            G90
            M83
            G92 E0
            ;LAYER:0
            G1 X0 Y5 Z0 E0 F1200
            G1 X10 Y5 Z0 E1 F1200
            ;End of Gcode
            """.trimIndent(),
        )
        writePrepared(directory, ConicalSettings(enabled = true, coneAngleDegrees = 16.0, firstLayerHeightMm = 0.0))

        ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        val relativeEs = gcode.readLines()
            .mapNotNull(GcodeCommand::parse)
            .filter { it.opcode == "G1" && it.has('X') && it.has('E') }
            .mapNotNull { it.value('E') }
        val sum = relativeEs.sum()
        assertTrue("Relative segments must sum to a positive compensated length", sum > 0.5)
        assertTrue("Relative segments must sum below the planar value", sum < 1.0)
    }

    @Test
    fun arcsAreRejected() {
        val directory = createTempDirectory("conical-arc-test-").toFile()
        val gcode = File(directory, "output.gcode")
        val original = """
            ;FLAVOR:Marlin
            G90
            M82
            G2 X10 Y10 I5 J0
            """.trimIndent()
        gcode.writeText(original)
        writePrepared(directory, ConicalSettings(enabled = true))

        val error = runCatching {
            ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(requireNotNull(error).message.orEmpty().contains("G2/G3"))
    }

    @Test
    fun machineEndGcodePassesThroughVerbatim() {
        val directory = createTempDirectory("conical-end-test-").toFile()
        val gcode = File(directory, "output.gcode")
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            G90
            M82
            G92 E0
            ;LAYER:0
            G1 X0 Y0 Z0 E0 F1200
            G1 X5 Y0 Z0 E1 F1200
            ;End of Gcode
            ${ConicalRuntime.MACHINE_END_SENTINEL}
            G0 Z20
            M104 S0
            M140 S0
            """.trimIndent(),
        )
        writePrepared(directory, ConicalSettings(enabled = true, firstLayerHeightMm = 0.0))

        ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        val lines = gcode.readLines()
        val sentinelIndex = lines.indexOf(ConicalRuntime.MACHINE_END_SENTINEL)
        assertTrue(sentinelIndex >= 0)
        val endLines = lines.subList(sentinelIndex + 1, lines.size)
        assertTrue("Machine end travel must not be cone-translated", "G0 Z20" in endLines)
        assertTrue("Machine end temperature commands must pass through", "M104 S0" in endLines)
        assertTrue("Machine end temperature commands must pass through", "M140 S0" in endLines)
    }

    @Test
    fun startGcodePrimeLinesAreNotBackTransformed() {
        val directory = createTempDirectory("conical-prime-test-").toFile()
        val gcode = File(directory, "output.gcode")
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            ;Generated with Cura
            G90
            M82
            G92 E0
            G1 X0.1 Y200.0 Z0.3 F1500.0 E15 ; Draw the first line
            G1 X0.4 Y20 Z0.3 F1500.0 E30 ; Draw the second line
            G92 E0
            ;LAYER:0
            G0 F6000 X5 Y5 Z0.28
            G1 F1200 X5 Y5 E0.1
            G1 X10 Y5 E0.2
            ;End of Gcode
            """.trimIndent(),
        )
        writePrepared(directory, ConicalSettings(enabled = true, coneAngleDegrees = 16.0, firstLayerHeightMm = 0.2))

        ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        val lines = gcode.readLines()
        assertTrue(
            "Prime line must pass through verbatim",
            "G1 X0.1 Y200.0 Z0.3 F1500.0 E15 ; Draw the first line" in lines,
        )
        val extrudedZ = lines.mapNotNull(GcodeCommand::parse)
            .filter { it.opcode == "G1" && it.has('E') && it.has('Z') }
            .mapNotNull { it.value('Z') }
        val minimum = extrudedZ.minOrNull()
        val maximum = extrudedZ.maxOrNull()
        assertNotNull(minimum)
        assertNotNull(maximum)
        assertTrue("Print must touch the bed (min extruded Z ${requireNotNull(minimum)})", requireNotNull(minimum) >= 0.19)
        assertTrue("Print must not float above the bed (max extruded Z ${requireNotNull(maximum)})", requireNotNull(maximum) < 5.0)
    }

    @Test
    fun offAxisWarpCentreRestoresTrueConeSlopeHeight() {
        val directory = createTempDirectory("conical-offaxis-test-").toFile()
        val gcode = File(directory, "output.gcode")
        val settings = ConicalSettings(
            enabled = true,
            coneAngleDegrees = 16.0,
            coneType = ConeType.OUTWARD,
            firstLayerHeightMm = 0.2,
        )
        val centerX = 100.0
        val centerY = 50.0
        // One horizontal Cura layer cutting the warped cone: constant Z, XY
        // pushed outward by 1/cos. Restored cone-slope heights must step down
        // by radius * tan(angle) per point; a radius computed against the
        // wrong centre coordinate breaks that spacing.
        val layerZ = 3.0
        val warpA = ConicalTransform.forward(100.0, 50.0, 0.0, centerX, centerY, settings)
        val warpB = ConicalTransform.forward(110.0, 50.0, 0.0, centerX, centerY, settings)
        val warpC = ConicalTransform.forward(120.0, 50.0, 0.0, centerX, centerY, settings)
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            ;Generated with Cura
            G90
            M82
            G92 E0
            ;LAYER:0
            G0 X${warpA[0]} Y${warpA[1]} Z$layerZ F6000
            G1 X${warpB[0]} Y${warpB[1]} Z$layerZ E0.5 F1200
            G1 X${warpC[0]} Y${warpC[1]} Z$layerZ E1.0 F1200
            ;End of Gcode
            """.trimIndent(),
        )
        writePrepared(directory, settings, centerX, centerY)

        val result = ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        assertNotNull(result)
        val moves = gcode.readLines().mapNotNull(GcodeCommand::parse)
        val zAtB = moves
            .filter { it.opcode == "G1" && it.has('X') && it.has('E') }
            .last { kotlin.math.abs(it.value('X')!! - 110.0) < 0.01 }
            .value('Z')!!
        val zAtC = moves
            .filter { it.opcode == "G1" && it.has('X') && it.has('E') }
            .last { kotlin.math.abs(it.value('X')!! - 120.0) < 0.01 }
            .value('Z')!!
        val expectedStep = 10.0 * kotlin.math.tan(Math.toRadians(16.0))
        assertEquals(expectedStep, zAtB - zAtC, 0.01)
        assertEquals(0.2, zAtC, 0.01)
    }

    @Test
    fun supportTowerBottomsStayOnThePlateAndTheModelIsNotLifted() {
        val directory = createTempDirectory("conical-support-test-").toFile()
        val gcode = File(directory, "output.gcode")
        val settings = ConicalSettings(
            enabled = true,
            coneAngleDegrees = 16.0,
            coneType = ConeType.OUTWARD,
            firstLayerHeightMm = 0.2,
        )
        // Warped "sliced" output around centre (0, 0). The model bottom at the
        // cone apex is the only model move; a support tower under an overhang
        // at original radius 50 sits at the warped radius 52.015 (r / cos) and
        // rises from the warped plate (z = 0.2) to the warped surface
        // (z = 15.0 and 16.0, both above r * tan = 14.916). Back-transformed
        // tower bottoms sit below the bed; they must be anchored to the plate
        // instead of lifting the whole file.
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            ;Generated with Cura
            G90
            M82
            G92 E0
            ;LAYER:0
            G1 X0 Y0 Z0 E0 F1200
            ;TYPE:SUPPORT
            G0 X52.015 Y0 Z0.2 F6000
            G1 X52.015 Y0 Z0.2 E0.2 F1200
            G0 X52.015 Y0 Z15.0 F6000
            G1 X52.015 Y0 Z15.0 E0.4 F1200
            G1 X52.015 Y0 Z16.0 E0.6 F1200
            ;TYPE:WALL-OUTER
            ;End of Gcode
            """.trimIndent(),
        )
        writePrepared(directory, settings)

        val result = ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        assertNotNull(result)
        assertTrue(requireNotNull(result).minimumZmm >= 0.0)

        val lines = gcode.readLines()
        val supportStart = lines.indexOf(";TYPE:SUPPORT")
        val supportEnd = lines.indexOf(";TYPE:WALL-OUTER")
        assertTrue(supportStart >= 0 && supportEnd > supportStart)

        // The model bottom must sit exactly on the first-layer height: the
        // support tower must never drag the global translate() lift upward.
        val modelMoves = lines.subList(0, supportStart)
            .mapNotNull(GcodeCommand::parse)
            .filter { it.opcode == "G1" && it.has('E') && it.has('Z') }
        for (move in modelMoves) {
            assertEquals("Model move must print on the bed", 0.2, move.value('Z')!!, 0.001)
        }

        // Support extrusion layers that land entirely below the plate are
        // skipped; surviving support moves are clamped to the plate.
        val supportMoves = lines.subList(supportStart, supportEnd)
            .mapNotNull(GcodeCommand::parse)
            .filter { it.opcode == "G1" && it.has('E') }
        assertEquals("Only support layers above the plate survive", 2, supportMoves.size)
        val supportZs = lines.subList(supportStart, supportEnd)
            .mapNotNull(GcodeCommand::parse)
            .filter { it.opcode == "G0" || it.opcode == "G1" }
            .mapNotNull { it.value('Z') }
        for (z in supportZs) {
            assertTrue("Support moves must stay on or above the plate, got " + z, z >= 0.2 - 0.001)
        }
    }

    @Test
    fun supportLayersPartiallyBelowThePlateAreClampedToTheBed() {
        val directory = createTempDirectory("conical-support-clamp-test-").toFile()
        val gcode = File(directory, "output.gcode")
        val settings = ConicalSettings(
            enabled = true,
            coneAngleDegrees = 16.0,
            coneType = ConeType.OUTWARD,
            firstLayerHeightMm = 0.2,
        )
        // One long support layer spanning radii 45..55 at warped Z = 14.6:
        // the inner end back-transforms above the plate, the outer end below
        // it. The layer must survive with its below-plate segments clamped.
        gcode.writeText(
            """
            ;FLAVOR:Marlin
            ;Generated with Cura
            G90
            M82
            G92 E0
            ;LAYER:0
            G1 X0 Y0 Z0 E0 F1200
            ;TYPE:SUPPORT
            G0 X46.813 Y0 Z14.6 F6000
            G1 X57.216 Y0 Z14.6 E0.5 F1200
            ;End of Gcode
            """.trimIndent(),
        )
        writePrepared(directory, settings)

        val result = ConicalStorage.backtransformStagedGcode(gcode, printerEnvelope())

        assertNotNull(result)
        val lines = gcode.readLines()
        val supportStart = lines.indexOf(";TYPE:SUPPORT")
        val supportZs = lines.subList(supportStart, lines.size)
            .mapNotNull(GcodeCommand::parse)
            .filter { (it.opcode == "G0" || it.opcode == "G1") && it.has('E') }
            .mapNotNull { it.value('Z') }
        assertTrue("The partial support layer must survive", supportZs.isNotEmpty())
        for (z in supportZs) {
            assertTrue("Support segments must stay on or above the plate, got " + z, z >= 0.2 - 0.001)
        }
        assertTrue(
            "Below-plate segments must be clamped onto the bed",
            supportZs.minOrNull()!! < 0.21,
        )
    }

    private fun writePrepared(
        directory: File,
        settings: ConicalSettings,
        centerX: Double = 0.0,
        centerY: Double = 0.0,
    ) {
        ConicalStorage.write(
            directory,
            ConicalPipeline.Prepared(
                centerX = centerX,
                centerY = centerY,
                settings = settings.copy(enabled = true).validated(),
                diagnostics = ConicalPipeline.Diagnostics(
                    sourceTriangles = 1,
                    refinedTriangles = 1,
                    refinementIterations = settings.refinementIterations,
                    coneAngleDegrees = settings.coneAngleDegrees,
                ),
            ),
        )
    }

    private fun printerEnvelope(): PrinterEnvelope = PrinterEnvelope(
        widthMm = 220.0,
        depthMm = 220.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        gcodeFlavor = "marlin",
    )
}
