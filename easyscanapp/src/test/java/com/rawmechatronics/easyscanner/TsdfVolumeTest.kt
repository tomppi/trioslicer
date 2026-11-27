package com.rawmechatronics.easyscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TsdfVolumeTest {

    private fun idPose() = RigidPose(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
        doubleArrayOf(0.0, 0.0, 0.0),
    )

    private fun camera() = DepthCamera(441.0, 435.0, 320.0, 240.0, 640, 480)

    @Test
    fun analyticSphereIsWatertightAcrossNegativeAndPositiveBlocks() {
        val voxel = 0.0025f
        val trunc = voxel * 4f
        val radius = 0.026f // spans blocks [-2..+1] on all 3 axes
        val vol = TsdfVolume(voxelSizeM = voxel, truncationM = trunc)
        for (gz in -14..14) for (gy in -14..14) for (gx in -14..14) {
            val x = (gx + 0.5f) * voxel
            val y = (gy + 0.5f) * voxel
            val z = (gz + 0.5f) * voxel
            val sdf = sqrt(x * x + y * y + z * z) - radius
            if (abs(sdf) <= trunc * 1.6f) {
                vol.setVoxelForTest(gx, gy, gz, (sdf / trunc).coerceIn(-1f, 1f), weightVal = 2f)
            }
        }
        val mesh = vol.extractMesh()
        assertTrue("expected closed sphere triangles", mesh.triangleCount > 1000)

        // Verify every directed edge (u, v) has an exact twin (v, u) across ALL block seams.
        val dirEdges = HashMap<Long, Int>(mesh.indices.size)
        fun add(u: Int, v: Int) {
            val k = (u.toLong() shl 32) or (v.toLong() and 0xffffffffL)
            dirEdges[k] = (dirEdges[k] ?: 0) + 1
        }
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.indices[t * 3]; val b = mesh.indices[t * 3 + 1]; val c = mesh.indices[t * 3 + 2]
            add(a, b); add(b, c); add(c, a)
        }
        var dup = 0; var unpaired = 0
        for ((k, cnt) in dirEdges) {
            if (cnt != 1) dup++
            val u = (k ushr 32).toInt(); val v = k.toInt()
            val twin = (v.toLong() shl 32) or (u.toLong() and 0xffffffffL)
            if (!dirEdges.containsKey(twin)) unpaired++
        }
        assertEquals("zero non-manifold duplicate directed edges across blocks", 0, dup)
        assertEquals("zero boundary cracks across blocks", 0, unpaired)

        // Verify vertex accuracy and outward analytic TSDF-gradient normals.
        for (k in 0 until mesh.vertexCount) {
            val x = mesh.positions[k * 3]; val y = mesh.positions[k * 3 + 1]; val z = mesh.positions[k * 3 + 2]
            val r = sqrt(x * x + y * y + z * z)
            assertEquals(radius, r, 0.001f)
            val nx = mesh.normals[k * 3]; val ny = mesh.normals[k * 3 + 1]; val nz = mesh.normals[k * 3 + 2]
            val nLen = sqrt(nx * nx + ny * ny + nz * nz)
            assertEquals(1f, nLen, 1e-3f)
            val dot = (nx * x + ny * y + nz * z) / r
            assertTrue("gradient normal must point radially outward, dot=$dot", dot > 0.90f)
        }
    }

    @Test
    fun laserCarvesDetailOverCoarseDa3AndResistsLaterCoarseBlur() {
        val cam = camera()
        val vol = TsdfVolume(voxelSizeM = 0.0025f)
        val n = AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE
        val rgb = ByteArray(n * 3) { 150.toByte() }

        // 1. Seed flat coarse DA3 plane at z = 0.500 m.
        val depthBase = FloatArray(n) { 0.500f }
        vol.integrateDepthMap(idPose(), cam, depthBase, scale = 1.0, rgb518 = rgb, stride = 4)
        val before = vol.extractMesh()
        assertTrue(before.triangleCount > 200)

        // 2. Sweep metric laser boss at z = 0.491 m (9 mm proud) in the central patch.
        val laser = ArrayList<Float>()
        for (iy in -6..6) for (ix in -6..6) {
            val u = 320.0 + ix * 2.0
            val v = 240.0 + iy * 2.0
            val ray = cam.undistort(u, v)
            val z = 0.491
            laser.add((ray[0] * z).toFloat()); laser.add((ray[1] * z).toFloat()); laser.add(z.toFloat())
        }
        val hits = vol.integrateLaserFrame(idPose(), FloatArray(laser.size) { laser[it] })
        assertEquals(169, hits)
        val carved = vol.extractMesh()
        val carvedCenterZ = carved.positions.let { p ->
            var minZ = Float.POSITIVE_INFINITY
            for (k in 0 until carved.vertexCount) {
                if (carved.refinedWeight[k] > 0.7f && abs(p[k * 3]) < 0.01f && abs(p[k * 3 + 1]) < 0.01f) {
                    if (p[k * 3 + 2] < minZ) minZ = p[k * 3 + 2]
                }
            }
            minZ
        }
        assertEquals(0.491f, carvedCenterZ, 0.002f)

        // 3. A second noisy coarse DA3 patch shifted +6 mm away must NOT erase the laser-carved boss.
        val depthNoisy = FloatArray(n) { 0.506f }
        vol.integrateDepthMap(idPose(), cam, depthNoisy, scale = 1.0, rgb518 = rgb, stride = 4)
        val afterNoise = vol.extractMesh()
        var protectedMinZ = Float.POSITIVE_INFINITY
        for (k in 0 until afterNoise.vertexCount) {
            if (afterNoise.refinedWeight[k] > 0.7f && abs(afterNoise.positions[k * 3]) < 0.01f && abs(afterNoise.positions[k * 3 + 1]) < 0.01f) {
                val z = afterNoise.positions[k * 3 + 2]
                if (z < protectedMinZ) protectedMinZ = z
            }
        }
        assertTrue("laser detail must resist coarse DA3 blur (was $protectedMinZ)", protectedMinZ < 0.495f)
    }

    @Test
    fun dirtyBlockCachingOnlyRebuildsTouchedBlocks() {
        val cam = camera()
        val vol = TsdfVolume(voxelSizeM = 0.004f)
        val n = AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE
        val depth = FloatArray(n) { 0.50f }
        val rgb = ByteArray(n * 3) { 128.toByte() }
        vol.integrateDepthMap(idPose(), cam, depth, scale = 1.0, rgb518 = rgb, stride = 4)
        val m1 = vol.extractMesh()
        val firstRebuilt = vol.stats().lastDirtyRebuilt
        assertTrue(firstRebuilt > 10)

        // No updates -> 0 dirty blocks rebuilt and identical mesh.
        val m2 = vol.extractMesh()
        assertEquals(0, vol.stats().lastDirtyRebuilt)
        assertEquals(m1.triangleCount, m2.triangleCount)

        // Single laser hit -> only a tiny handful of blocks rebuilt.
        vol.integrateLaserFrame(idPose(), floatArrayOf(0f, 0f, 0.496f))
        vol.extractMesh()
        val dirtyAfterOne = vol.stats().lastDirtyRebuilt
        assertTrue("expected 1..8 dirty blocks, got $dirtyAfterOne", dirtyAfterOne in 1..8)
    }

    @Test
    fun multiViewFusionProducesUnifiedVolumetricMesh() {
        val cam = camera()
        val vol = TsdfVolume(voxelSizeM = 0.004f)
        val n = AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE
        val rgb = ByteArray(n * 3) { 180.toByte() }
        val depth = FloatArray(n) { 0.48f }
        // View 0: front, View 1: yawed 20 deg around target.
        val pose0 = idPose()
        val ang = Math.toRadians(-20.0)
        val r1 = doubleArrayOf(cos(ang), 0.0, sin(ang), 0.0, 1.0, 0.0, -sin(ang), 0.0, cos(ang))
        val c1 = doubleArrayOf(0.16, 0.0, 0.03)
        val t1 = doubleArrayOf(
            -(r1[0] * c1[0] + r1[1] * c1[1] + r1[2] * c1[2]),
            -(r1[3] * c1[0] + r1[4] * c1[1] + r1[5] * c1[2]),
            -(r1[6] * c1[0] + r1[7] * c1[1] + r1[8] * c1[2]),
        )
        val pose1 = RigidPose(r1, t1)
        vol.integrateDepthMap(pose0, cam, depth, scale = 1.0, rgb518 = rgb, stride = 4)
        vol.integrateDepthMap(pose1, cam, depth, scale = 1.0, rgb518 = rgb, stride = 4)
        val mesh = vol.extractMesh()
        assertTrue(mesh.triangleCount > 500)
        assertEquals(2, vol.stats().integratedPatches)
    }

    @Test
    fun clipsDistantRoomBackgroundAndEnforcesMaxBlocksCap() {
        val cam = camera()
        val n = AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE
        val rgb = ByteArray(n * 3) { 128.toByte() }
        // Room wall at 6 m must allocate ZERO blocks under default 1.5 m foreground range.
        val volRoom = TsdfVolume()
        volRoom.integrateDepthMap(idPose(), cam, FloatArray(n) { 6.0f }, scale = 1.0, rgb518 = rgb, stride = 4)
        assertEquals(0, volRoom.stats().activeBlocks)

        // Foreground plane with tight maxBlocks cap must never exceed maxBlocks.
        val volCapped = TsdfVolume(voxelSizeM = 0.004f, maxBlocks = 25)
        volCapped.integrateDepthMap(idPose(), cam, FloatArray(n) { 0.60f }, scale = 1.0, rgb518 = rgb, stride = 4)
        assertTrue(volCapped.stats().activeBlocks in 1..25)
    }

    @Test
    fun puzzlePieceRuleBlocksDoubleLayerSheetAndExtendsIntoUncoveredSpace() {
        val cam = camera()
        val vol = TsdfVolume(voxelSizeM = 0.004f)
        val n = AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE
        val rgb = ByteArray(n * 3) { 160.toByte() }

        // 1. Place the first puzzle piece: flat plane at z = 0.500 m.
        vol.integrateDepthMap(idPose(), cam, FloatArray(n) { 0.500f }, scale = 1.0, rgb518 = rgb, stride = 4)
        val seedMesh = vol.extractMesh()
        val seedBlocks = vol.stats().activeBlocks
        assertTrue(seedMesh.triangleCount > 300)

        // 2. Try to paste a warped DA3 sheet 35 mm behind (outside the 16 mm trunc band) over the exact same view.
        val warped = FloatArray(n) { 0.535f }
        val overlapNovelty = vol.estimateNoveltyFraction(idPose(), cam, warped, scale = 1.0)
        assertTrue("same view should report near-zero novelty, was $overlapNovelty", overlapNovelty < 0.05f)

        vol.integrateDepthMap(idPose(), cam, warped, scale = 1.0, rgb518 = rgb, stride = 4)
        val afterPaste = vol.extractMesh()
        var ghostVerts = 0
        for (k in 0 until afterPaste.vertexCount) {
            if (afterPaste.positions[k * 3 + 2] > 0.518f) ghostVerts++
        }
        assertEquals("puzzle rule must not paste a second double-wall layer behind existing piece", 0, ghostVerts)
        assertTrue("must not allocate new interior blocks for covered area", vol.stats().activeBlocks <= seedBlocks + 2)

        // 3. Shift camera 22 cm right so genuinely new uncovered space appears on the right edge.
        val shiftedPose = RigidPose(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            doubleArrayOf(-0.22, 0.0, 0.0),
        )
        val shiftedDepth = FloatArray(n) { 0.500f }
        val newNovelty = vol.estimateNoveltyFraction(shiftedPose, cam, shiftedDepth, scale = 1.0)
        assertTrue("shifted view should expose >15% new space, was $newNovelty", newNovelty > 0.15f)

        vol.integrateDepthMap(shiftedPose, cam, shiftedDepth, scale = 1.0, rgb518 = rgb, stride = 4)
        val extended = vol.extractMesh()
        assertTrue(
            "puzzle piece must extend into uncovered +X space (${seedMesh.boundsMax[0]} -> ${extended.boundsMax[0]})",
            extended.boundsMax[0] > seedMesh.boundsMax[0] + 0.12f,
        )
    }
}
