package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Mesh -> STEP sheet's rules, without Android: presets and their plain-English meanings,
 * the quality/unit mapping onto the engine request, the honest cost band (mechanical seconds,
 * organic many minutes), the two refusals and the small conversion state machine.
 */
class MeshToStepLogicTest {

    private fun mesh(triangles: Int, name: String = "part.stl"): StlMesh = StlMesh(
        displayName = name,
        interleavedVertices = VertexData.fromArray(FloatArray(triangles * 18)),
        triangleCount = triangles,
        bounds = MeshBounds(0f, 0f, 0f, 10f, 10f, 10f),
    )

    private fun plateObject(
        id: String = "m1",
        triangles: Int = 1_928,
        name: String = "cantilever.stl",
    ): PlateObject = PlateObject(
        id = id,
        name = name,
        sourceMesh = mesh(triangles, name),
        mesh = mesh(triangles, name),
        sourcePath = null,
        placement = ModelPlacement(centerXmm = 5.0, centerYmm = 5.0, baseZmm = 0.0),
    )

    @Test
    fun thePresetsSayWhatTheyCostAndCadIsTheCheapest() {
        assertEquals(5, MeshToStepPreset.entries.size)
        assertEquals("Auto", MeshToStepPreset.AUTO.label)
        assertEquals("CAD", MeshToStepPreset.CAD.label)
        assertEquals("Smooth", MeshToStepPreset.SMOOTH.label)
        assertEquals("Precise", MeshToStepPreset.PRECISE.label)
        assertEquals("Exact", MeshToStepPreset.EXACT.label)
        assertTrue(
            "mechanical parts are the cheapest preset's promise",
            MeshToStepPreset.CAD.meaning.contains("Mechanical"),
        )
        assertTrue(
            "organic meshes are named with both ends of their cost at the preset",
            MeshToStepPreset.SMOOTH.meaning.contains("seconds") &&
                MeshToStepPreset.SMOOTH.meaning.contains("minutes"),
        )
        assertTrue(
            "Exact is named as tens of minutes",
            MeshToStepPreset.EXACT.meaning.contains("tens of minutes"),
        )
        assertTrue(
            "Smooth is the organic quad-remeshing path now",
            MeshToStepPreset.SMOOTH.meaning.contains("quad remeshing"),
        )
        assertNull(MeshToStepState(preset = MeshToStepPreset.AUTO).presetCaution)
        assertTrue(
            "Smooth carries its honest organic caution on the sheet",
            MeshToStepState(preset = MeshToStepPreset.SMOOTH).presetCaution
                .orEmpty()
                .contains("not the fast path"),
        )
        assertTrue(
            "a clean small organic mesh is allowed to be seconds",
            MeshToStepPreset.SMOOTH.meaning.contains("seconds for a clean small mesh"),
        )
        assertTrue(
            "Exact is a slow spelling of Precise",
            MeshToStepState(preset = MeshToStepPreset.EXACT).presetCaution
                .orEmpty()
                .contains("slowest mode"),
        )
        // CAD is the cheapest, Smooth is the slowest, and Precise/Exact are the same path.
        assertTrue(MeshToStepPreset.CAD.costFactor < MeshToStepPreset.AUTO.costFactor)
        assertTrue(MeshToStepPreset.SMOOTH.costFactor > MeshToStepPreset.EXACT.costFactor)
        assertEquals(
            MeshToStepPreset.PRECISE.costFactor,
            MeshToStepPreset.EXACT.costFactor,
            0f,
        )
    }

    @Test
    fun qualityAndUnitMapStraightOntoTheEngineRequest() {
        val sheet = MeshToStepState(
            objectId = "m1",
            sourceTriangles = 1_928,
            preset = MeshToStepPreset.CAD,
            quality = 0.7f,
            unit = MeshToStepUnit.INCH,
        )
        val request = meshToStepRequestFor(File("/tmp/source.stl"), sheet)
        assertEquals("/tmp/source.stl", request.sourceStl.path)
        assertEquals(MeshToStepPreset.CAD, request.preset)
        assertEquals(0.7f, request.quality, 0f)
        assertEquals("in", request.unit)

        // The default unit is the app's own: millimetres.
        assertEquals("mm", MeshToStepUnit.MM.value)
        assertEquals("mm", meshToStepRequestFor(File("/tmp/s.stl"), MeshToStepState()).unit)

        // Quality is a 0..1 dial and cannot leave the engine's range.
        assertEquals(1f, MeshToStepState().withQuality(2f).quality, 0f)
        assertEquals(0f, MeshToStepState().withQuality(-1f).quality, 0f)
        assertEquals(0.7f, MeshToStepState().quality, 0f)

        // A running conversion is not re-configured under the engine's feet.
        val converting = MeshToStepState(converting = true)
        assertEquals(MeshToStepPreset.AUTO, converting.withPreset(MeshToStepPreset.EXACT).preset)
    }

