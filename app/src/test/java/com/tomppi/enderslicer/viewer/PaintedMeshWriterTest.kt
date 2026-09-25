package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.supportpaint.SupportPaintState
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PaintedMeshWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun writesPaintAsTheAttributeTheEnginesRead() {
        val file = File(folder.root, "model.3mf")

        PaintedMeshWriter.write(quadMesh(), SupportPaintState(enforcerTriangles = setOf(0), blockerTriangles = setOf(1)), file)

        val parts = readParts(file)
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model"), parts.keys)
        val model = parts.getValue("3D/3dmodel.model")
        assertTrue(
            "the enforcer nibble must sit on the painted triangle",
            model.contains("v1=\"0\" v2=\"1\" v3=\"2\" slic3rpe:custom_supports=\"4\""),
        )
        assertTrue(
            "the blocker nibble must sit on its painted triangle",
            model.contains("slic3rpe:custom_supports=\"8\""),
        )
        assertEquals("the two triangles share an edge, so four corners are distinct", 4, Regex("<vertex ").findAll(model).count())
        assertEquals("two triangles are written", 2, Regex("<triangle ").findAll(model).count())
        assertTrue(model.contains("unit=\"millimeter\""))
        assertTrue(model.contains("xmlns:slic3rpe=\"http://schemas.slic3r.org/3mf/2017/06\""))
        assertTrue(model.contains("<build><item objectid=\"1\"/></build>"))
    }

    @Test
    fun unpaintedTrianglesCarryNoPaintAttribute() {
        val file = File(folder.root, "unpainted.3mf")

        PaintedMeshWriter.write(quadMesh(), SupportPaintState(), file)

        val model = readParts(file).getValue("3D/3dmodel.model")
        assertFalse(model.contains(PaintedMeshWriter.SUPPORT_PAINT_ATTRIBUTE))
        assertEquals(4, Regex("<vertex ").findAll(model).count())
    }

    @Test
    fun rejectsPaintThatDoesNotAddressTheStagedMesh() {
        val file = File(folder.root, "invalid.3mf")

        val failure = runCatching {
            PaintedMeshWriter.write(quadMesh(), SupportPaintState(enforcerTriangles = setOf(2)), file)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun rejectsNonFiniteCoordinates() {
        val vertices = quadVertices()
        vertices[7] = Float.NaN
        val mesh = quadMesh(vertices)
        val file = File(folder.root, "nan.3mf")

        val failure = runCatching { PaintedMeshWriter.write(mesh, SupportPaintState(), file) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    /** Two triangles sharing an edge, in the interleaved position+normal layout. */
    private fun quadMesh(vertices: FloatArray = quadVertices()): StlMesh = StlMesh(
        displayName = "quad",
        interleavedVertices = VertexData.fromArray(vertices),
        triangleCount = 2,
        bounds = MeshBounds(0f, 0f, 0f, 10f, 10f, 0f),
    )

    private fun quadVertices(): FloatArray {
        val vertices = FloatArray(36)
        val corners = listOf(
            0f, 0f, 0f,
            10f, 0f, 0f,
            0f, 10f, 0f,
            10f, 0f, 0f,
            10f, 10f, 0f,
            0f, 10f, 0f,
        )
        // Positions and normals are written explicitly: corner*6 holds the position
        // xyz, corner*6+3 the normal.
        for (corner in 0 until 6) {
            val base = corner * 6
            vertices[base] = corners[corner * 3]
            vertices[base + 1] = corners[corner * 3 + 1]
            vertices[base + 2] = corners[corner * 3 + 2]
            vertices[base + 3] = 0f
            vertices[base + 4] = 0f
            vertices[base + 5] = 1f
        }
        return vertices
    }

    private fun readParts(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
        }
    }
}
