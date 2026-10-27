package com.tomppi.enderslicer.solidcraft

import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.StlMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plate's per-part half of a live CAD document: one object per engine body, an edit patches
 * only the part it named, and the human's move of a part survives the repaint that follows.
 */
class CadPartPublishTest {

    private fun plan(
        bodies: List<Pair<String, StlMesh>>,
        models: List<PlateObject> = emptyList(),
        previousParts: List<CadPart> = emptyList(),
        legacyObjectId: String? = null,
    ): CadPartPlan = CadPartPublish.plan(
        bodies = bodies,
        models = models,
        previousParts = previousParts,
        displayPath = ::cadTestDisplayPath,
        legacyObjectId = legacyObjectId,
    )

    private fun imported(id: String = "import-1", name: String = "Imported"): PlateObject {
        val mesh = cadTestMesh(name)
        return PlateObject(
            id = id,
            name = name,
            sourceMesh = mesh,
            mesh = mesh,
            sourcePath = "/ws/imports/" + name + ".stl",
            placement = CadPartPublish.documentCoordinates(mesh),
        )
    }

    @Test
    fun onePlateObjectPerBodyWithTheEnginesNamesAndIds() {
        val result = plan(listOf("Case" to cadTestMesh("case"), "Lid" to cadTestMesh("lid")))
        assertEquals(2, result.models.size)
        assertEquals(listOf("Case", "Lid"), result.models.map { it.name })
        assertEquals(listOf("cad:Case", "cad:Lid"), result.models.map { it.id })
        assertEquals(
            listOf(
                CadPart(body = "Case", objectId = "cad:Case"),
                CadPart(body = "Lid", objectId = "cad:Lid"),
            ),
            result.parts,
        )
        assertEquals(
            listOf(cadTestDisplayPath("Case"), cadTestDisplayPath("Lid")),
            result.models.map { it.sourcePath },
        )
        assertEquals(2, result.triangleCount)
        // The document's own coordinates are the identity: the design's arrangement is kept.
        assertEquals(
            CadPartPublish.documentCoordinates(cadTestMesh("case")),
            result.models[0].placement,
        )
        assertTrue(result.paintDropped.isEmpty())
    }

    @Test
    fun anEditPatchesOnlyTheNamedPartAndKeepsTheOtherObjects() {
        val first = plan(listOf("Case" to cadTestMesh("case"), "Lid" to cadTestMesh("lid")))
        val lid = first.models.first { it.name == "Lid" }
        val movedLid = lid.withPlacement(
            lid.placement.copy(
                centerXmm = lid.placement.centerXmm + 20.0,
                centerYmm = lid.placement.centerYmm + 5.0,
            ),
        )
        val plate = first.models.map { if (it.id == lid.id) movedLid else it }
        // The edit named the lid: the case's mesh is the very same instance, the lid's is new.
        val caseMesh = first.models.first { it.name == "Case" }.sourceMesh
        val lidMesh = cadTestMesh("lid", x = 1f)
        val second = plan(
            listOf("Case" to caseMesh, "Lid" to lidMesh),
            models = plate,
            previousParts = first.parts,
        )
        val caseAfter = second.models.first { it.name == "Case" }
        val lidAfter = second.models.first { it.name == "Lid" }
        // The named part is the new tessellation, at the place the human gave it.
        assertSame(lidMesh, lidAfter.sourceMesh)
        assertEquals(lidMesh.bounds.centerX.toDouble() + 20.0, lidAfter.placement.centerXmm, 1e-6)
        assertEquals(lidMesh.bounds.centerY.toDouble() + 5.0, lidAfter.placement.centerYmm, 1e-6)
        // The part the edit did not name is the very same plate object: nothing re-transformed it.
        assertSame(caseMesh, caseAfter.sourceMesh)
        assertSame(first.models.first { it.name == "Case" }, caseAfter)
        assertEquals(listOf("cad:Case", "cad:Lid"), second.models.map { it.id })
    }

    @Test
    fun aPartWithPaintKeepsItWhileItsTriangleCountDoesNotChange() {
        val first = plan(listOf("Case" to cadTestMesh("case")))
        val painted = first.models.map {
            it.copy(supportPaint = SupportPaintState(enforcerTriangles = setOf(0)))
        }
        val same = plan(
            listOf("Case" to cadTestMesh("case", x = 1f)),
            models = painted,
            previousParts = first.parts,
        )
        assertEquals(setOf(0), same.models[0].supportPaint.enforcerTriangles)
        assertTrue(same.paintDropped.isEmpty())
        val changed = plan(
            listOf("Case" to cadTestMesh("case", triangles = 2)),
            models = painted,
            previousParts = first.parts,
        )
        assertTrue(changed.models[0].supportPaint.isEmpty)
        assertEquals(listOf("Case"), changed.paintDropped)
    }

