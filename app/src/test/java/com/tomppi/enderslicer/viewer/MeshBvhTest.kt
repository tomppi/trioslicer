package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

/**
 * The BVH is an acceleration structure, so the property that matters is that it
 * returns exactly what a full scan returns. These tests compare it against a
 * brute-force nearest-hit search over the same triangles.
 */
class MeshBvhTest {

    private fun buildMesh(triangles: Int, seed: Long): StlMesh {
        val rng = Random(seed)
        val values = FloatArray(triangles * 18)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (t in 0 until triangles) {
            val base = t * 18
            for (v in 0 until 3) {
                val x = (rng.nextFloat() - 0.5f) * 100f
                val y = (rng.nextFloat() - 0.5f) * 100f
                val z = (rng.nextFloat() - 0.5f) * 100f
                values[base + v * 6] = x
                values[base + v * 6 + 1] = y
                values[base + v * 6 + 2] = z
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            }
        }
        return StlMesh(
            displayName = "synthetic",
            interleavedVertices = VertexData.fromArray(values),
            triangleCount = triangles,
            bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
        )
    }

    /** Independent Möller-Trumbore, used as the reference nearest-hit search. */
    private fun bruteForce(
        mesh: StlMesh,
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
    ): Float {
        val v = mesh.interleavedVertices
        var best = Float.POSITIVE_INFINITY
        for (t in 0 until mesh.triangleCount) {
            val b = t * 18
            val ax = v[b]; val ay = v[b + 1]; val az = v[b + 2]
            val bx = v[b + 6]; val by = v[b + 7]; val bz = v[b + 8]
            val cx = v[b + 12]; val cy = v[b + 13]; val cz = v[b + 14]
            val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
            val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
            val px = dy * e2z - dz * e2y
            val py = dz * e2x - dx * e2z
            val pz = dx * e2y - dy * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (det > -1e-7f && det < 1e-7f) continue
            val inv = 1f / det
            val tx = ox - ax; val ty = oy - ay; val tz = oz - az
            val u = (tx * px + ty * py + tz * pz) * inv
            if (u < 0f || u > 1f) continue
            val qx = ty * e1z - tz * e1y
            val qy = tz * e1x - tx * e1z
            val qz = tx * e1y - ty * e1x
            val vv = (dx * qx + dy * qy + dz * qz) * inv
            if (vv < 0f || u + vv > 1f) continue
            val hit = (e2x * qx + e2y * qy + e2z * qz) * inv
            if (hit > 1e-7f && hit < best) best = hit
        }
        return best
    }

    @Test
    fun matchesBruteForceForManyRandomRays() {
        val mesh = buildMesh(triangles = 4000, seed = 20260911L)
        val bvh = MeshBvh.build(mesh)
        val rng = Random(4242L)
        var hits = 0

        repeat(3000) {
            // Aim from a sphere outside the mesh toward a point inside it.
            val tx = (rng.nextFloat() - 0.5f) * 60f
            val ty = (rng.nextFloat() - 0.5f) * 60f
            val tz = (rng.nextFloat() - 0.5f) * 60f
            val ox = (rng.nextFloat() - 0.5f) * 400f
            val oy = (rng.nextFloat() - 0.5f) * 400f
            val oz = (rng.nextFloat() - 0.5f) * 400f
            var dx = tx - ox; var dy = ty - oy; var dz = tz - oz
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 1e-3f) return@repeat
            dx /= len; dy /= len; dz /= len

            val expected = bruteForce(mesh, ox, oy, oz, dx, dy, dz)
            val actual = bvh.raycast(ox, oy, oz, dx, dy, dz)

            if (expected == Float.POSITIVE_INFINITY) {
                assertNull("BVH found a hit where the scan found none", actual)
            } else {
                assertTrue("BVH missed a hit the scan found (t=$expected)", actual != null)
                // The direction is unit length, so the distance from the origin
                // to the hit point is the ray parameter.
                val hx = actual!!.x - ox
                val hy = actual.y - oy
                val hz = actual.z - oz
                val actualT = sqrt(hx * hx + hy * hy + hz * hz)
                assertEquals("hit distance differs", expected, actualT, 1e-3f)
                hits++
            }
        }
        assertTrue("the test rays should actually hit the mesh", hits > 200)
    }

    @Test
    fun hitPointLiesOnTheRay() {
        val mesh = buildMesh(triangles = 500, seed = 7L)
        val bvh = MeshBvh.build(mesh)
        val ox = 0f; val oy = 0f; val oz = 300f
        val hit = bvh.raycast(ox, oy, oz, 0f, 0f, -1f)
        if (hit != null) {
            assertEquals(ox, hit.x, 1e-3f)
            assertEquals(oy, hit.y, 1e-3f)
            assertTrue("hit should be below the origin", hit.z < oz)
        }
    }

    @Test
    fun emptyMeshReturnsNoHit() {
        val mesh = buildMesh(triangles = 0, seed = 1L)
        val bvh = MeshBvh.build(mesh)
        assertNull(bvh.raycast(0f, 0f, 100f, 0f, 0f, -1f))
        assertEquals(0, bvh.triangleCount)
    }

}
