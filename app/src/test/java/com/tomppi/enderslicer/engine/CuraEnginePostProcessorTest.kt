package com.tomppi.enderslicer.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CuraEnginePostProcessorTest {
    @Test
    fun noEventsReuseFirstValidationAndBasePreviewWithoutRewritingOutput() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-postprocess").toFile()
        val output = File(directory, "output.gcode").apply { writeText(sampleGcode()) }
        val base = File(directory, "base.gcode")

        val result = CuraEnginePostProcessor.process(
            outputFile = output,
            baseGcodeFile = base,
            settingsTransport = "resolved-json",
            layerEvents = emptyList(),
            printerEnvelope = envelope(),
        )

        assertTrue(result.usedZeroEventFastPath)
        assertTrue(result.layerEvents.isEmpty())
        assertNull(result.previewFailure)
        assertNotNull(result.layerPreview)
        assertEquals(2, result.summary.layerCount)
        assertArrayEquals(base.readBytes(), output.readBytes())
        assertEquals(1, output.readLines().count { it == ";ENDERSLICER_SETTINGS_TRANSPORT:resolved-json" })
        assertFalse(output.readText().contains("+layer-events"))
        assertFalse(output.readText().contains(";ENDERSLICER_LAYER_EVENT:"))
    }

    @Test
    fun filteredInvalidEventsAlsoUseTheZeroEventFastPath() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-postprocess-filtered").toFile()
        val output = File(directory, "output.gcode").apply { writeText(sampleGcode()) }
        val base = File(directory, "base.gcode")
        val invalid = LayerEvent(
            id = "outside-model",
            layerNumber = 999,
            zMm = 99f,
            type = LayerEventType.MESSAGE,
            text = "Never emitted",
        )

        val result = CuraEnginePostProcessor.process(
            outputFile = output,
            baseGcodeFile = base,
            settingsTransport = "resolved-json",
            layerEvents = listOf(invalid),
            printerEnvelope = envelope(),
        )

        assertTrue(result.usedZeroEventFastPath)
        assertTrue(result.layerEvents.isEmpty())
        assertArrayEquals(base.readBytes(), output.readBytes())
        assertFalse(output.readText().contains(";ENDERSLICER_LAYER_EVENT:"))
    }

    @Test
    fun validEventsKeepTheExistingMaterializeRevalidateAndPreviewPipeline() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-postprocess-events").toFile()
        val output = File(directory, "output.gcode").apply { writeText(sampleGcode()) }
        val base = File(directory, "base.gcode")
        val event = LayerEvent(
            id = "user-message",
            layerNumber = 1,
            zMm = 0.4f,
            type = LayerEventType.MESSAGE,
            text = "Second layer",
        )

        val result = CuraEnginePostProcessor.process(
            outputFile = output,
            baseGcodeFile = base,
            settingsTransport = "resolved-json",
            layerEvents = listOf(event),
            printerEnvelope = envelope(),
        )

        assertFalse(result.usedZeroEventFastPath)
        assertEquals(listOf(event), result.layerEvents)
        assertNull(result.previewFailure)
        assertNotNull(result.layerPreview)
        assertFalse(base.readText().contains(";ENDERSLICER_LAYER_EVENT:"))
        assertTrue(base.readText().contains(";ENDERSLICER_SETTINGS_TRANSPORT:resolved-json"))
        assertTrue(output.readText().contains(";ENDERSLICER_LAYER_EVENT:user-message:MESSAGE:USER"))
        assertTrue(output.readText().contains(";ENDERSLICER_SETTINGS_TRANSPORT:resolved-json+layer-events"))
    }

    @Test
    fun adaptiveMeshLevelingSurvivesTheLayerEventRebuildOfTheBaseGcode() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-postprocess-aml").toFile()
        val output = File(directory, "output.gcode").apply { writeText(sampleGcode()) }
        val base = File(directory, "base.gcode")
        val event = LayerEvent(
            id = "user-message",
            layerNumber = 1,
            zMm = 0.4f,
            type = LayerEventType.MESSAGE,
            text = "Second layer",
        )

        val result = CuraEnginePostProcessor.process(
            outputFile = output,
            baseGcodeFile = base,
            settingsTransport = "resolved-json",
            layerEvents = listOf(event),
            printerEnvelope = envelope(),
            amlEnabled = true,
        )

        // The events path republishes base.gcode, so the AML block has to live in
        // it: injecting after the base copy lost leveling on every edited slice.
        assertEquals(1, base.readLines().count { it == AdaptiveBedMeshInjector.MARKER })
        assertEquals(1, output.readLines().count { it == AdaptiveBedMeshInjector.MARKER })
        assertTrue(base.readText().contains("C29 L0 R15 F0 B5"))
        assertTrue(output.readText().contains("C29 L0 R15 F0 B5"))
        assertTrue(output.readText().contains(";ENDERSLICER_LAYER_EVENT:user-message:MESSAGE:USER"))
        // The sanitizer and the preview ran on the published bytes, so the
        // transport marker names the injection too.
        assertTrue(
            output.readText().contains(
                ";ENDERSLICER_SETTINGS_TRANSPORT:resolved-json+adaptive-mesh-leveling+layer-events",
            ),
        )
        assertFalse(result.usedZeroEventFastPath)
        assertNotNull(result.layerPreview)
        assertEquals(2, result.summary.layerCount)
    }

    @Test
    fun adaptiveMeshLevelingRunsAfterTheBaseCopyWhenItIsTheOnlyPostProcessingStep() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-postprocess-aml-fast").toFile()
        val output = File(directory, "output.gcode").apply { writeText(sampleGcode()) }
        val base = File(directory, "base.gcode")

        val result = CuraEnginePostProcessor.process(
            outputFile = output,
            baseGcodeFile = base,
            settingsTransport = "resolved-json",
            layerEvents = emptyList(),
            printerEnvelope = envelope(),
            amlEnabled = true,
        )

        assertTrue(result.usedZeroEventFastPath)
        assertArrayEquals(base.readBytes(), output.readBytes())
        assertEquals(1, output.readLines().count { it == AdaptiveBedMeshInjector.MARKER })
        assertTrue(
            output.readText().contains(
                ";ENDERSLICER_SETTINGS_TRANSPORT:resolved-json+adaptive-mesh-leveling",
            ),
        )
    }

    @Test
    fun failsLoudWhenNonPlanarGcodeHasNoSurfaceDataInsteadOfSilentlyGoingPlanar() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-postprocess-nonplanar-lost").toFile()
        val output = File(directory, "output.gcode").apply {
            writeText(
                sampleGcode() + "\n" +
                    com.tomppi.enderslicer.nonplanar.NonPlanarRuntime.MACHINE_END_SENTINEL + "\n" +
                    "G28\n",
            )
        }
        val base = File(directory, "base.gcode")

        val error = runCatching {
            CuraEnginePostProcessor.process(
                outputFile = output,
                baseGcodeFile = base,
                settingsTransport = "resolved-json",
                layerEvents = emptyList(),
                printerEnvelope = envelope(),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("Non-planar printing was requested"))
    }

    private fun envelope(): PrinterEnvelope = PrinterEnvelope(
        widthMm = 230.0,
        depthMm = 230.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
    )

    private fun sampleGcode(): String = """
        ;FLAVOR:Marlin
        ;TIME:2
        ;Filament used: 0m
        ;LAYER_COUNT:2
        M82
        M104 S210
        G92 E0
        ;LAYER:0
        ;TYPE:WALL-OUTER
        ;MESH:model.stl
        G1 X0 Y0 Z0.2 F1200
        G1 X10 Y0 E1 F1200
        ;LAYER:1
        G1 X10 Y10 Z0.4 E2 F1200
        ;TIME_ELAPSED:2
        M104 S0
    """.trimIndent()
}
