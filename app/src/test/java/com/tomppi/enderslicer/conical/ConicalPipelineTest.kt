package com.tomppi.enderslicer.conical

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlParser
import com.tomppi.enderslicer.viewer.VertexData
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConicalPipelineTest {
    @Test
    fun prepareAndWarpWritesAValidRefinedAndWarpedStl() {
        val directory = createTempDirectory("conical-pipeline-").toFile()
        val modelFile = File(directory, "model.stl")
        StlMeshWriter.writeBinary(squareMesh(), modelFile)

        val settings = ConicalSettings(
            enabled = true,
            coneAngleDegrees = 16.0,
            refinementIterations = 1,
            coneType = ConeType.OUTWARD,
        )
        val prepared = ConicalPipeline.prepareAndWarp(modelFile, settings)

        assertEquals(2 * 4, prepared.diagnostics.refinedTriangles)
        assertEquals(1, prepared.diagnostics.refinementIterations)

        val warped = StlParser.parse(modelFile, modelFile.name)
        assertEquals(8, warped.triangleCount)
        assertTrue(warped.bounds.width > 100f)
        assertTrue(warped.bounds.maxZ > 0f)

        ConicalStorage.write(directory, prepared)
        val sidecar = ConicalStorage.backtransformStagedGcode(
            File(directory, "output.gcode").apply { writeText(sampleGcode()) },
            printerEnvelope(),
        )
        assertNotNull("Sidecar must round-trip through the workspace", sidecar)
    }

    @Test
    fun interruptedThreadAbortsPreparationAndLeavesTheSourceIntact() {
        val directory = createTempDirectory("conical-cancel-").toFile()
        val modelFile = File(directory, "model.stl")
        StlMeshWriter.writeBinary(squareMesh(), modelFile)
        val original = modelFile.readBytes()

        Thread.currentThread().interrupt()
        try {
            val failure = runCatching {
                ConicalPipeline.prepareAndWarp(
                    modelFile,
                    ConicalSettings(enabled = true, refinementIterations = 2),
                )
            }.exceptionOrNull()
            assertTrue("Expected InterruptedException but got: " + failure, failure is InterruptedException)
        } finally {
            Thread.interrupted()
        }
        assertArrayEquals(original, modelFile.readBytes())
    }

    @Test
    fun warpModifierWarpsPaintedPrismsAroundTheModelCentre() {
        val directory = createTempDirectory("conical-modifier-").toFile()
        val modelFile = File(directory, "model.stl")
        StlMeshWriter.writeBinary(squareMesh(), modelFile)
        val prismFile = File(directory, "support-enforcer.stl")
        val prism = paintedPrismMesh()
        StlMeshWriter.writeBinary(prism, prismFile)

        val settings = ConicalSettings(
            enabled = true,
            coneAngleDegrees = 16.0,
            refinementIterations = 1,
            coneType = ConeType.OUTWARD,
        )
        val prepared = ConicalPipeline.prepareAndWarp(modelFile, settings)

        prepared.warpModifier(prismFile)

        val warped = StlParser.parse(prismFile, prismFile.name)
        assertTrue("The painted prism must share the model refinement", warped.triangleCount > prism.triangleCount)
        assertTrue("The outward cone warp must lift the painted prism", warped.bounds.maxZ > prism.bounds.maxZ)

        // Every original prism vertex must map through the cone forward
        // transform around the MODEL centre, not the prism's own centre.
        val expected = ArrayList<DoubleArray>()
        var sourceOffset = 0
        repeat(prism.triangleCount) {
            repeat(3) { vertex ->
                val base = sourceOffset + vertex * 6
                expected += ConicalTransform.forward(
                    prism.interleavedVertices[base].toDouble(),
                    prism.interleavedVertices[base + 1].toDouble(),
                    prism.interleavedVertices[base + 2].toDouble(),
                    prepared.centerX,
                    prepared.centerY,
                    settings,
                )
            }
            sourceOffset += 18
        }
        val warpedVertices = ArrayList<DoubleArray>()
        var warpedOffset = 0
        repeat(warped.triangleCount) {
            repeat(3) { vertex ->
                val base = warpedOffset + vertex * 6
                warpedVertices += doubleArrayOf(
                    warped.interleavedVertices[base].toDouble(),
                    warped.interleavedVertices[base + 1].toDouble(),
                    warped.interleavedVertices[base + 2].toDouble(),
                )
            }
            warpedOffset += 18
        }
        for (point in expected) {
            val matched = warpedVertices.any { vertex ->
                kotlin.math.abs(vertex[0] - point[0]) < 0.005 &&
                    kotlin.math.abs(vertex[1] - point[1]) < 0.005 &&
                    kotlin.math.abs(vertex[2] - point[2]) < 0.005
            }
            assertTrue(
                "Warped prism must contain forward(" + point[0] + ", " + point[1] + ", " + point[2] +
                    ") around the model centre",
                matched,
            )
        }
    }

    @Test
    fun fullPipelineRoundTripThroughRealFiles() {
        val directory = createTempDirectory("conical-roundtrip-").toFile()
        val modelFile = File(directory, "model.stl")
        val mesh = squareMesh()
        StlMeshWriter.writeBinary(mesh, modelFile)

        val settings = ConicalSettings(
            enabled = true,
            coneAngleDegrees = 16.0,
            refinementIterations = 0,
            coneType = ConeType.OUTWARD,
            firstLayerHeightMm = 0.2,
        )
        val prepared = ConicalPipeline.prepareAndWarp(modelFile, settings)

        // Simulate CuraEngine slicing the warped solid: emit G1 moves to the
        // warped coordinates of the original mesh vertices at a nominal layer Z.
        val centerX = prepared.centerX
        val centerY = prepared.centerY
        val gcodeFile = File(directory, "output.gcode")
        val source = squareMesh()
        val warpedLayerZ = 0.5
        val lines = buildList {
            add(";FLAVOR:Marlin")
            add(";Generated with Cura")
            add("G90")
            add("M82")
            add("G92 E0")
            add(";LAYER:0")
            var e = 0.0
            var offset = 0
            val vertices = ArrayList<Triple<Double, Double, Double>>()
            repeat(source.triangleCount) {
                repeat(3) { vertex ->
                    val base = offset + vertex * 6
                    val x = source.interleavedVertices[base].toDouble()
                    val y = source.interleavedVertices[base + 1].toDouble()
                    val z = source.interleavedVertices[base + 2].toDouble()
                    vertices.add(Triple(x, y, z))
                }
                offset += 18
            }
            for ((x, y, z) in vertices) {
                val warped = ConicalTransform.forward(x, y, z, centerX, centerY, settings)
                e += 0.25
                add("G1 X${fmt(warped[0])} Y${fmt(warped[1])} Z${fmt(warped[2])} E${fmt(e)} F1200")
            }
            add(";End of Gcode")
        }
        gcodeFile.writeText(lines.joinToString("\n"))

        val diagnostics = prepared.backtransformGcode(gcodeFile, printerEnvelope())

        assertTrue(diagnostics.emittedMoves >= lines.count { it.startsWith("G1 X") })
        val output = gcodeFile.readLines().mapNotNull(GcodeCommand::parse)
        val spatial = output.filter { it.opcode == "G1" && it.has('X') && it.has('E') }
        val restoredX = spatial.mapNotNull { it.value('X') }.toSet()
        val restoredY = spatial.mapNotNull { it.value('Y') }.toSet()
        val originalX = source.interleavedVertices.toFloatArray()
            .filterIndexed { i, _ -> i % 6 == 0 }
            .map { it.toDouble() }
            .toSet()
        val originalY = source.interleavedVertices.toFloatArray()
            .filterIndexed { i, _ -> i % 6 == 1 }
            .map { it.toDouble() }
            .toSet()
        for (x in originalX) {
            assertTrue("Restored X $x must appear in the back-transformed G-code", restoredX.any { kotlin.math.abs(it - x) < 0.002 })
        }
        for (y in originalY) {
            assertTrue("Restored Y $y must appear in the back-transformed G-code", restoredY.any { kotlin.math.abs(it - y) < 0.002 })
        }
    }

    private fun paintedPrismMesh(): StlMesh {
        // A small painted patch floating at z = 1 above the model centre (110, 110).
        val triangles = listOf(
            floatArrayOf(109f, 109f, 1f, 111f, 109f, 1f, 109f, 111f, 1f),
            floatArrayOf(111f, 109f, 1f, 111f, 111f, 1f, 109f, 111f, 1f),
        )
        val interleaved = FloatArray(triangles.size * 18)
        var offset = 0
        for (triangle in triangles) {
            repeat(3) { vertex ->
                val base = offset + vertex * 6
                interleaved[base] = triangle[vertex * 3]
                interleaved[base + 1] = triangle[vertex * 3 + 1]
                interleaved[base + 2] = triangle[vertex * 3 + 2]
                interleaved[base + 3] = 0f
                interleaved[base + 4] = 0f
                interleaved[base + 5] = 1f
            }
            offset += 18
        }
        return StlMesh(
            displayName = "support-enforcer.stl",
            interleavedVertices = VertexData.fromArray(interleaved),
            triangleCount = triangles.size,
            bounds = MeshBounds(109f, 109f, 1f, 111f, 111f, 1f),
        )
    }

    private fun squareMesh(): StlMesh {
        // A 100 x 100 mm slab centred on (110, 110) with two triangles at z=0.
        val triangles = listOf(
            floatArrayOf(60f, 60f, 0f, 160f, 60f, 0f, 60f, 160f, 0f),
            floatArrayOf(160f, 60f, 0f, 160f, 160f, 0f, 60f, 160f, 0f),
        )
        val interleaved = FloatArray(triangles.size * 18)
        var offset = 0
        for (triangle in triangles) {
            repeat(3) { vertex ->
                val base = offset + vertex * 6
                interleaved[base] = triangle[vertex * 3]
                interleaved[base + 1] = triangle[vertex * 3 + 1]
                interleaved[base + 2] = triangle[vertex * 3 + 2]
                interleaved[base + 3] = 0f
                interleaved[base + 4] = 0f
                interleaved[base + 5] = 0f
            }
            offset += 18
        }
        return StlMesh(
            displayName = "square.stl",
            interleavedVertices = VertexData.fromArray(interleaved),
            triangleCount = triangles.size,
            bounds = MeshBounds(60f, 60f, 0f, 160f, 160f, 0f),
        )
    }

    private fun sampleGcode(): String = """
        ;FLAVOR:Marlin
        G90
        M82
        G92 E0
        ;LAYER:0
        G1 X0 Y0 Z0.2 E0 F1200
        G1 X10 Y10 Z0.2 E1 F1200
        ;End of Gcode
    """.trimIndent()

    private fun printerEnvelope(): PrinterEnvelope = PrinterEnvelope(
        widthMm = 220.0,
        depthMm = 220.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        gcodeFlavor = "marlin",
    )

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.6f", value)
        .trimEnd('0')
        .trimEnd('.')
        .let { if (it == "-0") "0" else it }
}
