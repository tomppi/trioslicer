package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResolvedPrinterEnvelopeFlowTest {
    @Test
    fun postProcessingPrefersResolvedWorkspaceEnvelopeOverFallbackPrinter() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-resolved-envelope-post").toFile()
        val output = File(directory, "output.gcode").apply {
            writeText(
                """
                ;FLAVOR:Marlin
                ;LAYER_COUNT:1
                M83
                M104 S210
                ;LAYER:0
                ;MESH:model.stl
                G1 X10 Y10 Z0.2 F1200
                G1 X30 Y10 E1 F1200
                ;TIME_ELAPSED:1
                M104 S0
                """.trimIndent(),
            )
        }
        val base = File(directory, "base.gcode")
        val resolved = PrinterEnvelope(20.0, 20.0, 50.0, "rectangular", false)
        resolved.writeTo(File(directory, PrinterEnvelope.METADATA_FILE_NAME))
        val fallback = PrinterEnvelope(230.0, 230.0, 250.0, "rectangular", false)

        val error = runCatching {
            CuraEnginePostProcessor.process(
                outputFile = output,
                baseGcodeFile = base,
                settingsTransport = "resolved-json",
                layerEvents = emptyList(),
                printerEnvelope = fallback,
            )
        }.exceptionOrNull()

        assertTrue(error is PrinterEnvelope.OutsideBuildVolumeException)
    }

    @Test
    fun publicationPreservesResolvedWorkspaceEnvelope() {
        val resultRoot = kotlin.io.path.createTempDirectory("enderslicer-resolved-envelope-results").toFile()
        val workspace = kotlin.io.path.createTempDirectory("enderslicer-resolved-envelope-work").toFile()
        val output = File(workspace, "output.gcode").apply { writeText("validated-output") }
        val base = File(workspace, "base.gcode").apply { writeText("validated-base") }
        val resolved = PrinterEnvelope(180.0, 160.0, 220.0, "elliptic", true)
        resolved.writeTo(File(workspace, PrinterEnvelope.METADATA_FILE_NAME))
        val fallback = PrinterEnvelope(230.0, 230.0, 250.0, "rectangular", false)

        val artifact = SliceArtifactPublisher(resultRoot).publish(
            id = "resolved-envelope",
            gcodeSource = output,
            baseGcodeSource = base,
            printerEnvelope = fallback,
        )

        assertEquals(resolved, SliceArtifactPublisher.readPrinterEnvelope(artifact.gcodeFile))
    }
}
