package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.supportpaint.SupportPaintState
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThreeMfModelParserTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun readsBackWhatTheWriterStaged() {
        val file = File(folder.root, "round-trip.3mf")
        PaintedMeshWriter.write(
            quadMesh(),
            SupportPaintState(enforcerTriangles = setOf(0), blockerTriangles = setOf(1)),
            file,
        )

        val parsed = ThreeMfModelParser.parse(file, "round-trip.3mf", maxTriangles = 1_000)

        assertEquals(2, parsed.mesh.triangleCount)
        assertEquals(setOf(0), parsed.paint.enforcerTriangles)
        assertEquals(setOf(1), parsed.paint.blockerTriangles)
        assertEquals(0f, parsed.mesh.bounds.minX)
        assertEquals(10f, parsed.mesh.bounds.maxX)
        assertEquals(10f, parsed.mesh.bounds.maxY)
        val vertices = parsed.mesh.interleavedVertices
        assertEquals(36, vertices.size)
        // First corner of the first triangle, then its face normal.
        assertEquals(0f, vertices[0])
        assertEquals(0f, vertices[1])
        assertEquals(0f, vertices[2])
        assertEquals(0f, vertices[3])
        assertEquals(0f, vertices[4])
        assertEquals(1f, vertices[5])
    }

    @Test
    fun appliesUnitAndBuildTransform() {
        val file = File(folder.root, "transform.3mf")
        writeThreeMf(
            file,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <model unit="inch" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
             <resources>
              <object id="1" type="model">
               <mesh>
                <vertices>
                 <vertex x="0" y="0" z="0"/>
                 <vertex x="1" y="0" z="0"/>
                 <vertex x="0" y="1" z="0"/>
                </vertices>
                <triangles>
                 <triangle v1="0" v2="1" v3="2"/>
                </triangles>
               </mesh>
              </object>
             </resources>
             <build><item objectid="1" transform="2 0 0 0 2 0 0 0 2 5 0 0"/></build>
            </model>
            """.trimIndent(),
        )

        val parsed = ThreeMfModelParser.parse(file, "transform.3mf", maxTriangles = 1_000)

        assertEquals(1, parsed.mesh.triangleCount)
        // 1 inch = 25.4 mm, doubled by the transform, then moved 5 mm along X.
        val vertices = parsed.mesh.interleavedVertices
        assertEquals(5f, vertices[0], 1e-4f)
        assertEquals(0f, vertices[1], 1e-4f)
        assertEquals(5f + 2f * 25.4f, vertices[6], 1e-3f)
        assertEquals(2f * 25.4f, vertices[13], 1e-3f)
        assertTrue(parsed.paint.isEmpty)
    }

    @Test
    fun skipsPaintThatSplitsItsFacet() {
        val file = File(folder.root, "split.3mf")
        writeThreeMf(
            file,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
                   xmlns:slic3rpe="http://schemas.slic3r.org/3mf/2017/06">
             <resources>
              <object id="1" type="model">
               <mesh>
                <vertices>
                 <vertex x="0" y="0" z="0"/>
                 <vertex x="1" y="0" z="0"/>
                 <vertex x="0" y="1" z="0"/>
                </vertices>
                <triangles>
                 <triangle v1="0" v2="1" v3="2" slic3rpe:custom_supports="84"/>
                </triangles>
               </mesh>
              </object>
             </resources>
             <build><item objectid="1"/></build>
            </model>
            """.trimIndent(),
        )

        val parsed = ThreeMfModelParser.parse(file, "split.3mf", maxTriangles = 1_000)

        assertEquals(1, parsed.mesh.triangleCount)
        assertTrue("a split facet stays unpainted rather than being guessed", parsed.paint.isEmpty)
    }

    @Test
    fun rejectsAPackageWithoutAModelPart() {
        val file = File(folder.root, "empty.3mf")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
        }

        val failure = runCatching {
            ThreeMfModelParser.parse(file, "empty.3mf", maxTriangles = 1_000)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun writeThreeMf(file: File, model: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zip.write(model.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun quadMesh(): StlMesh = StlMesh(
        displayName = "quad",
        interleavedVertices = VertexData.fromArray(quadVertices()),
        triangleCount = 2,
        bounds = MeshBounds(0f, 0f, 0f, 10f, 10f, 0f),
    )

    private fun quadVertices(): FloatArray {
        val corners = listOf(
            0f, 0f, 0f,
            10f, 0f, 0f,
            0f, 10f, 0f,
            10f, 0f, 0f,
            10f, 10f, 0f,
            0f, 10f, 0f,
        )
        val vertices = FloatArray(36)
        for (corner in 0 until 6) {
            val base = corner * 6
            vertices[base] = corners[corner * 3]
            vertices[base + 1] = corners[corner * 3 + 1]
            vertices[base + 2] = corners[corner * 3 + 2]
            vertices[base + 5] = 1f
        }
        return vertices
    }
}
