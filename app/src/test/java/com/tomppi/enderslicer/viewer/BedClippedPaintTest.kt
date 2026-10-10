package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.supportpaint.SupportPaintModifiers
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Support paint on a model that hangs below the bed.
 *
 * Paint is indices into the mesh the VIEWER draws, and the engine is handed the
 * bed-clipped mesh: everything below Z=0 dropped, every triangle crossing the
 * plane split in two. That renames every triangle after the first cut, so an
 * unmapped index points at a different facet - on a cube sitting 5 mm below the
 * bed with its top face painted, the enforcer landed on the front wall - and
 * past the clipped count it points at nothing, which the plate writer refuses
 * with a bare require.
 *
 * The clipper records which input triangle each output triangle came from, and
 * the paint is read through that record.
 */
class BedClippedPaintTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** The probe fixture: a cube from z = -5 to z = 5, its top face painted. */
    private fun submergedCube(): StlMesh = MeshFixtures.box(0f, 0f, -5f, 10f, 10f, 5f)

    @Test
    fun paintOnTheTopFaceOfAPartlySubmergedCubeStaysOnTheTopFace() {
        val displayed = submergedCube()
        // MeshFixtures writes the faces in order bottom, top, front, +X, +Y, -X:
        // the top face is triangles 2 and 3.
        val paint = SupportPaintState(enforcerTriangles = setOf(2, 3))

        val clipped = BedClipper.clipToBedWithSources(displayed)
        val staged = paint.throughClip(clipped.sourceTriangles!!)

        assertTrue("the clip really did rename triangles", clipped.mesh.triangleCount != displayed.triangleCount)
        assertEquals("both top-face halves survived", 2, staged.enforcerTriangles.size)
        assertTrue(
            "every painted index addresses the staged mesh: " + staged.enforcerTriangles,
            staged.enforcerTriangles.all { it in 0 until clipped.mesh.triangleCount },
        )

        val prisms = paintedPrisms(clipped.mesh, staged)

        assertEquals("one 8-triangle prism per painted triangle", 16, prisms.triangleCount)
        assertEquals("the enforcer sits ON the top face", 4.6f, prisms.bounds.minZ, 1e-3f)
        assertEquals("and not through the part", 5.4f, prisms.bounds.maxZ, 1e-3f)
        assertEquals("over the whole face", 0f, prisms.bounds.minX, 1e-3f)
        assertEquals(10f, prisms.bounds.maxX, 1e-3f)
        assertEquals(0f, prisms.bounds.minY, 1e-3f)
        assertEquals(10f, prisms.bounds.maxY, 1e-3f)
    }

    @Test
    fun aPaintedTriangleThatTheClipRenamedStillWritesAPlate() {
        // A fanned cube sitting ten millimetres under the bed with one
        // millimetre left above it: most of its 24 facets are entirely below the
        // plate and the clip drops them, so the last facet's index - the one
        // painted here - is past the end of the staged mesh.
        val displayed = MeshFixtures.fannedBox(0f, 0f, -10f, 10f, 10f, 1f)
        val paint = SupportPaintState(blockerTriangles = setOf(23))

        val clipped = BedClipper.clipToBedWithSources(displayed)
        assertTrue(
            "the staged mesh is shorter than the displayed one: " + clipped.mesh.triangleCount,
            clipped.mesh.triangleCount < displayed.triangleCount,
        )
        assertTrue(
            "and the painted index no longer addresses it: " + clipped.mesh.triangleCount,
            23 !in 0 until clipped.mesh.triangleCount,
        )
        val staged = paint.throughClip(clipped.sourceTriangles!!)

        val file = File(folder.root, "plate.3mf")
        PlateThreeMfWriter.write(
            file,
            listOf(PlateThreeMfWriter.Entry(name = "half", mesh = clipped.mesh, paint = staged)),
            PlateThreeMfWriter.Dialect.ORCA,
        )

        val parsed = ThreeMfModelParser.parse(file, "plate.3mf", maxTriangles = 1_000)
        assertEquals("the painted wall survives the round trip", 1, parsed.paint.blockerTriangles.size)
        val triangle = parsed.paint.blockerTriangles.single()
        assertEquals(
            "and it is still the -X wall it was painted on",
            -1f,
            parsed.mesh.interleavedVertices[triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE + 3],
            1e-3f,
        )
    }

    /** The enforcer prisms CuraEngine would be given for [paint] on [mesh]. */
    private fun paintedPrisms(mesh: StlMesh, paint: SupportPaintState): StlMesh {
        val destination = File(folder.root, "modifiers")
        val modifier = SupportPaintModifiers.generate(
            mesh = mesh,
            paint = paint,
            destination = destination,
            thicknessMm = 0.8,
        ).single()
        return StlParser.parse(modifier.file, modifier.file.name, MeshTriangleLimits.current())
    }
}
