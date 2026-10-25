package com.tomppi.enderslicer.solidcraft

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The display/print handoff: the document's tessellation is one mesh per body, an edit patches
 * only the bodies it named, and the Slice export is a deliberate artifact that may be reused
 * only while the document has not moved on.
 */
class CadDocumentTest {

    private fun triangle(x: Float = 0f, z: Float = 0f): StlMesh {
        val floats = floatArrayOf(
            x, 0f, z, 0f, 0f, 1f,
            x + 10f, 0f, z, 0f, 0f, 1f,
            x, 10f, z, 0f, 0f, 1f,
        )
        return StlMesh(
            displayName = "body",
            interleavedVertices = VertexData.fromArray(floats),
            triangleCount = 1,
            bounds = MeshBounds(x, 0f, z, x + 10f, 10f, z),
        )
    }

    @Test
    fun aOneBodyDocumentBorrowsTheBodysOwnVertices() {
        val composer = CadBodyMeshes()
        val body = triangle()
        composer.put("Body1", body)
        val composed = composer.compose("bracket")!!
        assertEquals("bracket", composed.displayName)
        assertSame(body.interleavedVertices, composed.interleavedVertices)
    }

    @Test
    fun composingTwoBodiesConcatenatesThemInBodyOrder() {
        val composer = CadBodyMeshes()
        composer.put("Body1", triangle(x = 0f))
        composer.put("Body2", triangle(x = 20f))
        val composed = composer.compose("bracket")!!
        assertEquals(2, composed.triangleCount)
        assertEquals(2, composer.count)
        assertEquals(0f, composed.bounds.minX)
        assertEquals(30f, composed.bounds.maxX)
    }

    @Test
    fun anIncrementalEditPatchesOnlyTheNamedBodyAndDropsTheRemovedOne() {
        val composer = CadBodyMeshes()
        composer.put("Body1", triangle(x = 0f))
        composer.put("Body2", triangle(x = 20f))
        val before = composer.compose("part")!!
        assertEquals(2, before.triangleCount)

        val delta = DocumentDelta(
            tool = "execute",
            command = "solid.fillet",
            changedBodies = listOf("Body1"),
            removedBodies = listOf("Body2"),
        )
        val replacement = triangle(x = 0f, z = 5f)
        val applied = CadLiveUpdate.applyIncremental(composer, delta) { replacement }
        assertTrue(applied)
        assertEquals(listOf("Body1"), composer.names())
        val after = composer.compose("part")!!
        assertEquals(1, after.triangleCount)
        assertEquals(5f, after.bounds.minZ)
    }

    @Test
    fun anIncrementalEditThatCannotFindItsMeshFallsBackByReportingFalse() {
        val composer = CadBodyMeshes()
        composer.put("Body1", triangle())
        val delta = DocumentDelta(tool = "execute", changedBodies = listOf("Body2"))
        assertFalse(CadLiveUpdate.applyIncremental(composer, delta) { null })
    }

    @Test
    fun aCreatedAndAChangedBodyAreBothRepainted() {
        val delta = DocumentDelta(
            tool = "execute",
            createdBodies = listOf("Body2"),
            changedBodies = listOf("Body1", "Body2"),
        )
        assertEquals(listOf("Body2", "Body1"), delta.repaintBodies)
        assertFalse(delta.isEmpty)
    }

    @Test
    fun aBusyEditsReportIsMergedRatherThanLost() {
        val first = DocumentDelta(tool = "execute", createdBodies = listOf("Body1"))
        val second = DocumentDelta(tool = "execute", changedBodies = listOf("Body1"), removedBodies = listOf("Body2"))
        val merged = first.mergedWith(second)
        assertTrue(merged.createdBodies.contains("Body1"))
        assertTrue(merged.changedBodies.contains("Body1"))
        assertTrue(merged.removedBodies.contains("Body2"))
    }

