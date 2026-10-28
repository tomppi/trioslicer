package com.tomppi.enderslicer.mesh2step

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The engine's JSON-lines protocol, its CLI mapping and the cache file name are the contract
 * between the bundled Mesh-Mix-S2C engine and the app; these tests pin every event kind the
 * engine emits and the exact arguments the seam passes.
 */
class MeshToStepProtocolTest {

    private val request = MeshToStepEngine.Request(sourceStl = File("/tmp/part.stl"))

    @Test
    fun everyEventKindTheEngineEmitsIsParsed() {
        val stage = MeshToStepProtocol.parse("""{"event":"stage","stage":"Loading mesh","progress":0.25}""")
        assertEquals(MeshToStepProtocol.Event.Stage("Loading mesh", 0.25f), stage)

        val progress = MeshToStepProtocol.parse(
            """{"event":"progress","stage":"Fitting surfaces","progress":0.625}""",
        )
        assertEquals(MeshToStepProtocol.Event.Progress("Fitting surfaces", 0.625f), progress)

        val log = MeshToStepProtocol.parse("""{"event":"log","message":"Using CAD"}""")
        assertEquals(MeshToStepProtocol.Event.Log("Using CAD"), log)

        val warning = MeshToStepProtocol.parse("""{"event":"warning","message":"Sewing left a gap"}""")
        assertEquals(MeshToStepProtocol.Event.Warning("Sewing left a gap"), warning)

        val part = MeshToStepProtocol.parse("""{"event":"part","name":"lid","output":"lid.step"}""")
        assertTrue(part is MeshToStepProtocol.Event.Part)
        assertEquals("lid", (part as MeshToStepProtocol.Event.Part).part.getString("name"))

        val result = MeshToStepProtocol.parse("""{"event":"result","valid":true,"faces":31}""")
        assertTrue(result is MeshToStepProtocol.Event.Result)
        assertTrue((result as MeshToStepProtocol.Event.Result).data.getBoolean("valid"))

        val error = MeshToStepProtocol.parse(
            """{"event":"error","message":"The file is damaged.","reason":"The file itself is the problem.","details":"Traceback"}""",
        )
        assertEquals(
            MeshToStepProtocol.Event.Error(
                "The file is damaged.",
                "The file itself is the problem.",
                "Traceback",
            ),
            error,
        )
    }

    @Test
    fun blankLinesAreIgnoredAndGarbageIsKeptAsMalformed() {
        assertNull(MeshToStepProtocol.parse(""))
        assertNull(MeshToStepProtocol.parse("   "))
        assertEquals(
            MeshToStepProtocol.Event.Malformed("not json at all"),
            MeshToStepProtocol.parse("not json at all"),
        )
        // JSON that is not an engine event is malformed for this protocol, not a crash.
        assertTrue(MeshToStepProtocol.parse("""{"something":1}""") is MeshToStepProtocol.Event.Malformed)
    }

    @Test
    fun fractionsAreClampedAndMissingNumbersKeepTheLastValue() {
        assertEquals(0.5f, MeshToStepProtocol.clampFraction(0.5), 0f)
        assertEquals(1f, MeshToStepProtocol.clampFraction(1.4), 0f)
        assertEquals(0f, MeshToStepProtocol.clampFraction(-2.0), 0f)
        assertEquals(0.3f, MeshToStepProtocol.clampFraction(Double.NaN, previous = 0.3f), 0f)
        val stage = MeshToStepProtocol.parse("""{"event":"stage","stage":"Writing STEP"}""")
        assertEquals(0f, (stage as MeshToStepProtocol.Event.Stage).fraction, 0f)
    }

    @Test
    fun presetsMapToTheEnginesOwnArguments() {
        assertEquals("auto", MeshToStepProtocol.presetArgument(MeshToStepEngine.Preset.AUTO))
        assertEquals("cad", MeshToStepProtocol.presetArgument(MeshToStepEngine.Preset.CAD))
        assertEquals("smooth", MeshToStepProtocol.presetArgument(MeshToStepEngine.Preset.SMOOTH))
        assertEquals("precise", MeshToStepProtocol.presetArgument(MeshToStepEngine.Preset.PRECISE))
        assertEquals("exact", MeshToStepProtocol.presetArgument(MeshToStepEngine.Preset.EXACT))
    }

