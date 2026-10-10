package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.viewer.BedClipper.clipToBed
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The build-plate clip: what a placement below the bed is staged as.
 *
 * The contract these tests pin down is the one the slice path relies on - a
 * model that does not dip below Z=0 comes back as the very same instance (so it
 * stages byte-for-byte as before), a model that does comes back cut at Z=0 with
 * its winding intact, and a model wholly below the plate comes back empty.
 */
class BedClipperTest {
    @Test
    fun modelEntirelyAboveThePlaneIsReturnedUnchanged() {
        val mesh = mesh(quad(indices = intArrayOf(0, 1, 2), offsetZ = 1f))

        val clipped = clipToBed(mesh)

        // The same instance, not an equal copy: nothing was allocated and
        // nothing will be rewritten on the way to the engine.
        assertSame("a model above the bed is not copied", mesh, clipped)
        assertSame("its vertex data is the same instance", mesh.interleavedVertices, clipped.interleavedVertices)
    }

    @Test
    fun modelEntirelyBelowThePlaneYieldsAnEmptyMesh() {
        val mesh = mesh(quad(indices = intArrayOf(0, 1, 2), offsetZ = -5f))

        val clipped = clipToBed(mesh)

        assertEquals("nothing is left above the bed", 0, clipped.triangleCount)
        assertEquals("no vertex data is kept", 0, clipped.interleavedVertices.size)
        assertNotSame("it is not the mesh it came from", mesh, clipped)
        assertEquals("the empty bounds are degenerate", 0f, clipped.bounds.minZ, 0f)
        assertEquals("the empty bounds are degenerate", 0f, clipped.bounds.maxZ, 0f)
        // What the caller must do with it: refuse to stage it. An empty STL is
        // exactly what StlMeshWriter refuses, which is the failure this check
        // replaces with a refusal before the engine is involved.
        val empty = File(kotlin.io.path.createTempDirectory("enderslicer-empty-clip").toFile(), "empty.stl")
        val error = runCatching { StlMeshWriter.writeBinary(clipped, empty) }.exceptionOrNull()
        assertTrue(
            "an empty clip cannot be staged: " + error,
            error is IllegalArgumentException && error.message.orEmpty().contains("empty STL"),
        )
    }

    @Test
    fun modelCrossingThePlaneKeepsTheRightTrianglesAndBounds() {
        val mesh = mesh(box(halfX = 1f, halfY = 1f, halfZ = 1.5f, centerZ = -0.5f))

        val clipped = clipToBed(mesh)

        // Two triangles are wholly below and vanish; the four side faces are
        // crossed by the plane. Each keeps a trapezoid as two triangles and the
        // corner below as one more, having gained two crossing points:
        // 12 - 2 - 8 + 8 + 4 = 14.
        assertEquals("the triangles below the bed are gone and the crossing ones cut", 14, clipped.triangleCount)
        assertEquals("the top of the model is untouched", 1f, clipped.bounds.maxZ, 0f)
        assertEquals("the cut sits on the plane", 0f, clipped.bounds.minZ, 1e-6f)
        assertEquals("X is untouched", -1f, clipped.bounds.minX, 0f)
        assertEquals("X is untouched", 1f, clipped.bounds.maxX, 0f)
        assertEquals("Y is untouched", -1f, clipped.bounds.minY, 0f)
        assertEquals("Y is untouched", 1f, clipped.bounds.maxY, 0f)
        // The box spans z = -2 to 1, so the part above the plane is the 2 x 2 x 1
        // slab from 0 to 1: 4 mm3. That only comes out right if the cut
        // triangles are the right triangles.
        assertEquals("the kept volume is the part above the bed", 4.0, signedVolume(clipped), 1e-5)
    }

