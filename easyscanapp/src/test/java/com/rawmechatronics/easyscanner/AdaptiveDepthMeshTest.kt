package com.rawmechatronics.easyscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AdaptiveDepthMeshTest {

    private fun identityPose() = RigidPose(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
        doubleArrayOf(0.0, 0.0, 0.0),
    )

    private fun camera() = DepthCamera(441.0, 435.0, 320.0, 240.0, 640, 480)

    private fun flatMesh(zM: Float = 0.50f): AdaptiveDepthMesh {
        val n = AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE
        val depth = FloatArray(n) { zM }
        val rgb = ByteArray(n * 3) { 128.toByte() }
        return AdaptiveDepthMesh(identityPose(), camera(), depth, scale = 1.0, offset = 0.0, rgb518 = rgb)
    }

    @Test
    fun coarseMeshSubdividesAndCarvesDetailWhereLaserSweeps() {
        val mesh = flatMesh(0.50f)
        val before = mesh.buildMesh()
        assertEquals(0, before.refinedBlocks)
        assertEquals(AdaptiveDepthMesh.COARSE * AdaptiveDepthMesh.COARSE * 2, before.triangleCount)

        // Sweep a 15 mm proud metric laser ridge (z = 0.485 m) across the centre.
        val cam = camera()
        val laser = ArrayList<Float>()
        for (k in -40..40) {
            val u = 320.0 + k * 0.5
            val v = 240.0
            val ray = cam.undistort(u, v)
            val z = 0.485
            laser.add((ray[0] * z).toFloat())
            laser.add((ray[1] * z).toFloat())
            laser.add(z.toFloat())
        }
        val (stats, _) = mesh.addLaserFrame(identityPose(), FloatArray(laser.size) { laser[it] })
        assertEquals(81, stats.acceptedPoints)
        assertEquals(0, stats.rejectedOutliers)
        assertTrue("expected refined blocks", stats.totalRefinedBlocks >= 8)

        val after = mesh.buildMesh()
        assertTrue("triangle count must increase in refined region", after.triangleCount > before.triangleCount)
        // Check that refined vertices moved from 0.50 m toward 0.485 m and have unit normals facing camera (-Z).
        var minRefinedZ = Float.POSITIVE_INFINITY
        var refinedVerts = 0
        for (k in 0 until after.vertexCount) {
            val nx = after.normals[k * 3]; val ny = after.normals[k * 3 + 1]; val nz = after.normals[k * 3 + 2]
            val nLen = sqrt(nx * nx + ny * ny + nz * nz)
            assertEquals(1f, nLen, 1e-3f)
            assertTrue("normal should face reference camera (-Z)", nz <= 0.01f)
            if (after.refinedWeight[k] >= 0.99f) {
                refinedVerts++
                val z = after.positions[k * 3 + 2]
                if (z < minRefinedZ) minRefinedZ = z
            }
        }
        assertTrue(refinedVerts > 10)
        assertEquals(0.485f, minRefinedZ, 0.002f)
    }

    @Test
    fun transitionBetweenCoarseAndRefinedBlocksHasZeroTJunctions() {
        val mesh = flatMesh(0.50f)
        val cam = camera()
        // Hit the exact centre of block (40, 40) -> pixel (162, 162).
        val uf = (162 + 0.5) * cam.width / AdaptiveDepthMesh.SIZE
        val vf = (162 + 0.5) * cam.height / AdaptiveDepthMesh.SIZE
        val ray = cam.undistort(uf, vf)
        val z = 0.495
        val (stats, _) = mesh.addLaserFrame(
            identityPose(),
            floatArrayOf((ray[0] * z).toFloat(), (ray[1] * z).toFloat(), z.toFloat()),
        )
        assertEquals(1, stats.totalRefinedBlocks)

        val data = mesh.buildMesh()
        // Every undirected interior edge in the mesh must be shared by exactly 2 triangles (watertight!).
        val edgeCounts = HashMap<Long, Int>()
        fun addEdge(a: Int, b: Int) {
            val lo = minOf(a, b).toLong()
            val hi = maxOf(a, b).toLong()
            val key = (lo shl 32) or hi
            edgeCounts[key] = (edgeCounts[key] ?: 0) + 1
        }
        for (t in 0 until data.triangleCount) {
            val a = data.indices[t * 3]; val b = data.indices[t * 3 + 1]; val c = data.indices[t * 3 + 2]
            addEdge(a, b); addEdge(b, c); addEdge(c, a)
        }
        // Only outer mesh boundary edges (4 * 129 = 516) may have count 1; every interior edge must have count 2.
        val boundaryEdges = edgeCounts.values.count { it == 1 }
        val nonManifoldEdges = edgeCounts.values.count { it > 2 }
        assertEquals("non-manifold edges must be zero", 0, nonManifoldEdges)
        assertEquals("exactly 4*COARSE outer boundary edges => zero interior cracks or T-junctions",
            4 * AdaptiveDepthMesh.COARSE, boundaryEdges)
    }

    @Test
    fun rejectsOutlierLaserPointsAndAcceptsMovedCameraSweep() {
        val mesh = flatMesh(0.50f)
        // Wild outlier 0.5 m behind the surface must be rejected.
        val (bad, _) = mesh.addLaserFrame(identityPose(), floatArrayOf(0f, 0f, 1.0f))
        assertEquals(0, bad.acceptedPoints)
        assertEquals(1, bad.rejectedOutliers)
        assertEquals(0, mesh.refinedBlockCount())

        // Camera moved 5 cm right and rotated slightly, viewing a true world point (0, 0, 0.49).
        val ang = 0.08
        val r = doubleArrayOf(cos(ang), 0.0, sin(ang), 0.0, 1.0, 0.0, -sin(ang), 0.0, cos(ang))
        val c = doubleArrayOf(0.05, 0.0, 0.0)
        val t = doubleArrayOf(
            -(r[0] * c[0] + r[1] * c[1] + r[2] * c[2]),
            -(r[3] * c[0] + r[4] * c[1] + r[5] * c[2]),
            -(r[6] * c[0] + r[7] * c[1] + r[8] * c[2]),
        )
        val movedPose = RigidPose(r, t)
        val pCam = movedPose.worldToCamera(0.0, 0.0, 0.49)
        val (good, worldPts) = mesh.addLaserFrame(
            movedPose,
            floatArrayOf(pCam[0].toFloat(), pCam[1].toFloat(), pCam[2].toFloat()),
        )
        assertEquals(1, good.acceptedPoints)
        assertEquals(0.0f, worldPts[0], 1e-4f)
        assertEquals(0.0f, worldPts[1], 1e-4f)
        assertEquals(0.49f, worldPts[2], 1e-4f)
    }

    @Test
    fun exportsBinaryMillimetreMeshPlyWithNormals() {
        val mesh = flatMesh(0.50f)
        val data = mesh.buildMesh()
        val out = File(kotlin.io.path.createTempDirectory("adaptive-ply-").toFile(), "out.ply")
        AdaptiveDepthMesh.writeMeshPly(out, data, toMillimetres = true)
        val bytes = out.readBytes()
        val marker = "end_header\n".toByteArray(Charsets.US_ASCII)
        var hdrEnd = -1
        for (i in 0..bytes.size - marker.size) {
            var ok = true
            for (j in marker.indices) if (bytes[i + j] != marker[j]) { ok = false; break }
            if (ok) { hdrEnd = i + marker.size; break }
        }
        assertTrue(hdrEnd > 0)
        val hdr = String(bytes, 0, hdrEnd, Charsets.US_ASCII)
        assertTrue(hdr.contains("property float nx"))
        assertTrue(hdr.contains("element vertex ${data.vertexCount}"))
        assertTrue(hdr.contains("element face ${data.triangleCount}"))
        val expectedBody = data.vertexCount * 27 + data.triangleCount * 13
        assertEquals(expectedBody, bytes.size - hdrEnd)
    }

    @Test
    fun metricPointAtDoesNotDoubleScaleDepth() {
        val cam = camera()
        val n = AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE
        // Raw relative depth = 0.25, scale = 2.4 -> true metric depth = 0.60 m.
        val rawDepth = FloatArray(n) { 0.25f }
        val rgb = ByteArray(n * 3) { 128.toByte() }
        val mesh = AdaptiveDepthMesh(identityPose(), cam, rawDepth, scale = 2.4, offset = 0.0, rgb518 = rgb)
        val pt = mesh.metricPointAt(cam.cx, cam.cy)
        assertTrue("metricPointAt must return a valid 3D point", pt != null)
        assertEquals(0.0, pt!![0], 1e-4)
        assertEquals(0.0, pt[1], 1e-4)
        assertEquals(0.60, pt[2], 1e-3)
    }
}