    @Test
    fun qualityIsClippedAndPrintedWithoutExponent() {
        assertEquals("0.7", MeshToStepProtocol.qualityArgument(0.7f))
        assertEquals("1", MeshToStepProtocol.qualityArgument(1f))
        assertEquals("0", MeshToStepProtocol.qualityArgument(-3f))
        assertEquals("1", MeshToStepProtocol.qualityArgument(4f))
        assertEquals("0.25", MeshToStepProtocol.qualityArgument(0.25f))
    }

    @Test
    fun conversionArgumentsCarryTheRequestedPresetQualityUnitAndJsonMode() {
        val input = File("/tmp/in.stl")
        val output = File("/tmp/out.step")
        val arguments = MeshToStepProtocol.convertArguments(request, input, output)
        assertEquals(
            listOf(
                "convert",
                input.absolutePath,
                "-o",
                output.absolutePath,
                "--preset", "auto",
                "--quality", "0.7",
                "--unit", "mm",
                "--json",
            ),
            arguments,
        )
        assertFalse("no tolerance unless asked", arguments.contains("--tolerance"))
    }

    @Test
    fun toleranceAndUnitsAreForwardedOnlyWhenSet() {
        val input = File("/tmp/in.stl")
        val output = File("/tmp/out.step")
        val arguments = MeshToStepProtocol.convertArguments(
            request.copy(
                preset = MeshToStepEngine.Preset.CAD,
                quality = 0.9f,
                unit = "in",
                tolerance = 0.05,
            ),
            input,
            output,
        )
        assertTrue(arguments.windowed(2).contains(listOf("--preset", "cad")))
        assertTrue(arguments.windowed(2).contains(listOf("--quality", "0.9")))
        assertTrue(arguments.windowed(2).contains(listOf("--unit", "in")))
        assertTrue(arguments.windowed(2).contains(listOf("--tolerance", "0.05")))
    }

    @Test
    fun cacheNamesAreSafeAndAlwaysStepFiles() {
        assertEquals("cantilever01-bottom-cad.step", MeshToStepProtocol.outputName(File("cantilever01-bottom.stl")))
        assertEquals("my_part__v2-cad.step", MeshToStepProtocol.outputName(File("my part (v2).stl")))
        assertEquals("model-cad.step", MeshToStepProtocol.outputName(File(".stl")))
        assertEquals("model-cad.step", MeshToStepProtocol.outputName(File("...")))
        assertFalse(MeshToStepProtocol.outputName(File("a/b/c.stl")).contains('/'))
    }

    @Test
    fun warningsTurnTheResultsAdvisoryFindingsIntoPlainSentences() {
        val invalid = JSONObject(
            """{"valid":false,"mesh_check":{"issues":[{"code":"open_shell","problem":"One shell is open."}]},"fidelity":{"ok":false,"tolerance":0.02}}""",
        )
        val warnings = MeshToStepProtocol.warnings(invalid)
        assertTrue(warnings.any { it.contains("could not validate") })
        assertTrue(warnings.any { it.contains("One shell is open.") })
        assertTrue(warnings.any { it.contains("follow the mesh loosely") })

        val clean = JSONObject("""{"valid":true,"mesh_check":{"issues":[]},"fidelity":{"ok":true}}""")
        assertTrue(MeshToStepProtocol.warnings(clean).isEmpty())

        // The engine's read-back checks are advisory results too; an unreadable STEP must
        // surface as a warning even when "valid" itself is true.
        val unchecked = JSONObject(
            """{"valid":true,"checks":[{"code":"unreadable","level":"error","message":"The saved STEP file couldn't be opened again."}]}""",
        )
        assertTrue(
            MeshToStepProtocol.warnings(unchecked).any { it.contains("couldn't be opened again") },
        )
    }

    @Test
    fun errorSentencesKeepTheEnginesMessageAndReason() {
        val error = MeshToStepProtocol.Event.Error("Could not write the STEP.", "The disk is full.", null)
        assertEquals("Could not write the STEP. The disk is full.", MeshToStepProtocol.errorSentence(error))
        val bare = MeshToStepProtocol.Event.Error("Could not write the STEP.", null, null)
        assertEquals("Could not write the STEP.", MeshToStepProtocol.errorSentence(bare))
    }
}