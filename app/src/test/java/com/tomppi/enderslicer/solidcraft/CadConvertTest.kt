package com.tomppi.enderslicer.solidcraft

import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.StlMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The human's Convert to STL: the document's parts become the plate's own meshes, at the
 * placement the human gave them, and a second conversion replaces them in place.
 */
class CadConvertTest {

    private fun imported(): PlateObject {
        val mesh = cadTestMesh("imported")
        return PlateObject(
            id = "import-1",
            name = "Imported",
            sourceMesh = mesh,
            mesh = mesh,
            sourcePath = "/ws/imports/imported.stl",
            placement = CadPartPublish.documentCoordinates(mesh),
        )
    }

    private fun livePart(body: String, mesh: StlMesh = cadTestMesh(body)): PlateObject =
        PlateObject(
            id = "cad:" + body,
            name = body,
            sourceMesh = mesh,
            mesh = mesh,
            sourcePath = cadTestDisplayPath(body),
            placement = CadPartPublish.documentCoordinates(mesh),
        )

    private fun convert(
        parts: List<Pair<String, StlMesh>>,
        models: List<PlateObject>,
        replacing: List<CadPart>,
        legacyObjectId: String? = null,
    ): CadConvertPlan = CadConvert.plan(
        parts = parts.map { (body, mesh) ->
            CadConvertedPart(
                body = body,
                mesh = mesh,
                stlPath = "/ws/converted/design-" + body + ".stl",
            )
        },
        models = models,
        replacing = replacing,
        legacyObjectId = legacyObjectId,
    )

    @Test
    fun conversionProducesOneMeshObjectPerPartAndRemovesTheDocumentOnes() {
        val import = imported()
        val case = livePart("Case")
        val lid = livePart("Lid")
        val result = convert(
            parts = listOf("Case" to cadTestMesh("case-stl"), "Lid" to cadTestMesh("lid-stl")),
            models = listOf(import, case, lid),
            replacing = listOf(CadPart("Case", "cad:Case"), CadPart("Lid", "cad:Lid")),
        )
        assertEquals(listOf("import-1", "stl:Case", "stl:Lid"), result.models.map { it.id })
        assertEquals(listOf("Case", "Lid"), result.models.drop(1).map { it.name })
        assertEquals(
            listOf("/ws/converted/design-Case.stl", "/ws/converted/design-Lid.stl"),
            result.models.drop(1).map { it.sourcePath },
        )
        assertEquals(
            listOf(
                CadPart("Case", "stl:Case"),
                CadPart("Lid", "stl:Lid"),
            ),
            result.parts,
        )
        assertEquals(2, result.triangleCount)
        assertSame(import, result.models[0])
    }

    @Test
    fun theFirstConversionKeepsTheArrangementTheHumanGaveTheParts() {
        val lidMesh = cadTestMesh("lid")
        val lid = livePart("Lid", lidMesh)
        val moved = lid.withPlacement(
            lid.placement.copy(
                centerXmm = lid.placement.centerXmm + 25.0,
                centerYmm = lid.placement.centerYmm - 10.0,
            ),
        )
        val result = convert(
            parts = listOf("Lid" to cadTestMesh("lid-stl")),
            models = listOf(moved),
            replacing = listOf(CadPart("Lid", "cad:Lid")),
        )
        assertEquals(moved.placement, result.models[0].placement)
        assertEquals(moved.placement.centerXmm, result.models[0].mesh.bounds.centerX.toDouble(), 1e-6)
    }

    @Test
    fun convertAgainReplacesItsOwnObjectsAndKeepsTheirPlacementAndPaint() {
        val first = convert(
            parts = listOf("Case" to cadTestMesh("case"), "Lid" to cadTestMesh("lid")),
            models = listOf(imported(), livePart("Case"), livePart("Lid")),
            replacing = listOf(CadPart("Case", "cad:Case"), CadPart("Lid", "cad:Lid")),
        )
        val case = first.models.first { it.name == "Case" }
        val movedCase = case.withPlacement(
            case.placement.copy(centerXmm = case.placement.centerXmm + 12.0),
        ).copy(supportPaint = SupportPaintState(enforcerTriangles = setOf(0)))
        val plate = first.models.map { if (it.id == case.id) movedCase else it }
        val again = convert(
            parts = listOf("Case" to cadTestMesh("case", x = 3f), "Lid" to cadTestMesh("lid", x = 3f)),
            models = plate,
            replacing = first.parts,
        )
        assertEquals(listOf("import-1", "stl:Case", "stl:Lid"), again.models.map { it.id })
        assertEquals(movedCase.placement, again.models[1].placement)
        assertEquals(setOf(0), again.models[1].supportPaint.enforcerTriangles)
        assertEquals("Lid", again.models[2].name)
    }

    @Test
    fun aPartTheDocumentNoLongerHasLosesItsConvertedObject() {
        val first = convert(
            parts = listOf("Case" to cadTestMesh("case"), "Lid" to cadTestMesh("lid")),
            models = listOf(livePart("Case"), livePart("Lid")),
            replacing = listOf(CadPart("Case", "cad:Case"), CadPart("Lid", "cad:Lid")),
        )
        val again = convert(
            parts = listOf("Case" to cadTestMesh("case")),
            models = first.models,
            replacing = first.parts,
        )
        assertEquals(listOf("stl:Case"), again.models.map { it.id })
        assertEquals(listOf(CadPart("Case", "stl:Case")), again.parts)
    }

    @Test
    fun aConversionWithoutTrianglesPutsNothingOnThePlate() {
        val result = convert(
            parts = listOf("Empty" to cadTestMesh("empty", triangles = 0)),
            models = listOf(imported(), livePart("Empty")),
            replacing = listOf(CadPart("Empty", "cad:Empty")),
        )
        assertEquals(listOf("import-1"), result.models.map { it.id })
        assertTrue(result.parts.isEmpty())
        assertEquals(0, result.triangleCount)
    }

    @Test
    fun aRestored196DocumentsWholeObjectIsReplacedNotKeptBesideTheConvertedParts() {
        // A 1.9.6 descriptor draws the whole document as one plate object and has no per-part
        // mapping. Convert used to leave that object on the plate and add one STL per body
        // beside it: Slice then printed the same geometry twice.
        val legacy = PlateObject(
            id = "legacy-doc",
            name = "Whole document",
            sourceMesh = cadTestMesh("whole"),
            mesh = cadTestMesh("whole"),
            sourcePath = "/ws/display/design-display.stl",
            placement = CadPartPublish.documentCoordinates(cadTestMesh("whole")),
        )
        val result = convert(
            parts = listOf("Case" to cadTestMesh("case"), "Lid" to cadTestMesh("lid")),
            models = listOf(imported(), legacy),
            replacing = emptyList(),
            legacyObjectId = "legacy-doc",
        )
        assertEquals(listOf("import-1", "stl:Case", "stl:Lid"), result.models.map { it.id })
        assertTrue(result.models.none { it.id == "legacy-doc" })
        assertEquals(listOf("stl:Case", "stl:Lid"), result.parts.map { it.objectId })
    }
}
