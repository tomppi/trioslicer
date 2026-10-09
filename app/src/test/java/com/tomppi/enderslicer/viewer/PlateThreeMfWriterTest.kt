package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.PlateThreeMfWriter.Dialect
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlateThreeMfWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun writesEveryEntryAsItsOwnNamedObjectWithItsOwnBuildItem() {
        val file = File(folder.root, "plate.3mf")

        PlateThreeMfWriter.write(file, listOf(entry("Cube", 2), entry("Wedge & pin", 1)), Dialect.ORCA)

        val model = readParts(file).getValue("3D/3dmodel.model")
        assertEquals("one object per entry", 2, Regex("<object ").findAll(model).count())
        assertTrue(model.contains("<object id=\"1\" name=\"Cube\" type=\"model\">"))
        assertTrue("a name is XML-escaped", model.contains("name=\"Wedge &amp; pin\""))
        assertTrue("an object only exists through its item", model.contains("<build><item objectid=\"1\"/><item objectid=\"2\"/></build>"))
    }

    @Test
    fun orcaCarriesPerObjectFilamentInModelSettings() {
        val file = File(folder.root, "plate.3mf")

        PlateThreeMfWriter.write(
            file,
            listOf(entry("Cube", 2, extruder = 2), entry("Wedge", 1, extruder = 1)),
            Dialect.ORCA,
        )

        val settings = readParts(file).getValue("Metadata/model_settings.config")
        assertTrue(settings.contains("<object id=\"1\">"))
        assertTrue(settings.contains("<metadata key=\"name\" value=\"Cube\"/>"))
        assertTrue(settings.contains("<metadata key=\"extruder\" value=\"2\"/>"))
        assertTrue(settings.contains("<object id=\"2\">"))
        assertTrue(settings.contains("<metadata key=\"extruder\" value=\"1\"/>"))
    }

    @Test
    fun orcaWithoutAssignedFilamentsNeedsNoModelSettings() {
        val file = File(folder.root, "plain.3mf")

        PlateThreeMfWriter.write(file, listOf(entry("Cube", 2), entry("Wedge", 1)), Dialect.ORCA)

        // Names ride the objects themselves, so without a per-object setting the
        // plate stays the three parts the engines have always been handed.
        assertEquals(
            setOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model"),
            readParts(file).keys,
        )
    }

    @Test
    fun prusaDialectStampsTheVersionItsLegacyLoaderReads() {
        val file = File(folder.root, "plate.3mf")

        PlateThreeMfWriter.write(
            file,
            listOf(entry("Cube", 2, extruder = 2), entry("Wedge", 1)),
            Dialect.PRUSA_LEGACY,
        )

        val parts = readParts(file)
        assertTrue(
            parts.getValue("3D/3dmodel.model")
                .contains("<metadata name=\"Application\">PrusaSlicer-2.9.6</metadata>"),
        )
        val settings = parts.getValue("Metadata/Slic3r_PE_model.config")
        assertTrue(settings.contains("<object id=\"1\" instancescount=\"1\">"))
        assertTrue(settings.contains("<metadata type=\"object\" key=\"name\" value=\"Cube\"/>"))
        assertTrue(settings.contains("<metadata type=\"object\" key=\"extruder\" value=\"2\"/>"))
        assertTrue("each object needs its own volume", settings.contains("<volume firstid=\"0\" lastid=\"1\">"))
        assertTrue(settings.contains("<volume firstid=\"0\" lastid=\"0\">"))
        assertTrue(settings.contains("<metadata type=\"volume\" key=\"volume_type\" value=\"ModelPart\"/>"))
    }

    @Test
    fun paintIsRepresentedTheWayTheSingleObjectWriterRepresentsIt() {
        val mesh = mesh(2)
        val paint = SupportPaintState(enforcerTriangles = setOf(0), blockerTriangles = setOf(1))
        val plate = File(folder.root, "plate.3mf")
        val single = File(folder.root, "single.3mf")

        PlateThreeMfWriter.write(plate, listOf(PlateThreeMfWriter.Entry("quad", mesh, paint)), Dialect.ORCA)
        PaintedMeshWriter.write(mesh, paint, single)

        val expected = listOf(
            "<triangle v1=\"0\" v2=\"1\" v3=\"2\" slic3rpe:custom_supports=\"4\"/>",
            "<triangle v1=\"3\" v2=\"4\" v3=\"5\" slic3rpe:custom_supports=\"8\"/>",
        )
        val painted = Regex("<triangle [^>]*slic3rpe:custom_supports=\"[48]\"/>")
        val fromPlate = painted.findAll(readParts(plate).getValue("3D/3dmodel.model")).map { it.value }.toList()
        assertEquals(expected, fromPlate)
        assertEquals(
            painted.findAll(readParts(single).getValue("3D/3dmodel.model")).map { it.value }.toList(),
            fromPlate,
        )
    }

    @Test
    fun writesTheSameBytesForTheSamePlate() {
        val first = File(folder.root, "first.3mf")
        val second = File(folder.root, "second.3mf")
        val entries = listOf(
            entry("Cube", 2, extruder = 1),
            entry("Wedge", 1, paint = SupportPaintState(enforcerTriangles = setOf(0))),
        )

        PlateThreeMfWriter.write(first, entries, Dialect.PRUSA_LEGACY)
        PlateThreeMfWriter.write(second, entries, Dialect.PRUSA_LEGACY)

        assertArrayEquals(first.readBytes(), second.readBytes())
    }

    @Test
    fun rejectsPaintThatDoesNotAddressTheObject() {
        val file = File(folder.root, "invalid.3mf")

        val failure = runCatching {
            PlateThreeMfWriter.write(
                file,
                listOf(entry("Cube", 2, paint = SupportPaintState(enforcerTriangles = setOf(2)))),
                Dialect.ORCA,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun rejectsAnEmptyPlate() {
        val failure = runCatching {
            PlateThreeMfWriter.write(File(folder.root, "empty.3mf"), emptyList(), Dialect.ORCA)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun entry(
        name: String,
        triangles: Int,
        paint: SupportPaintState? = null,
        extruder: Int? = null,
    ) = PlateThreeMfWriter.Entry(name = name, mesh = mesh(triangles), paint = paint, extruder = extruder)

    /** [triangles] separate triangles in the interleaved position+normal layout. */
    private fun mesh(triangles: Int): StlMesh {
        val vertices = FloatArray(triangles * 18)
        for (triangle in 0 until triangles) {
            // Two millimetres apart, so no two triangles share a corner and every
            // triangle owns three vertices of its own.
            val left = triangle * 2f
            val corners = listOf(
                left, 0f, 0f,
                left + 1f, 0f, 0f,
                left, 1f, 0f,
            )
            for (corner in 0..2) {
                val base = triangle * 18 + corner * 6
                vertices[base] = corners[corner * 3]
                vertices[base + 1] = corners[corner * 3 + 1]
                vertices[base + 2] = corners[corner * 3 + 2]
                vertices[base + 5] = 1f
            }
        }
        return StlMesh(
            displayName = "mesh-$triangles",
            interleavedVertices = VertexData.fromArray(vertices),
            triangleCount = triangles,
            bounds = MeshBounds(0f, 0f, 0f, 2f * triangles - 1f, 1f, 0f),
        )
    }

    private fun readParts(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
        }
    }
}
