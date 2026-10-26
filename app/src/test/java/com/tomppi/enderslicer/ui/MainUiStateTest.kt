package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.engine.LayerEvent
import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.solidcraft.CadDocument
import com.tomppi.enderslicer.solidcraft.CadPart
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every input a slice depends on - settings, engine, printer, model, placement,
 * support paint, Smart Infill package - has to drop the published artifact, so
 * the export path can never offer G-code that no longer matches the plate.
 * [MainUiState.withoutPublishedSlice] is that single place.
 */
class MainUiStateTest {
    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 220.0,
        depthMm = 220.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0,
        printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0,
        printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    private fun slicedState() = MainUiState(
        printer = printer,
        sliceResultId = "artifact-1",
        gcodePath = "/tmp/slice-results/artifact-1/model.gcode",
        gcodeComplete = true,
        baseGcodePath = "/tmp/slice-results/artifact-1/model.base.gcode",
        sliceEngine = SlicerEngine.CURA,
        layerPreview = GcodeLayerPreview(
            layers = emptyList(),
            minX = 0f,
            minY = 0f,
            maxX = 1f,
            maxY = 1f,
            minSpeedMmPerSecond = 0f,
            maxSpeedMmPerSecond = 1f,
            minLayerHeightMm = 0.2f,
            maxLayerHeightMm = 0.2f,
            totalSegmentCount = 0,
            folded = false,
        ),
        layerEvents = listOf(
            LayerEvent(
                id = "event-1",
                layerNumber = 3,
                zMm = 0.6f,
                type = LayerEventType.MESSAGE,
            ),
        ),
        estimatedPrintSeconds = 3_600,
        sliceLogPath = "/tmp/slice-results/artifact-1/slice.log",
        sliceDurationMilliseconds = 12_345,
        sliceProgressPercent = 100,
        warnings = listOf("Layer 3 is taller than the nozzle can print"),
        statusMessage = "Sliced 1.2 MB of validated G-code",
    )

    @Test
    fun theSlicedStateIsExportableToBeginWith() {
        assertTrue(slicedState().hasCurrentGcode())
    }

    @Test
    fun droppingThePublishedSliceLeavesNothingExportable() {
        val dropped = slicedState().withoutPublishedSlice("Support paint changed; slice again to export G-code")

        assertNull(dropped.sliceResultId)
        assertNull(dropped.gcodePath)
        assertNull(dropped.baseGcodePath)
        assertNull(dropped.layerPreview)
        assertNull(dropped.estimatedPrintSeconds)
        assertNull(dropped.sliceLogPath)
        assertNull(dropped.sliceDurationMilliseconds)
        assertEquals(emptyList<LayerEvent>(), dropped.layerEvents)
        assertNull(dropped.sliceEngine)
        assertFalse(dropped.gcodeComplete)
        assertFalse(dropped.hasCurrentGcode())
    }

    @Test
    fun droppingThePublishedSliceOnlyChangesTheReasonAndTheArtifacts() {
        val before = slicedState()
        val dropped = before.withoutPublishedSlice("Support paint changed; slice again to export G-code")

        assertEquals("Support paint changed; slice again to export G-code", dropped.statusMessage)
        assertEquals(before.warnings, dropped.warnings)
        assertFalse(dropped.isBusy)
        assertEquals(before.sliceProgressPercent, dropped.sliceProgressPercent)
    }

    @Test
    fun droppingThePublishedSliceKeepsTheCurrentMessageByDefault() {
        val dropped = slicedState().withoutPublishedSlice()

        assertEquals("Sliced 1.2 MB of validated G-code", dropped.statusMessage)
        assertFalse(dropped.hasCurrentGcode())
    }