    @Test
    fun theCostBandIsTheSpikesHonestShape() {
        // The engine lane's mechanical test part: 1,928 triangles, 8.09 s, 31 faces (x86).
        val mechanical = MeshToStepState(
            objectId = "m1",
            sourceTriangles = 1_928,
            preset = MeshToStepPreset.CAD,
            quality = 0.7f,
        )
        assertEquals("Seconds", mechanical.costBand)
        assertTrue(
            "mechanical meshes are promised seconds in the copy",
            mechanical.costLine.contains("convert in seconds"),
        )
        assertTrue("cancel is promised live", mechanical.costLine.contains("Cancel stays live"))

        // The 225k-triangle organic benchy: many minutes (it did not finish in 25 min on x86).
        val organic = MeshToStepState(
            objectId = "m1",
            sourceTriangles = 225_154,
            preset = MeshToStepPreset.AUTO,
            quality = 0.7f,
        )
        assertEquals("Tens of minutes", organic.costBand)
        assertTrue("organic work points at Smooth", organic.costLine.contains("need Smooth"))
        assertTrue(organic.costLine.contains("Cancel stays live"))
        val exactOrganic = organic.withPreset(MeshToStepPreset.EXACT)
        assertEquals("Tens of minutes", exactOrganic.costBand)
        assertTrue(exactOrganic.costLine.contains("tens of minutes"))

        // Same mesh: finer quality never reads as cheaper.
        val coarse = MeshToStepState(sourceTriangles = 50_000, quality = 0f)
        val fine = MeshToStepState(sourceTriangles = 50_000, quality = 1f)
        assertEquals("About a minute", coarse.costBand)
        assertEquals("Several minutes", fine.costBand)
        assertTrue(
            MeshToStepState(sourceTriangles = 0).costLine.contains("Select a mesh first"),
        )

        // Smooth is honest in both directions: a clean small organic mesh can be seconds, a
        // larger one is tens of minutes, and the copy never promises speed.
        val smallOrganic = MeshToStepState(
            sourceTriangles = 1_280,
            preset = MeshToStepPreset.SMOOTH,
            quality = 0.7f,
        )
        assertEquals("Seconds to minutes", smallOrganic.costBand)
        assertTrue(smallOrganic.costLine.startsWith("On-device and CPU-heavy: ~seconds to minutes"))
        assertTrue(smallOrganic.costLine.contains("not the fast path"))
        assertTrue(smallOrganic.costLine.contains("Cancel stays live"))
        assertTrue(
            "a mushroom-sized mesh is still in the seconds-to-minutes band",
            smallOrganic.copy(sourceTriangles = 512).costBand == "Seconds to minutes",
        )
        assertEquals(
            "Minutes",
            smallOrganic.copy(sourceTriangles = 20_000).costBand,
        )
        val largerOrganic = smallOrganic.copy(sourceTriangles = 60_000)
        assertEquals("Tens of minutes", largerOrganic.costBand)
        assertTrue(largerOrganic.costLine.contains("tens of minutes"))
    }

    @Test
    fun aLiveCadDocumentsOwnPartRefusesByNameAndConvertedPartsDoNot() {
        assertTrue(
            meshToStepRefusal(null, documentLoaded = false, documentConverted = false)
                .orEmpty()
                .contains("Select a plate object first"),
        )
        assertTrue(
            meshToStepRefusal("cad:Case", documentLoaded = true, documentConverted = false)
                .orEmpty()
                .contains("Convert the CAD document first"),
        )
        assertNull(
            "a converted part is an ordinary mesh",
            meshToStepRefusal("stl:Case", documentLoaded = true, documentConverted = true),
        )
        assertNull(
            "an imported mesh beside a live document is still a mesh",
            meshToStepRefusal("imported-uuid", documentLoaded = true, documentConverted = false),
        )
    }