    @Test
    fun oneCornerAboveThePlaneLeavesAQuadAndOneBelowLeavesATriangle() {
        // Apex at (1,2,2) over the base (0,0,-2)-(2,0,-2), then the same triangle
        // with the base at (0,0,2)-(2,0,2). Both are the same sort of triangle
        // and both are cut on the same line, so one keeps the apex's share of
        // the surface and the other the base's. The shapes differ - under the
        // apex it is the triangle of the apex, over the base the trapezoid of
        // the base - and the two shares add up to the whole triangle.
        val apexAbove = mesh(
            floatArrayOf(
                0f, 0f, -2f, 0f, 0f, 1f,
                2f, 0f, -2f, 0f, 0f, 1f,
                1f, 2f, 2f, 0f, 0f, 1f,
            ),
        )
        val apexBelow = mesh(
            floatArrayOf(
                0f, 0f, 2f, 0f, 0f, 1f,
                2f, 0f, 2f, 0f, 0f, 1f,
                1f, 2f, -2f, 0f, 0f, 1f,
            ),
        )

        val clippedOne = clipToBed(apexAbove)
        val clippedTwo = clipToBed(apexBelow)

        assertEquals("the apex above the plane leaves one triangle", 1, clippedOne.triangleCount)
        assertEquals("the apex below the plane leaves a trapezoid", 2, clippedTwo.triangleCount)
        // 3 / sqrt(5) is the share of the surface the trapezoid of the base
        // keeps when the triangle is scaled so its whole surface is sqrt(5).
        assertEquals("the apex keeps its share of the surface", kotlin.math.sqrt(5.0) / 2.0, area(clippedOne), 1e-5)
        assertEquals("the base keeps three times as much", kotlin.math.sqrt(5.0) * 3.0 / 2.0, area(clippedTwo), 1e-5)
        assertEquals(0f, clippedOne.bounds.minZ, 1e-6f)
        assertEquals(0f, clippedTwo.bounds.minZ, 1e-6f)
        assertEquals("the cut keeps the whole base", 2f, clippedTwo.bounds.maxX, 0f)
        assertEquals("and starts above the base's near end", 0f, clippedTwo.bounds.minX, 0f)
        assertEquals("the crossing points sit over the base", 0.5f, clippedOne.bounds.minX, 1e-6f)
        assertEquals("and over the other side of it", 1.5f, clippedOne.bounds.maxX, 1e-6f)
        assertEquals("the apex above the plane is untouched", 2f, clippedOne.bounds.maxZ, 1e-6f)
        assertEquals("the trapezoid keeps the base it started from", 2f, clippedTwo.bounds.maxZ, 1e-6f)
    }

    @Test
    fun windingIsPreserved() {
        val clipped = clipToBed(mesh(box(halfX = 1f, halfY = 1f, halfZ = 1.5f, centerZ = -0.5f)))
        val mirrored = clipToBed(mesh(mirroredBoxTriangles()))
        val outward = clipToBed(
            mesh(
                floatArrayOf(
                    1f, 0f, -1f, 0f, 0f, 1f,
                    1f, 2f, -1f, 0f, 0f, 1f,
                    1f, 2f, 1f, 0f, 0f, 1f,
                ),
            ),
        )

        // A closed mesh keeps the sign of the volume its surface bounds when it
        // is cut open: +4 only comes out if every surviving triangle still winds
        // the way the original did.
        assertEquals("the clipped box still faces outwards", 4.0, signedVolume(clipped), 1e-5)
        // The same box with every triangle reversed bounds the same volume with
        // the opposite sign, and clipping must keep that sign.
        assertEquals("a reversed mesh stays reversed", -4.0, signedVolume(mirrored), 1e-5)
        // The cut triangles carry the original vertex normals, not recomputed
        // ones: this face's three vertices all point along +Z, and the whole face
        // is x = 1.
        assertEquals("a cut triangle keeps its vertex normals", 1f, normalZAt(outward, 0), 0f)
        assertEquals("and its own plane", 1f, outward.interleavedVertices[0], 0f)
    }

    @Test
    fun aTriangleOnThePlaneIsHandledDeterministically() {
        val standing = mesh(triangle(1f, 1f, 0f))
        val lying = mesh(floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, 1f))

        // Nothing of either triangle is below the plane, so neither has anything
        // cut away and both are handed back untouched.
        assertSame("a triangle standing on the plane is untouched", standing, clipToBed(standing))
        assertSame("a triangle lying on the plane is untouched", lying, clipToBed(lying))

        // Crossing with the plane running through two edges: base from (0,0,-1)
        // to (2,0,-1), apex at (1,1,1). The plane passes through (0.5,0.5,0) and
        // (1.5,0.5,0), leaving a triangle of base 1 and height sqrt(1.25)/2.
        val touching = mesh(
            floatArrayOf(
                0f, 0f, -1f, 0f, 0f, 1f,
                2f, 0f, -1f, 0f, 0f, 1f,
                1f, 1f, 1f, 0f, 0f, 1f,
            ),
        )
        val clippedTouching = clipToBed(touching)