    @Test
    fun theWorkspaceKeepsEveryGeneratedFileInsideTheEnginesAllowedFolder() {
        val root = Files.createTempDirectory("cad-workspace").toFile()
        try {
            val workspace = CadWorkspace(root).ensureDirectories()
            val document = "../step out/../../evil"
            val design = workspace.designFile(document)
            val body = workspace.bodyFile(document, "../Body 1")
            val print = workspace.printFile(document, 1234L)
            for (file in listOf(design, body, print)) {
                assertTrue(
                    file.canonicalPath + " escaped " + root.canonicalPath,
                    file.canonicalPath.startsWith(root.canonicalPath + File.separator),
                )
            }
            assertTrue(workspace.designFile("Part") == workspace.designFile("part"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theFirstSliceExportsAndAnUnchangedDocumentReusesThatExport() {
        val exports = Files.createTempDirectory("cad-exports").toFile()
        try {
            val fresh = CadDocument(name = "Bracket", designPath = "/tmp/bracket.solidcraft")
            val first = CadSliceHandoff.decide(fresh, exports, 1_000L)
            assertTrue(first is CadSliceHandoff.Decision.Export)
            val firstPath = (first as CadSliceHandoff.Decision.Export).destination
            firstPath.writeText("stl")
            val exported = fresh.copy(
                sliceStlPath = firstPath.absolutePath,
                sliceExportRevision = fresh.displayRevision,
                sliceExportAtMillis = 1_000L,
                sliceTriangles = 1,
            )
            val again = CadSliceHandoff.decide(exported, exports, 2_000L)
            assertTrue(again is CadSliceHandoff.Decision.Reuse)
            assertEquals(firstPath, (again as CadSliceHandoff.Decision.Reuse).source)
        } finally {
            exports.deleteRecursively()
        }
    }

    @Test
    fun anEditedDocumentMustBeExportedAgainAndSoMustAReusedFileThatIsGone() {
        val exports = Files.createTempDirectory("cad-exports").toFile()
        try {
            val untouched = CadDocument(name = "Bracket", designPath = "/tmp/bracket.solidcraft")
            val export = CadSliceHandoff.decide(untouched, exports, 1_000L) as CadSliceHandoff.Decision.Export
            export.destination.writeText("stl")
            val edited = untouched.copy(
                displayRevision = 4L,
                sliceStlPath = export.destination.absolutePath,
                sliceExportRevision = 3L,
                sliceExportAtMillis = 1_000L,
            )
            assertTrue(CadSliceHandoff.decide(edited, exports, 2_000L) is CadSliceHandoff.Decision.Export)
            export.destination.delete()
            val unchanged = edited.copy(displayRevision = 3L, sliceExportRevision = 3L)
            assertTrue(CadSliceHandoff.decide(unchanged, exports, 2_000L) is CadSliceHandoff.Decision.Export)
        } finally {
            exports.deleteRecursively()
        }
    }

    @Test
    fun aDocumentWithNoPrintExportNeedsOne() {
        val document = CadDocument(name = "x", designPath = "/tmp/x.solidcraft")
        assertTrue(document.needsSliceExport)
        val exported = document.copy(
            sliceStlPath = "/tmp/x-print.stl",
            sliceExportRevision = document.displayRevision,
        )
        // The file does not exist, so even a matching revision cannot be reused.
        assertTrue(exported.needsSliceExport)
    }

    @Test
    fun theDocumentRejectsNoFilesystemPathsItselfButTheWorkspaceNamesThem() {
        val workspace = CadWorkspace(File("/data/files/solidcraft/workspace"))
        assertTrue(workspace.designFile("A B/C").name.endsWith(".solidcraft"))
        assertTrue(workspace.importFile("../../etc/passwd").name.endsWith("etc_passwd") ||
            workspace.importFile("../../etc/passwd").name.contains("passwd"))
        assertNotNull(workspace.bodyFile("doc", "Body1"))
    }
}
