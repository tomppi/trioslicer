package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.solidcraft.CadDocument
import com.tomppi.enderslicer.solidcraft.CadPart
import com.tomppi.enderslicer.solidcraft.CadSliceGate
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
 * The plate's DELETE (long-press card / model tools) is also a statement about a live CAD
 * document: its plate objects are the document's only owner on the plate. Deleting the last
 * live part must end the document exactly like closing it, or the CAD slice gate keeps
 * refusing Slice with nothing live on the plate. Deleting one part of a multi-part document
 * keeps the document live (and refused until the human converts it).
 */
class CadPlateDeleteTest {

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

    private fun mesh(): StlMesh = StlMesh(
        displayName = "part.stl",
        interleavedVertices = VertexData.fromArray(FloatArray(18)),
        triangleCount = 1,
        bounds = MeshBounds(0f, 0f, 0f, 10f, 10f, 10f),
    )

    private fun objectFor(id: String, name: String = id): PlateObject = PlateObject(
        id = id,
        name = name,
        sourceMesh = mesh(),
        mesh = mesh(),
        sourcePath = null,
        placement = ModelPlacement(centerXmm = 5.0, centerYmm = 5.0, baseZmm = 0.0),
    )

    private fun liveDocument(vararg objectIds: String): CadDocument = CadDocument(
        name = "case",
        designPath = "/tmp/case.solidcraft",
        parts = objectIds.mapIndexed { index, id -> CadPart(body = "body-" + index, objectId = id) },
        bodyCount = objectIds.size,
    )

    @Test
    fun deletingTheLastLiveCadPartEndsTheDocumentAndSliceOpens() {
        val state = MainUiState(
            printer = printer,
            models = listOf(objectFor("cad:body-0")),
            cadDocument = liveDocument("cad:body-0"),
        )
        assertEquals(CadSliceGate.NEEDS_STL, state.cadSliceBlockedReason)

        val removal = state.cadRemovalFor("cad:body-0")
        assertNotNull("the plate object belongs to the live document", removal)
        assertTrue(removal!!.endsDocument)
        assertNull(removal.document)

        // What removeModel commits for the end case: the object and the document both go.
        val after = state.withoutModel("cad:body-0").copy(cadDocument = null)
        assertNull(after.cadSliceBlockedReason)
        assertFalse(after.cadNeedsConversion)
        assertNull(after.cadConvertLabel)

        // The acceptance sequence: import an STL afterwards and Slice must be unlocked.
        val imported = after.withModels(listOf(objectFor("imported-stl", "sphere.stl")))
        assertNull(imported.cadSliceBlockedReason)
    }

    @Test
    fun deletingOnePartOfAMultiPartDocumentKeepsTheDocumentLive() {
        val state = MainUiState(
            printer = printer,
            models = listOf(objectFor("cad:a"), objectFor("cad:b")),
            cadDocument = liveDocument("cad:a", "cad:b"),
        )
        val removal = state.cadRemovalFor("cad:a")
        assertNotNull(removal)
        assertFalse(removal!!.endsDocument)
        assertEquals(listOf("cad:b"), removal.document?.parts?.map { it.objectId })

        val after = state.withoutModel("cad:a").copy(cadDocument = removal.document)
        // The refusal is still correct: a live document remains on the plate.
        assertEquals(CadSliceGate.NEEDS_STL, after.cadSliceBlockedReason)
        assertTrue(after.cadNeedsConversion)
        assertEquals("Convert to STL", after.cadConvertLabel)

        // Deleting the remaining part now ends the document.
        val last = after.cadRemovalFor("cad:b")
        assertNotNull(last)
        assertTrue(last!!.endsDocument)
        val ended = after.withoutModel("cad:b").copy(cadDocument = null)
        assertNull(ended.cadSliceBlockedReason)
    }

    @Test
    fun anImportedMeshOrAConvertedPartDoesNotTouchTheLiveDocument() {
        val live = MainUiState(
            printer = printer,
            models = listOf(objectFor("cad:a"), objectFor("imported-stl")),
            cadDocument = liveDocument("cad:a"),
        )
        assertNull("an imported mesh is not the document's object", live.cadRemovalFor("imported-stl"))

        val converted = live.copy(
            cadDocument = liveDocument("cad:a").copy(
                converted = true,
                convertedParts = listOf(CadPart(body = "a", objectId = "stl:a")),
                parts = emptyList(),
            ),
        )
        assertNull("a converted STL is an ordinary mesh", converted.cadRemovalFor("stl:a"))
        assertNull("a converted document does not gate Slice", converted.cadSliceBlockedReason)
    }

    @Test
    fun theExistingPlateWidePathsStillEndTheDocument() {
        // Clear plate and close both go through cadDocument = null in the ViewModel; convert
        // keeps a converted document (Sliceable) instead of detaching it.
        val source = source("ui/MainViewModel.kt")
        val clearStart = source.indexOf("fun clearBuildPlate()")
        val clearBlock = source.substring(clearStart, source.indexOf("fun ", clearStart + 10))
        assertTrue(clearBlock.contains("cadDocument = null"))

        val closeStart = source.indexOf("fun closeCadDocument()")
        val closeBlock = source.substring(closeStart, source.indexOf("fun ", closeStart + 10))
        assertTrue(closeBlock.contains("cadDocument = null"))

        val convertStart = source.indexOf("fun convertCadToStl()")
        val convertBlock = source.substring(convertStart, source.indexOf("fun ", convertStart + 10))
        assertTrue(convertBlock.contains("cadDocument = documentAfter"))
    }

    @Test
    fun theRemovePathUsesTheDocumentAwareHelperAndNamesItsSentences() {
        val source = source("ui/MainViewModel.kt")
        val removeStart = source.indexOf("fun removeModel(id: String)")
        val removeBlock = source.substring(removeStart, source.indexOf("// --- Splitting", removeStart))
        assertTrue(removeBlock.contains("state.cadRemovalFor(id)"))
        assertTrue(removeBlock.contains("endsLiveDocument"))
        assertTrue(removeBlock.contains("base.copy(cadDocument = null)"))
        assertTrue(removeBlock.contains("base.copy(cadDocument = cadRemoval.document)"))
        assertTrue(
            "the end sentence names the plate and Slice",
            removeBlock.contains("the CAD document is no longer on the plate"),
        )
        assertTrue(
            "the multi-part sentence names the remaining parts and the missing conversion",
            removeBlock.contains("the CAD document is still live with"),
        )
        assertTrue(removeBlock.contains("convert it to STL before Slice can run"))

        val state = source("ui/MainUiState.kt")
        assertTrue(state.contains("fun cadRemovalFor(id: String)"))
        assertTrue(state.contains("data class CadPlateRemoval"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            java.io.File("src/main/java/com/tomppi/enderslicer/" + relative),
            java.io.File("app/src/main/java/com/tomppi/enderslicer/" + relative),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("Unable to locate source file for " + relative)
    }
}