    @Test
    fun aNewBodyIsAppendedAndARemovedBodyLosesItsObject() {
        val first = plan(
            listOf("Case" to cadTestMesh("case"), "Lid" to cadTestMesh("lid")),
            models = listOf(imported()),
        )
        val second = plan(
            listOf("Lid" to cadTestMesh("lid"), "Screw" to cadTestMesh("screw")),
            models = first.models,
            previousParts = first.parts,
        )
        assertEquals(listOf("import-1", "cad:Lid", "cad:Screw"), second.models.map { it.id })
        assertEquals(
            listOf(CadPart("Lid", "cad:Lid"), CadPart("Screw", "cad:Screw")),
            second.parts,
        )
    }

    @Test
    fun theLegacyWholeDocumentObjectIsReplacedByThePartsInItsPlace() {
        val importedObject = imported()
        val legacy = PlateObject(
            id = "legacy-doc",
            name = "Whole document",
            sourceMesh = cadTestMesh("whole"),
            mesh = cadTestMesh("whole"),
            sourcePath = "/ws/models/solidcraft-design-display.stl",
            placement = CadPartPublish.documentCoordinates(cadTestMesh("whole")),
        )
        val result = plan(
            listOf("Case" to cadTestMesh("case"), "Lid" to cadTestMesh("lid")),
            models = listOf(importedObject, legacy),
            legacyObjectId = "legacy-doc",
        )
        assertEquals(listOf("import-1", "cad:Case", "cad:Lid"), result.models.map { it.id })
        assertSame(importedObject, result.models[0])
    }

    @Test
    fun anImportedObjectThatHoldsABodyIdMakesThePartIdUnique() {
        val collision = imported(id = "cad:Case", name = "Collision")
        val result = plan(
            listOf("Case" to cadTestMesh("case"), "Lid" to cadTestMesh("lid")),
            models = listOf(collision),
        )
        assertEquals(listOf("cad:Case", "cad:Case#2", "cad:Lid"), result.models.map { it.id })
        assertEquals("cad:Case#2", result.parts.first().objectId)
    }

    @Test
    fun aPartWithoutTrianglesDoesNotBecomeAnObject() {
        val result = plan(
            listOf("Empty" to cadTestMesh("empty", triangles = 0), "Case" to cadTestMesh("case")),
        )
        assertEquals(listOf("cad:Case"), result.models.map { it.id })
        assertEquals(1, result.triangleCount)
    }

    @Test
    fun thePlacementCarryNeverRecentreAPartTheHumanMoved() {
        val mesh = cadTestMesh("case")
        val moved = CadPartPublish.documentCoordinates(mesh).copy(
            centerXmm = mesh.bounds.centerX.toDouble() + 30.0,
            baseZmm = mesh.bounds.minZ.toDouble() + 4.0,
        )
        val next = cadTestMesh("case", x = 5f)
        val carried = CadPartPublish.carryPlacement(moved, mesh, next)
        assertEquals(next.bounds.centerX.toDouble() + 30.0, carried.centerXmm, 1e-6)
        assertEquals(mesh.bounds.minZ.toDouble() + 4.0, carried.baseZmm, 1e-6)
        assertNotEquals(moved.centerXmm, carried.centerXmm)
    }

    @Test
    fun aRenamedBodyKeepsItsPlacementAndPaintAndSaysItWasRenamed() {
        val first = plan(listOf("Case" to cadTestMesh("case"), "Lid" to cadTestMesh("lid")))
        val lid = first.models.first { it.name == "Lid" }
        val moved = lid.withPlacement(
            lid.placement.copy(
                centerXmm = lid.placement.centerXmm + 20.0,
                centerYmm = lid.placement.centerYmm + 5.0,
            ),
        ).copy(supportPaint = SupportPaintState(enforcerTriangles = setOf(0)))
        val plate = first.models.map { if (it.id == lid.id) moved else it }
        val caseMesh = first.models.first { it.name == "Case" }.sourceMesh
        // The engine renamed Lid to Cover; the shape did not change (same triangle count).
        val renamed = plan(
            listOf("Case" to caseMesh, "Cover" to cadTestMesh("cover")),
            models = plate,
            previousParts = first.parts,
        )
        assertEquals(listOf("Case", "Cover"), renamed.models.map { it.name })
        val cover = renamed.models.first { it.name == "Cover" }
        // The human's arrangement and paint survive the rename: the name is the engine's,
        // the place is the user's.
        assertEquals(lid.placement.centerXmm + 20.0, cover.placement.centerXmm, 1e-6)
        assertEquals(lid.placement.centerYmm + 5.0, cover.placement.centerYmm, 1e-6)
        assertEquals(setOf(0), cover.supportPaint.enforcerTriangles)
        assertEquals(listOf("Lid" to "Cover"), renamed.renamed)
        // The old object is gone, not kept beside the renamed one.
        assertEquals(2, renamed.models.size)
        assertEquals(listOf("cad:Case", "cad:Cover"), renamed.models.map { it.id })
    }
}
