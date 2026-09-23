package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LayerEventOrderingTest {
    @Test
    fun numericUserOrdinalsRecoverChronologyAfterLexicalPresort() {
        val chronological = listOf(
            fanEvent("user-2", 20.0),
            fanEvent("user-10", 40.0),
            fanEvent("user-11", 60.0),
        )
        val legacyLexicalOrder = chronological.sortedBy(LayerEvent::id)

        val normalized = LayerEventOrdering.normalize(legacyLexicalOrder)

        assertEquals(listOf("user-2", "user-10", "user-11"), normalized.map(LayerEvent::id))
    }

    @Test
    fun materializerEmitsNewestSameLayerStateLast() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-event-order").toFile()
        val base = File(directory, "base.gcode").apply { writeText(baseGcode()) }
        val output = File(directory, "output.gcode")
        val legacyLexicalOrder = listOf(
            fanEvent("user-2", 20.0),
            fanEvent("user-10", 40.0),
            fanEvent("user-11", 60.0),
        ).sortedBy(LayerEvent::id)

        GcodeLayerEventProcessor.materialize(base, output, legacyLexicalOrder)
        val text = output.readText()
        val markers = Regex(";ENDERSLICER_LAYER_EVENT:([^:]+):")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(listOf("user-2", "user-10", "user-11"), markers)
        assertTrue(text.lastIndexOf("M106 S153") > text.lastIndexOf("M106 S102"))
    }

    private fun fanEvent(
        id: String,
        value: Double,
    ): LayerEvent = LayerEvent(
        id = id,
        layerNumber = 1,
        zMm = 0.4f,
        type = LayerEventType.FAN_SPEED,
        value = value,
    )

    private fun baseGcode(): String = """
        ;FLAVOR:Marlin
        M83
        ;LAYER:0
        G1 X1 Y0 Z0.2 E1 F1200
        ;LAYER:1
        G1 X2 Y0 Z0.4 E1 F1200
    """.trimIndent()
}