    @Test
    fun openingTheSheetCarriesTheSelectedMesh() {
        val opened = meshToStepOpened(plateObject())
        assertTrue(opened.open)
        assertEquals("m1", opened.objectId)
        assertEquals("cantilever.stl", opened.sourceName)
        assertEquals(1_928, opened.sourceTriangles)
        assertEquals(MeshToStepPreset.AUTO, opened.preset)
        assertNull(opened.startBlockedReason)
        assertTrue(opened.canStart)
    }

    @Test
    fun anEmptySheetRefusesStartByName() {
        val empty = meshToStepOpened(null)
        assertEquals(null, empty.objectId)
        assertFalse(empty.canStart)
        assertTrue(
            empty.startBlockedReason.orEmpty().contains("Select a plate object first"),
        )
    }

    @Test
    fun aSecondStartIsRefusedByNameWhileOneRuns() {
        val running = meshToStepOpened(plateObject()).starting()
        assertTrue(running.converting)
        assertEquals(
            "A Mesh -> STEP conversion is already running - wait for it or Cancel it.",
            running.startBlockedReason,
        )
        assertFalse(running.canStart)
        assertEquals(0f, running.progress, 0f)
    }

    @Test
    fun progressIsMonotonicAndTheResultKeepsFacesWarningsAndValidity() {
        val started = meshToStepOpened(plateObject()).starting()
        val progressed = started.progressed("tessellating", 0.4f, "reading the mesh")
        assertEquals("tessellating", progressed.stage)
        assertEquals(0.4f, progressed.progress, 0f)
        assertEquals("tessellating · 40%", progressed.progressLabel)
        assertEquals(
            "an out-of-range fraction cannot overshoot the bar",
            1f,
            started.progressed("exporting", 2f, null).progress,
            0f,
        )

        val result = MeshToStepResultInfo(
            stepPath = "/cache/mesh2step/run/part-cad.step",
            faces = 31,
            solids = 1,
            valid = true,
            warnings = listOf("closed a small seam"),
            millis = 8_090,
        )
        val done = progressed.succeeded(result)
        assertFalse(done.converting)
        assertEquals(1f, done.progress, 0f)
        assertNull(done.stage)
        assertEquals("part-cad.step", done.result?.fileName)
        assertTrue(done.result?.summaryLine.orEmpty().contains("31 faces"))
        assertTrue(done.result?.summaryLine.orEmpty().contains("valid"))
        assertTrue(done.result?.summaryLine.orEmpty().contains("8.1 s"))
        assertTrue(done.resultWarningsLine.orEmpty().contains("closed a small seam"))

        val invalid = result.copy(valid = false, warnings = listOf("open boundary at x=12"))
        val invalidDone = started.succeeded(invalid)
        assertTrue(invalidDone.result?.summaryLine.orEmpty().contains("validation issues"))
        assertTrue(invalidDone.resultWarningsLine.orEmpty().contains("x=12"))
    }

    @Test
    fun failureAndCancelLeaveAUsableSheetWithTheEnginesSentence() {
        val opened = meshToStepOpened(plateObject())
        val failed = opened.failed("the Python payload is missing")
        assertFalse(failed.converting)
        assertEquals("the Python payload is missing", failed.error)
        assertNull(failed.result)
        assertNull(failed.startBlockedReason)

        val cancelled = opened.starting().cancelled()
        assertFalse(cancelled.converting)
        assertTrue(cancelled.error.orEmpty().contains("no STEP file was written"))
        assertTrue(cancelled.canStart)

        val saved = failed.saved("cantilever-cad.step", "content://downloads/42")
        assertEquals("Saved STEP to cantilever-cad.step.", saved.savedLine)
        assertEquals("content://downloads/42", saved.savedUri)
        assertNull(saved.error)
    }

    @Test
    fun theSaveNameFollowsTheEngineLanesConvention() {
        assertEquals("cantilever-cad.step", meshToStepFileName("cantilever.stl"))
        assertEquals("Case-2-cad.step", meshToStepFileName("Case 2.stl"))
        assertEquals("already-cad.step", meshToStepFileName("already-cad.stl"))
        assertEquals("mesh-cad.step", meshToStepFileName(""))
    }

    @Test
    fun durationsReadShort() {
        assertEquals("8 ms", formatMeshToStepMillis(8))
        assertEquals("8.1 s", formatMeshToStepMillis(8_090))
        assertEquals("2 min 06 s", formatMeshToStepMillis(126_000))
    }
}
