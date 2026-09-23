package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class StlParserHardeningTest {
    @Test
    fun bareVertexTripletIsRejected() {
        val file = ascii(
            """
            vertex 0 0 0
            vertex 1 0 0
            vertex 0 1 0
            """.trimIndent(),
        )

        val error = runCatching { StlParser.parse(file, maxTriangles = 10) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException || error is IllegalStateException)
    }

    @Test
    fun verticesCannotCrossFacetBoundaries() {
        val file = ascii(
            """
            solid malformed
              facet normal 0 0 1
                outer loop
                  vertex 0 0 0
                  vertex 1 0 0
                endloop
              endfacet
              facet normal 0 0 1
                outer loop
                  vertex 0 1 0
                  vertex 0 0 1
                  vertex 1 0 1
                  vertex 0 1 1
                endloop
              endfacet
            endsolid malformed
            """.trimIndent(),
        )

        val error = runCatching { StlParser.parse(file, maxTriangles = 10) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException || error is IllegalStateException)
    }

    @Test
    fun validAsciiFacetParses() {
        val file = ascii(
            """
            solid valid
              facet normal 0 0 1
                outer loop
                  vertex 0 0 0
                  vertex 1 0 0
                  vertex 0 1 0
                endloop
              endfacet
            endsolid valid
            """.trimIndent(),
        )

        val mesh = StlParser.parse(file, maxTriangles = 10)

        assertEquals(1, mesh.triangleCount)
        assertEquals(1f, mesh.bounds.maxX)
        assertEquals(1f, mesh.bounds.maxY)
    }

    @Test
    fun asciiFacetNormalIsRebuiltFromVertices() {
        val file = ascii(
            """
            solid stale-normal
              facet normal 1 0 0
                outer loop
                  vertex 0 0 0
                  vertex 1 0 0
                  vertex 0 1 0
                endloop
              endfacet
            endsolid stale-normal
            """.trimIndent(),
        )

        val mesh = StlParser.parse(file, maxTriangles = 10)

        assertEquals(0f, mesh.interleavedVertices[3], 0.000001f)
        assertEquals(0f, mesh.interleavedVertices[4], 0.000001f)
        assertEquals(1f, mesh.interleavedVertices[5], 0.000001f)
    }

    @Test
    fun reusableBufferedWriterProducesValidBinaryStl() {
        val directory = createTempDirectory("enderslicer-stl-roundtrip").toFile()
        val target = File(directory, "roundtrip.stl")
        val vertices = floatArrayOf(
            0f, 0f, 0f, 0f, 0f, 1f,
            1f, 0f, 0f, 0f, 0f, 1f,
            0f, 1f, 0f, 0f, 0f, 1f,
        )
        val source = StlMesh(
            displayName = "roundtrip.stl",
            interleavedVertices = VertexData.fromArray(vertices),
            triangleCount = 1,
            bounds = MeshBounds(0f, 0f, 0f, 1f, 1f, 0f),
        )

        StlMeshWriter.writeBinary(source, target)
        val parsed = StlParser.parse(target, maxTriangles = 10)

        assertEquals(84L + 50L, target.length())
        assertEquals(1, parsed.triangleCount)
        assertEquals(source.bounds, parsed.bounds)
    }

    @Test
    fun binaryFacetNormalIsRebuiltFromVertices() {
        val directory = createTempDirectory("enderslicer-stl-normal").toFile()
        val target = File(directory, "stale-normal.stl")
        val vertices = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f,
            1f, 0f, 0f, 1f, 0f, 0f,
            0f, 1f, 0f, 1f, 0f, 0f,
        )
        val source = StlMesh(
            displayName = "stale-normal.stl",
            interleavedVertices = VertexData.fromArray(vertices),
            triangleCount = 1,
            bounds = MeshBounds(0f, 0f, 0f, 1f, 1f, 0f),
        )

        StlMeshWriter.writeBinary(source, target)
        val parsed = StlParser.parse(target, maxTriangles = 10)

        assertEquals(0f, parsed.interleavedVertices[3], 0.000001f)
        assertEquals(0f, parsed.interleavedVertices[4], 0.000001f)
        assertEquals(1f, parsed.interleavedVertices[5], 0.000001f)
    }

    @Test
    fun oversizedAsciiLineIsRejectedBeforeParsing() {
        val file = ascii("solid big\n" + " ".repeat(20 * 1024) + "\nendsolid big\n")

        val error = runCatching { StlParser.parse(file, maxTriangles = 10) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException || error is IllegalStateException)
        assertTrue(error?.message?.contains("safety limit") == true)
    }

    private fun ascii(content: String): File {
        val directory = createTempDirectory("enderslicer-ascii-stl").toFile()
        return File(directory, "model.stl").apply { writeText(content) }
    }
}
