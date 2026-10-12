package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Object labels must survive the published-G-code policy.
 *
 * PrusaSlicer's firmware labelling writes M486 with the object's own name in quotes, and the
 * parameter scanner used to read the digits inside that name as parameters: the app's own
 * "cube10.stl lower" produced E10, so publishing rejected the engine's output at the first
 * labelled object ("Published G-code rejects unsupported M486 parameters at line 22") and the
 * label the printer needs never reached the file.
 */
class PublishedObjectLabelTest {

    @Test
    fun aQuotedObjectNameIsTextRatherThanParameters() {
        val command = requireNotNull(GcodeCommand.parse("M486 A\"cube10.stl lower\""))

        assertTrue(
            "the name's own digits are not parameters: " + command.parameterLetters,
            command.parameterLetters.isEmpty(),
        )
        assertEquals("A\"cube10.stl lower\"", command.rawArguments)
    }

    @Test
    fun everyFirmwareLabelledMarkerPassesThePublishedPolicy() {
        val lines = listOf(
            "M486 A\"cube10.stl lower\"",
            "M486 S0",
            "M486 S-1",
            "M486 C",
            "M486 P\"cube10.stl lower\"",
            "M486 T2",
            "M486 U1",
            "M486 S12345 A\"part-2\"",
        )
        lines.forEachIndexed { index, line ->
            val command = requireNotNull(GcodeCommand.parse(line)) { line }
            GcodeCommandPolicy.requirePublishedSafe(command, currentLayer = 1, lineNumber = index + 1)
        }
    }

    @Test
    fun aMalformedObjectMarkerIsStillRefused() {
        listOf("M486 X1", "M486 S-2", "M486 S1.5").forEach { line ->
            val command = requireNotNull(GcodeCommand.parse(line)) { line }
            val error = runCatching {
                GcodeCommandPolicy.requirePublishedSafe(command, currentLayer = 1, lineNumber = 7)
            }.exceptionOrNull()
            assertTrue(line + " has to be refused, got " + error, error is IllegalArgumentException)
        }
    }

    @Test
    fun aPublishedFileKeepsItsObjectLabels() {
        val file = temporaryGcode(
            """
            ;FLAVOR:Marlin
            M83
            M486 T1
            M486 A"cube10.stl lower"
            ;LAYER_CHANGE
            ;Z:0.2
            M486 S0
            G1 X10 Y10 Z0.2 E1 F1200
            G1 X20 Y10 E1 F1200
            M486 S-1
            M486 U1
            """.trimIndent(),
        )

        GcodeSanitizer.validateAndRepair(file)

        val text = file.readText()
        assertTrue("the name has to survive", text.contains("M486 A\"cube10.stl lower\""))
        assertTrue("the object selection has to survive", text.contains("M486 S0"))
        assertTrue("clearing the selection has to survive", text.contains("M486 S-1"))
        assertTrue("the reset has to survive", text.contains("M486 T1"))
    }

    private fun temporaryGcode(content: String): File {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-object-label").toFile()
        return File(directory, "output.gcode").apply { writeText(content) }
    }
}
