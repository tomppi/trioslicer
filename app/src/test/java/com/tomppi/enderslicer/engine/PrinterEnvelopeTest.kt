package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PrinterEnvelopeTest {
    @Test
    fun rectangularFrontLeftEnvelopeCoversAllSixBoundsWithTolerance() {
        val envelope = rectangular(originAtCenter = false)

        assertTrue(envelope.contains(0.0, 0.0, 0.0))
        assertTrue(envelope.contains(230.049, 230.049, 250.049))
        assertTrue(envelope.contains(-0.049, -0.049, -0.049))
        assertFalse(envelope.contains(-0.051, 10.0, 1.0))
        assertFalse(envelope.contains(230.051, 10.0, 1.0))
        assertFalse(envelope.contains(10.0, -0.051, 1.0))
        assertFalse(envelope.contains(10.0, 230.051, 1.0))
        assertFalse(envelope.contains(10.0, 10.0, -0.051))
        assertFalse(envelope.contains(10.0, 10.0, 250.051))
    }

    @Test
    fun rectangularCenteredEnvelopeUsesSymmetricCoordinates() {
        val envelope = rectangular(originAtCenter = true)

        assertTrue(envelope.contains(-115.05, -115.05, 0.0))
        assertTrue(envelope.contains(115.05, 115.05, 250.0))
        assertFalse(envelope.contains(-115.051, 0.0, 1.0))
        assertFalse(envelope.contains(115.051, 0.0, 1.0))
    }

    @Test
    fun ellipticEnvelopeChecksTheActualPlateEquation() {
        val frontLeft = PrinterEnvelope(200.0, 100.0, 200.0, "elliptic", false)
        val centered = PrinterEnvelope(200.0, 100.0, 200.0, "ellipse", true)

        assertTrue(frontLeft.contains(200.0, 50.0, 1.0))
        assertTrue(frontLeft.contains(100.0, 100.0, 1.0))
        assertFalse(frontLeft.contains(200.0, 100.0, 1.0))
        assertTrue(centered.contains(-100.0, 0.0, 1.0))
        assertTrue(centered.contains(0.0, 50.0, 1.0))
        assertFalse(centered.contains(100.0, 50.0, 1.0))
    }

    @Test
    fun binaryStlPreflightStreamsEveryTransformedVertex() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-envelope-stl").toFile()
        val valid = File(directory, "valid.stl")
        val invalid = File(directory, "invalid.stl")
        writeBinaryStl(valid, listOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(230f, 0f, 0f),
            floatArrayOf(0f, 230f, 250f),
        ))
        writeBinaryStl(invalid, listOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(230.2f, 0f, 0f),
            floatArrayOf(0f, 10f, 1f),
        ))

        rectangular(false).requireBinaryStlFits(valid)
        val error = runCatching { rectangular(false).requireBinaryStlFits(invalid) }.exceptionOrNull()

        assertTrue(error is PrinterEnvelope.OutsideBuildVolumeException)
        assertTrue(error?.message.orEmpty().contains("Model vertex 2"))
    }

    @Test
    fun binaryStlPreflightAppliesTheRequestTransformBeforeBoundsChecking() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-envelope-transform").toFile()
        val source = File(directory, "source.stl")
        writeBinaryStl(source, listOf(
            floatArrayOf(1000f, 1000f, 10f),
            floatArrayOf(1010f, 1000f, 10f),
            floatArrayOf(1000f, 1010f, 20f),
        ))
        val transform = com.tomppi.enderslicer.viewer.StlSliceTransform(
            linear = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            translationXmm = -990.0,
            translationYmm = -990.0,
            translationZmm = -10.0,
        )

        rectangular(false).requireBinaryStlFits(source, transform)
        val error = runCatching { rectangular(false).requireBinaryStlFits(source) }.exceptionOrNull()

        assertTrue(error is PrinterEnvelope.OutsideBuildVolumeException)
    }

    @Test
    fun sanitizerRejectsPositiveSupportAdhesionAndStartupExtrusionsOutsideThePlate() {
        listOf(";TYPE:SUPPORT", ";TYPE:SKIRT", "; custom startup purge").forEach { marker ->
            val file = temporaryGcode(
                """
                ;FLAVOR:Marlin
                M83
                M104 S210
                $marker
                G1 X10 Y10 Z0.2 F1200
                G1 X231 Y10 E1 F1200
                ;TIME_ELAPSED:1
                M104 S0
                """.trimIndent(),
            )

            val error = runCatching {
                GcodeSanitizer.validateAndRepair(file, printerEnvelope = rectangular(false))
            }.exceptionOrNull()

            assertTrue("Expected $marker to be rejected", error is PrinterEnvelope.OutsideBuildVolumeException)
        }
    }

    @Test
    fun sanitizerChecksBothEndsOfAnExtrusionAndTheMachineHeight() {
        val outsideStart = temporaryGcode(
            """
            ;FLAVOR:Marlin
            M83
            M104 S210
            G1 X231 Y10 Z0.2 F1200
            G1 X10 Y10 E1 F1200
            M104 S0
            """.trimIndent(),
        )
        val aboveHeight = temporaryGcode(
            """
            ;FLAVOR:Marlin
            M83
            M104 S210
            G1 X10 Y10 Z250.2 F1200
            G1 X20 Y10 E1 F1200
            M104 S0
            """.trimIndent(),
        )

        assertTrue(
            runCatching {
                GcodeSanitizer.validateAndRepair(outsideStart, printerEnvelope = rectangular(false))
            }.exceptionOrNull() is PrinterEnvelope.OutsideBuildVolumeException,
        )
        assertTrue(
            runCatching {
                GcodeSanitizer.validateAndRepair(aboveHeight, printerEnvelope = rectangular(false))
            }.exceptionOrNull() is PrinterEnvelope.OutsideBuildVolumeException,
        )
    }

    @Test
    fun printerEnvelopeRoundTripsThroughArtifactMetadata() {
        val file = File(kotlin.io.path.createTempDirectory("enderslicer-envelope-json").toFile(), "envelope.json")
        val expected = PrinterEnvelope(220.0, 180.0, 300.0, "circle", true)

        expected.writeTo(file)
        val actual = PrinterEnvelope.readFrom(file)

        assertEquals(expected.widthMm, actual.widthMm, 0.0)
        assertEquals(expected.depthMm, actual.depthMm, 0.0)
        assertEquals(expected.heightMm, actual.heightMm, 0.0)
        assertEquals(expected.originAtCenter, actual.originAtCenter)
        assertEquals("elliptic", actual.buildPlateShape)
    }

    private fun rectangular(originAtCenter: Boolean): PrinterEnvelope = PrinterEnvelope(
        widthMm = 230.0,
        depthMm = 230.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = originAtCenter,
    )

    private fun temporaryGcode(content: String): File {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-envelope-gcode").toFile()
        return File(directory, "output.gcode").apply { writeText(content) }
    }

    private fun writeBinaryStl(file: File, vertices: List<FloatArray>) {
        require(vertices.size == 3)
        file.outputStream().use { output ->
            output.write(ByteArray(80))
            output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
            val triangle = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
            repeat(3) { triangle.putFloat(0f) }
            vertices.forEach { vertex ->
                vertex.forEach { value -> triangle.putFloat(value) }
            }
            triangle.putShort(0)
            output.write(triangle.array())
        }
    }

    @Test
    fun theLabelNamesTheVolumeThatDoesNotFit() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-envelope-label").toFile()
        val modifier = File(directory, "smart-infill-1-30pct.stl")
        writeBinaryStl(modifier, listOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(230.2f, 0f, 0f),
            floatArrayOf(0f, 10f, 1f),
        ))

        // Without a label every volume reads as "Model vertex N", which once sent
        // a debugging session after the model instead of the modifier.
        val unlabelled = runCatching { rectangular(false).requireBinaryStlFits(modifier) }
            .exceptionOrNull()?.message.orEmpty()
        assertTrue(unlabelled, unlabelled.contains("Model vertex 2"))

        val labelled = runCatching {
            rectangular(false).requireBinaryStlFits(modifier, label = "Smart Infill 30% modifier")
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue(labelled, labelled.contains("Smart Infill 30% modifier vertex 2"))
    }
}