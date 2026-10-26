package com.tomppi.enderslicer.solidcraft

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The document's live-view handoff: the tessellation is one mesh per body, an edit patches only
 * the bodies it named, and the plate draws one object per body.
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
    fun theComposerKeepsOneMeshPerBodyInTheEnginesOrder() {
        val composer = CadBodyMeshes()
        composer.put("Body1", triangle(x = 0f))
        composer.put("Body2", triangle(x = 20f))
        assertEquals(listOf("Body1", "Body2"), composer.names())
        assertEquals(listOf("Body1" to 0, "Body2" to 1), composer.entries().mapIndexed { index, pair -> pair.first to index })
        assertEquals(2, composer.count)
        assertEquals(2, composer.triangleCount())
        assertEquals("Body2", composer.entries()[1].first)
        assertEquals(1, composer.mesh("Body2")?.triangleCount)
        assertEquals(0f, composer.mesh("Body1")?.bounds?.maxZ)
    }

    @Test
    fun anEmptyComposerHasNoTriangles() {
        val composer = CadBodyMeshes()
        assertEquals(0, composer.count)
        assertEquals(0, composer.triangleCount())
        assertTrue(composer.entries().isEmpty())
    }

    @Test
    fun anIncrementalEditPatchesOnlyTheNamedBodyAndDropsTheRemovedOne() {
        val composer = CadBodyMeshes()
        composer.put("Body1", triangle(x = 0f))
        composer.put("Body2", triangle(x = 20f))
        assertEquals(2, composer.triangleCount())

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
        assertEquals(1, composer.triangleCount())
        assertEquals(5f, composer.mesh("Body1")?.bounds?.minZ)
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
    fun theDocumentNamesItsOwnObjectsAndBodies() {
        val live = CadDocument(
            name = "Bracket",
            designPath = "/ws/bracket.solidcraft",
            parts = listOf(
                CadPart("Case", "cad:Case"),
                CadPart("Lid", "cad:Lid"),
            ),
        )
        assertEquals("Case", live.bodyOf("cad:Case"))
        assertEquals(null, live.bodyOf("stl:Case"))
        assertTrue(live.isCadObject("cad:Lid"))
        assertFalse(live.isCadObject("stl:Lid"))
        assertTrue(live.isDocumentObject("cad:Lid"))
        assertFalse(live.isDocumentObject("stl:Lid"))
        assertEquals(setOf("cad:Case", "cad:Lid"), live.objectIds())

        val converted = live.copy(
            parts = emptyList(),
            converted = true,
            convertedParts = listOf(
                CadPart("Case", "stl:Case"),
                CadPart("Lid", "stl:Lid"),
            ),
        )
        assertTrue(converted.isDocumentObject("stl:Case"))
        assertFalse(converted.isDocumentObject("cad:Case"))
        assertEquals("stl:Lid", converted.convertedObjectOf("Lid"))
        assertEquals("stl:Case", converted.convertedObjectOf("Case"))
        // A converted object draws a body but is not a live part of the document.
        assertEquals(null, converted.bodyOf("stl:Case"))
    }

    @Test
    fun theWorkspaceKeepsEveryGeneratedFileInsideTheEnginesAllowedFolder() {
        val root = Files.createTempDirectory("cad-workspace").toFile()
        try {
            val workspace = CadWorkspace(root).ensureDirectories()
            val document = "../step out/../../evil"
            val design = workspace.designFile(document)
            val body = workspace.bodyFile(document, "../Body 1")
            val converted = workspace.convertedFile(document, "../Body 1")
            val print = workspace.printFile(document, 1234L)
            for (file in listOf(design, body, converted, print)) {
                assertTrue(
                    file.canonicalPath + " escaped " + root.canonicalPath,
                    file.canonicalPath.startsWith(root.canonicalPath + File.separator),
                )
            }
            assertTrue(workspace.designFile("Part") == workspace.designFile("part"))
            // The converted STLs are their own folder: deleting a design must not take them.
            assertEquals("converted", converted.parentFile?.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theDocumentRejectsNoFilesystemPathsItselfButTheWorkspaceNamesThem() {
        val workspace = CadWorkspace(File("/data/files/solidcraft/workspace"))
        assertTrue(workspace.designFile("A B/C").name.endsWith(".solidcraft"))
        assertTrue(workspace.importFile("../../etc/passwd").name.endsWith("etc_passwd") ||
            workspace.importFile("../../etc/passwd").name.contains("passwd"))
        assertNotNull(workspace.bodyFile("doc", "Body1"))
        assertTrue(workspace.convertedFile("doc", "Body1").parentFile?.name == "converted")
    }
}