    /** One triangle is a model: the plate helpers count them and read their paths. */
    private fun plateObject(id: String, path: String): PlateObject {
        val vertices = floatArrayOf(
            0f, 0f, 0f, 0f, 0f, 1f,
            1f, 0f, 0f, 0f, 0f, 1f,
            0f, 1f, 0f, 0f, 0f, 1f,
        )
        val source = StlMesh(
            displayName = path.substringAfterLast('/'),
            interleavedVertices = VertexData.fromArray(vertices),
            triangleCount = 1,
            bounds = MeshBounds(0f, 0f, 0f, 1f, 1f, 0f),
        )
        val placement = ModelPlacement.centeredOnBed(source, bedWidthMm = 220.0, bedDepthMm = 220.0)
        return PlateObject(
            id = id,
            name = source.displayName,
            sourceMesh = source,
            mesh = placement.transformed(source),
            sourcePath = path,
            placement = placement,
        )
    }

    private fun plateState(vararg objects: PlateObject) = MainUiState(
        printer = printer,
        models = objects.toList(),
        selectedModelId = objects.firstOrNull()?.id,
    )

    @Test
    fun removingAModelTakesItOffThePlate() {
        val first = plateObject("a", "/models/a.stl")
        val second = plateObject("b", "/models/b.stl")

        val next = plateState(first, second).withoutModel("a")

        assertEquals(listOf("b"), next.models.map { it.id })
        // The one that stayed keeps everything it had. This is the half that regressed: the state
        // was rebuilt from the old list, so the removed object stayed and only its file went.
        assertEquals(second.placement, next.models.single().placement)
        assertEquals(second.sourcePath, next.models.single().sourcePath)
    }

    @Test
    fun removingTheSelectedModelSelectsAnother() {
        val next = plateState(plateObject("a", "/models/a.stl"), plateObject("b", "/models/b.stl"))
            .withoutModel("a")

        assertEquals("b", next.selectedModelId)
    }

    @Test
    fun removingTheLastModelLeavesAnEmptyPlate() {
        val next = plateState(plateObject("a", "/models/a.stl")).withoutModel("a")

        assertEquals(emptyList<PlateObject>(), next.models)
        assertNull(next.selectedModelId)
        assertNull(next.mesh)
        assertNull(next.modelPath)
        assertNull(next.modelPlacement)
    }

    @Test
    fun aSecondCopyOfOneFileGetsItsOwnName() {
        val original = plateObject("a", "/models/part.stl")
        val second = original.copy(id = "b", name = "part.stl (2)")
        val state = plateState(original)

        assertEquals(
            "a name nothing on the plate uses is left alone",
            "other.stl",
            state.uniqueModelName("other.stl"),
        )
        assertEquals(
            "a second part from the same file must not share its label: PrusaSlicer's firmware " +
                "labels carry no instance id for separate objects, so Klipper would treat the two " +
                "as one cancellable object",
            "part.stl (2)",
            state.uniqueModelName("part.stl"),
        )
        assertEquals(
            "and a third",
            "part.stl (3)",
            state.withModelAdded(second).uniqueModelName("part.stl"),
        )
    }

    @Test
    fun addingAModelSelectsIt() {
        val added = plateState(plateObject("a", "/models/a.stl"))
            .withModelAdded(plateObject("b", "/models/b.stl"))

        assertEquals(listOf("a", "b"), added.models.map { it.id })
        assertEquals("b", added.selectedModelId)
    }

    /** The plate state before a new_design: the document's own object, and a published slice. */
    private fun drawnCadState(vararg otherObjects: PlateObject): MainUiState {
        val drawn = plateObject("cad-display", "/models/solidcraft-untitled-display.stl")
        return MainUiState(
            printer = printer,
            models = listOf(drawn) + otherObjects.toList(),
            selectedModelId = drawn.id,
            cadDocument = CadDocument(
                name = "Untitled",
                designPath = "/data/cad/untitled.solidcraft",
                parts = listOf(CadPart(body = "Case", objectId = drawn.id)),
                displayRevision = 7,
                triangleCount = 1,
                bodyCount = 1,
            ),
            sliceResultId = "artifact-1",
            gcodePath = "/tmp/slice-results/artifact-1/model.gcode",
            gcodeComplete = true,
        )
    }