        assertEquals("two below and one above leaves one triangle", 1, clippedTouching.triangleCount)
        assertEquals("the cut is on the plane", 0f, clippedTouching.bounds.minZ, 1e-6f)
        assertEquals("the triangle above the plane is sqrt(1.25)/2 mm2", kotlin.math.sqrt(1.25) / 2.0, area(clippedTouching), 1e-5)
        assertEquals("the cut runs between the crossing points", 1.5f, clippedTouching.bounds.maxX, 1e-6f)
        assertEquals("and back to the other one", 0.5f, clippedTouching.bounds.minX, 1e-6f)
    }

    private fun mesh(floats: FloatArray): StlMesh {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var offset = 0
        repeat(floats.size / 6) {
            minX = minOf(minX, floats[offset])
            minY = minOf(minY, floats[offset + 1])
            minZ = minOf(minZ, floats[offset + 2])
            maxX = maxOf(maxX, floats[offset])
            maxY = maxOf(maxY, floats[offset + 1])
            maxZ = maxOf(maxZ, floats[offset + 2])
            offset += 6
        }
        return StlMesh(
            displayName = "bed-clip-test",
            interleavedVertices = VertexData.fromArray(floats),
            triangleCount = floats.size / 18,
            bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
        )
    }

    /** One triangle from (0, 0, 0), with a +Z normal on all three vertices. */
    private fun triangle(
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float = 0f,
        y2: Float = 0f,
        z2: Float = 0f,
    ): FloatArray = floatArrayOf(
        0f, 0f, 0f, 0f, 0f, 1f,
        x1, y1, z1, 0f, 0f, 1f,
        x2, y2, z2, 0f, 0f, 1f,
    )

    /** Three of the corners of a unit square, raised or lowered by [offsetZ]. */
    private fun quad(indices: IntArray, offsetZ: Float): FloatArray {
        val corners = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f)
        return FloatArray(indices.size * 6) { index ->
            val vertex = indices[index / 6]
            val channel = index % 6
            if (channel == 2) corners[vertex * 3 + 2] + offsetZ else corners[vertex * 3 + channel]
        }
    }

    /** An axis-aligned closed box, all twelve triangles facing outwards. */
    private fun box(halfX: Float, halfY: Float, halfZ: Float, centerZ: Float): FloatArray =
        boxTriangles(halfX, halfY, halfZ, centerZ, reversed = false)

    private fun mirroredBoxTriangles(): FloatArray = boxTriangles(1f, 1f, 1.5f, -0.5f, reversed = true)

    private fun boxTriangles(halfX: Float, halfY: Float, halfZ: Float, centerZ: Float, reversed: Boolean): FloatArray {
        val corners = arrayOf(
            floatArrayOf(-halfX, -halfY, centerZ - halfZ),
            floatArrayOf(halfX, -halfY, centerZ - halfZ),
            floatArrayOf(halfX, halfY, centerZ - halfZ),
            floatArrayOf(-halfX, halfY, centerZ - halfZ),
            floatArrayOf(-halfX, -halfY, centerZ + halfZ),
            floatArrayOf(halfX, -halfY, centerZ + halfZ),
            floatArrayOf(halfX, halfY, centerZ + halfZ),
            floatArrayOf(-halfX, halfY, centerZ + halfZ),
        )
        val faces = arrayOf(
            intArrayOf(0, 3, 2, 1),
            intArrayOf(4, 5, 6, 7),
            intArrayOf(0, 1, 5, 4),
            intArrayOf(1, 2, 6, 5),
            intArrayOf(2, 3, 7, 6),
            intArrayOf(3, 0, 4, 7),
        )
        val floats = FloatArray(faces.size * 2 * 18)
        var out = 0
        faces.forEach { face ->
            val triangles = listOf(
                intArrayOf(face[0], face[1], face[2]),
                intArrayOf(face[0], face[2], face[3]),
            )
            triangles.forEach { cornersOfTriangle ->
                val ordered = if (reversed) cornersOfTriangle.reversedArray() else cornersOfTriangle
                ordered.forEach { corner ->
                    floats[out++] = corners[corner][0]
                    floats[out++] = corners[corner][1]
                    floats[out++] = corners[corner][2]
                    floats[out++] = 0f
                    floats[out++] = 0f
                    floats[out++] = 1f
                }
            }
        }
        return floats
    }

    /** The volume the surface bounds, positive for an outward-facing closed mesh. */
    private fun signedVolume(mesh: StlMesh): Double {
        var total = 0.0
        var offset = 0
        repeat(mesh.triangleCount) {
            val vertices = mesh.interleavedVertices
            val ax = vertices[offset].toDouble()
            val ay = vertices[offset + 1].toDouble()
            val az = vertices[offset + 2].toDouble()
            val bx = vertices[offset + 6].toDouble()
            val by = vertices[offset + 7].toDouble()
            val bz = vertices[offset + 8].toDouble()
            val cx = vertices[offset + 12].toDouble()
            val cy = vertices[offset + 13].toDouble()
            val cz = vertices[offset + 14].toDouble()
            total += (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6.0
            offset += 18
        }
        return total
    }

    /** The total area of every triangle, whichever way they face. */
    private fun area(mesh: StlMesh): Double {
        var total = 0.0
        var offset = 0
        repeat(mesh.triangleCount) {
            val vertices = mesh.interleavedVertices
            val ax = vertices[offset].toDouble()
            val ay = vertices[offset + 1].toDouble()
            val az = vertices[offset + 2].toDouble()
            val bx = vertices[offset + 6].toDouble()
            val by = vertices[offset + 7].toDouble()
            val bz = vertices[offset + 8].toDouble()
            val cx = vertices[offset + 12].toDouble()
            val cy = vertices[offset + 13].toDouble()
            val cz = vertices[offset + 14].toDouble()
            val ux = bx - ax
            val uy = by - ay
            val uz = bz - az
            val vx = cx - ax
            val vy = cy - ay
            val vz = cz - az
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            total += kotlin.math.sqrt(nx * nx + ny * ny + nz * nz) / 2.0
            offset += 18
        }
        return total
    }

    private fun normalZAt(mesh: StlMesh, triangle: Int): Float = mesh.interleavedVertices[triangle * 18 + 5]
}