    @Test
    fun anEmptyCadDocumentTakesThePreviousDocumentsPictureOffThePlate() {
        val before = drawnCadState()
        assertNotNull("the plate starts with the document's picture", before.mesh)
        assertTrue("and a published slice of it", before.hasCurrentGcode())

        val after = before.withoutCadBodies(
            partObjectIds = listOf("cad-display"),
            name = "Untitled",
            designPath = "/data/cad/untitled.solidcraft",
            sourcePath = null,
            editMillis = 5L,
        )

        // A new_design with no bodies must not leave the old part standing on the plate while
        // the status says the engine has nothing to draw - that reads as a crash.
        assertEquals(emptyList<PlateObject>(), after.models)
        assertNull(after.mesh)
        assertNull(after.selectedModel)
        assertNull(after.cadDocument?.displayObjectId)
        assertNull(after.cadDocument?.displayMeshPath)
        assertEquals(0, after.cadDocument?.triangleCount)
        assertEquals(0, after.cadDocument?.bodyCount)
        assertTrue("the document's parts went with its picture", after.cadDocument?.parts.orEmpty().isEmpty())
        assertFalse("nothing is converted after a new design", after.cadDocument?.converted == true)
        assertEquals(8L, after.cadDocument?.displayRevision)
        assertEquals(1, after.cadDocument?.editCount)
        assertEquals(
            "the sentence the app shows when a design has no bodies",
            "CAD document: the engine has no bodies to draw yet",
            after.statusMessage,
        )
        assertFalse("the slice was of the previous shape", after.hasCurrentGcode())
    }

    @Test
    fun anEmptyCadDocumentLeavesTheHumansOwnImportedMeshOnThePlate() {
        val after = drawnCadState(plateObject("imported", "/models/part.stl")).withoutCadBodies(
            partObjectIds = listOf("cad-display"),
            name = "Untitled",
            designPath = "/data/cad/untitled.solidcraft",
            sourcePath = null,
            editMillis = null,
        )

        assertEquals(listOf("imported"), after.models.map { it.id })
        assertEquals("imported", after.selectedModelId)
        assertNotNull("the human's mesh is not the document's picture", after.mesh)
        assertEquals(0, after.cadDocument?.editCount)
    }

    private fun cadState(converted: Boolean = false, stale: Boolean = false): MainUiState =
        plateState().copy(
            cadDocument = CadDocument(
                name = "Bracket",
                designPath = "/data/cad/bracket.solidcraft",
                converted = converted,
                convertedRevision = if (converted) 2L else -1L,
                derivedStale = stale,
                staleReason = if (stale) "the design moved on" else null,
            ),
        )

    @Test
    fun aLiveDocumentRefusesSliceByNameAndThePlateKnowsThePressThatClearsIt() {
        val live = cadState()
        assertEquals("Slice needs an STL - convert the CAD document first", live.cadSliceBlockedReason)
        assertTrue(live.cadNeedsConversion)
        assertEquals("Convert to STL", live.cadConvertLabel)
        // A mesh plate is none of those things.
        assertNull(plateState().cadSliceBlockedReason)
        assertFalse(plateState().cadNeedsConversion)
        assertNull(plateState().cadConvertLabel)
    }

    @Test
    fun aConvertedDocumentIsSliceableAndOffersConvertAgain() {
        val converted = cadState(converted = true)
        assertNull(converted.cadSliceBlockedReason)
        assertFalse(converted.cadNeedsConversion)
        assertEquals("Convert again", converted.cadConvertLabel)
    }

    @Test
    fun aStalePlateStillSaysWhyItIsRefused() {
        val stale = cadState(converted = true, stale = true)
        assertEquals("the design moved on", stale.cadSliceBlockedReason)
        assertFalse(stale.cadNeedsConversion)
    }
}
