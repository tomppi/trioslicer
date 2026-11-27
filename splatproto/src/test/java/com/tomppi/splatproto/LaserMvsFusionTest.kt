package com.tomppi.splatproto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

class LaserMvsFusionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun interpolatePoseMovesCentreLinearlyAndSlerpsRotation() {
        // Pose A: identity at C = (0, 0, 0)
        val qa = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        val a = LaserMvsFusion.CameraPose(LaserMvsFusion.quatToMat(qa), doubleArrayOf(0.0, 0.0, 0.0), qa)

        // Pose B: 90 deg rotation about Z, world camera centre at C = (2, 0, 0) => t = -R * C
        val ang = Math.PI / 2.0
        val qb = doubleArrayOf(cos(ang / 2.0), 0.0, 0.0, sin(ang / 2.0))
        val rb = LaserMvsFusion.quatToMat(qb)
        val cb = doubleArrayOf(2.0, 0.0, 0.0)
        val tb = doubleArrayOf(
            -(rb[0] * cb[0] + rb[1] * cb[1] + rb[2] * cb[2]),
            -(rb[3] * cb[0] + rb[4] * cb[1] + rb[5] * cb[2]),
            -(rb[6] * cb[0] + rb[7] * cb[1] + rb[8] * cb[2]),
        )
        val b = LaserMvsFusion.CameraPose(rb, tb, qb)

        val mid = LaserMvsFusion.interpolatePose(a, b, 0.5)
        val cm = mid.centre()
        assertEquals(1.0, cm[0], 1e-6)
        assertEquals(0.0, cm[1], 1e-6)
        assertEquals(0.0, cm[2], 1e-6)

        // Rotation angle at midpoint must be 45 degrees about Z.
        val expectedW = cos(Math.PI / 8.0)
        assertEquals(expectedW, kotlin.math.abs(mid.q[0]), 1e-6)
    }

    @Test
    fun estimateLaserScaleRecoversKnownMetricScaleFactor() {
        val intr = Intrinsics(640, 480, 500.0, 500.0, 320.0, 240.0, 0.0, 0.0, 0.0, 0.0, 0.0, true)
        val q = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        val pose = LaserMvsFusion.CameraPose(LaserMvsFusion.quatToMat(q), doubleArrayOf(0.0, 0.0, 0.0), q)

        // Suppose MVS world has a plane at z = 0.50 m, while true metric laser measures z = 0.75 m.
        // True scale factor should be 0.75 / 0.50 = 1.50.
        val gridCount = 20 * 20
        val worldXyz = FloatArray(gridCount * 6)
        val laserCamM = FloatArray(gridCount * 3)
        var k = 0
        for (iy in 0 until 20) {
            for (ix in 0 until 20) {
                val u = 160.0 + ix * 16.0
                val v = 120.0 + iy * 12.0
                val rx = (u - intr.cx) / intr.fx
                val ry = (v - intr.cy) / intr.fy
                val zMvs = 0.50
                val zLaser = 0.75
                worldXyz[k * 6] = (rx * zMvs).toFloat()
                worldXyz[k * 6 + 1] = (ry * zMvs).toFloat()
                worldXyz[k * 6 + 2] = zMvs.toFloat()
                worldXyz[k * 6 + 5] = -1f
                laserCamM[k * 3] = (rx * zLaser).toFloat()
                laserCamM[k * 3 + 1] = (ry * zLaser).toFloat()
                laserCamM[k * 3 + 2] = zLaser.toFloat()
                k++
            }
        }
        val lf = LaserMvsFusion.LaserFrame(0, 1_000_000L, pose, laserCamM, null, pose)
        val scale = LaserMvsFusion.estimateLaserScale(listOf(lf), worldXyz, 6, gridCount, intr)
        assertEquals(1.5, scale, 0.03)
    }

    @Test
    fun readParallaxPlyAndLaserInitProduceValidBinaryOutputs() {
        val scanDir = tmp.newFolder("scan-test")
        val sparseDir = File(scanDir, "sparse/0").apply { mkdirs() }
        val imagesDir = File(scanDir, "images").apply { mkdirs() }
        val hybridLaserDir = File(scanDir, "hybrid/laser").apply { mkdirs() }

        // Write a 3-point binary dense.ply (Parallax 27-byte format).
        val densePly = File(scanDir, "dense.ply")
        val hdr = "ply\nformat binary_little_endian 1.0\n" +
            "element vertex 3\n" +
            "property float x\nproperty float y\nproperty float z\n" +
            "property float nx\nproperty float ny\nproperty float nz\n" +
            "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n"
        FileOutputStream(densePly).use { os ->
            os.write(hdr.toByteArray(Charsets.US_ASCII))
            val bb = ByteBuffer.allocate(3 * 27).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 3) {
                bb.putFloat(i * 0.1f); bb.putFloat(0.2f); bb.putFloat(0.5f)
                bb.putFloat(0f); bb.putFloat(0f); bb.putFloat(-1f)
                bb.put((50 + i * 20).toByte()); bb.put(120.toByte()); bb.put(200.toByte())
            }
            os.write(bb.array())
        }
        val cloud = LaserMvsFusion.readParallaxPly(densePly)
        assertNotNull(cloud)
        assertEquals(3, cloud!!.count)
        assertTrue(cloud.hasNormals)
        assertEquals(0.2f, cloud.xyzNxyz[1], 1e-6f)
        assertEquals(-1f, cloud.xyzNxyz[5], 1e-6f)
        assertEquals(70, cloud.rgb[3].toInt() and 0xFF)

        // Write COLMAP cameras.txt + images.txt with 2 keyframes (frame 0 at x=0, frame 10 at x=0.2 m).
        File(sparseDir, "cameras.txt").writeText("1 PINHOLE 640 480 500 500 320 240\n")
        File(sparseDir, "images.txt").writeText(
            "1 1 0 0 0 0 0 0 1 f00000.jpg\n\n" +
                "2 1 0 0 0 -0.2 0 0 1 f00010.jpg\n\n",
        )
        // Laser frame at frame 5 (halfway between frame 0 and frame 10 => camera centre x = 0.1 m).
        val f5 = File(hybridLaserDir, "f00005.f32")
        val lbb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        lbb.putFloat(0f); lbb.putFloat(0f); lbb.putFloat(500f) // 0.5 m in front of camera
        f5.writeBytes(lbb.array())

        val hybridJson = JSONObject()
            .put(
                "images",
                JSONArray()
                    .put(JSONObject().put("file", "f00000.jpg").put("frame", 0).put("sensor_timestamp_ns", 1_000_000_000L))
                    .put(JSONObject().put("file", "f00010.jpg").put("frame", 10).put("sensor_timestamp_ns", 2_000_000_000L)),
            )
            .put(
                "laser_frames",
                JSONArray()
                    .put(JSONObject().put("frame", 5).put("sensor_timestamp_ns", 1_500_000_000L).put("laser", "hybrid/laser/f00005.f32")),
            )
        File(scanDir, "hybrid/hybrid.json").writeText(hybridJson.toString())

        val initPly = File(scanDir, "init.ply")
        assertTrue(LaserInit.build(Scan(scanDir), initPly))
        assertTrue(initPly.isFile && initPly.length() > 100L)

        // Verify LaserInit -> Cross3dgsCheckpoint round-trip places frame 5's point at world (0.1, 0.0, 0.5).
        val chk = File(scanDir, "init.d3gs")
        assertTrue(Cross3dgsCheckpoint.fromPly(initPly, chk))
        assertEquals(1, Cross3dgsCheckpoint.readCount(chk))
        val cb = ByteBuffer.wrap(chk.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        // Header is 16 bytes (magic, version, count, sh_degree); first Gaussian mean (x, y, z, pad) is at offset 16.
        assertEquals(0.1f, cb.getFloat(16), 1e-4f)
        assertEquals(0.0f, cb.getFloat(20), 1e-4f)
        assertEquals(0.5f, cb.getFloat(24), 1e-4f)
        // And linear scale (0.0015 m) sits in scales_opacity right after means (offset 32).
        assertEquals(0.0015f, cb.getFloat(32), 1e-5f)
    }

    @Test
    fun estimateLaserScaleHandlesLargeColmapScaleFactor() {
        val intr = Intrinsics(640, 480, 500.0, 500.0, 320.0, 240.0, 0.0, 0.0, 0.0, 0.0, 0.0, true)
        val q = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        val pose = LaserMvsFusion.CameraPose(LaserMvsFusion.quatToMat(q), doubleArrayOf(0.0, 0.0, 0.0), q)

        // COLMAP arbitrary scale is 8x smaller than metric (z_colmap = 0.10, z_laser = 0.80 => scale = 8.0).
        val gridCount = 20 * 20
        val worldXyz = FloatArray(gridCount * 6)
        val laserCamM = FloatArray(gridCount * 3)
        var k = 0
        for (iy in 0 until 20) {
            for (ix in 0 until 20) {
                val u = 160.0 + ix * 16.0
                val v = 120.0 + iy * 12.0
                val rx = (u - intr.cx) / intr.fx
                val ry = (v - intr.cy) / intr.fy
                val zColmap = 0.10
                val zLaser = 0.80
                worldXyz[k * 6] = (rx * zColmap).toFloat()
                worldXyz[k * 6 + 1] = (ry * zColmap).toFloat()
                worldXyz[k * 6 + 2] = zColmap.toFloat()
                laserCamM[k * 3] = (rx * zLaser).toFloat()
                laserCamM[k * 3 + 1] = (ry * zLaser).toFloat()
                laserCamM[k * 3 + 2] = zLaser.toFloat()
                k++
            }
        }
        val lf = LaserMvsFusion.LaserFrame(0, 1_000_000L, pose, laserCamM, null, pose)
        val scale = LaserMvsFusion.estimateLaserScale(listOf(lf), worldXyz, 6, gridCount, intr)
        assertEquals(8.0, scale, 0.15)
    }

    @Test
    fun laserInitAppliesEstimatedColmapScaleToMetricLaserPoints() {
        val scanDir = tmp.newFolder("scan-scaled")
        val sparseDir = File(scanDir, "sparse/0").apply { mkdirs() }
        File(scanDir, "images").mkdirs()
        val hybridLaserDir = File(scanDir, "hybrid/laser").apply { mkdirs() }

        // COLMAP world is at 0.5x metric scale: dense plane sits at z = 0.25 while metric laser reads z = 0.50 m.
        val count = 100
        val densePly = File(scanDir, "dense.ply")
        val hdr = "ply\nformat binary_little_endian 1.0\n" +
            "element vertex $count\n" +
            "property float x\nproperty float y\nproperty float z\n" +
            "property float nx\nproperty float ny\nproperty float nz\n" +
            "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n"
        val lbb = ByteBuffer.allocate(count * 12).order(ByteOrder.LITTLE_ENDIAN)
        FileOutputStream(densePly).use { os ->
            os.write(hdr.toByteArray(Charsets.US_ASCII))
            val bb = ByteBuffer.allocate(count * 27).order(ByteOrder.LITTLE_ENDIAN)
            for (iy in 0 until 10) {
                for (ix in 0 until 10) {
                    val u = 220.0 + ix * 20.0
                    val v = 140.0 + iy * 20.0
                    val rx = (u - 320.0) / 500.0
                    val ry = (v - 240.0) / 500.0
                    bb.putFloat((rx * 0.25).toFloat()); bb.putFloat((ry * 0.25).toFloat()); bb.putFloat(0.25f)
                    bb.putFloat(0f); bb.putFloat(0f); bb.putFloat(-1f)
                    bb.put(180.toByte()); bb.put(180.toByte()); bb.put(180.toByte())
                    // Laser stored in millimetres at z = 500 mm (0.50 m).
                    lbb.putFloat((rx * 500.0).toFloat()); lbb.putFloat((ry * 500.0).toFloat()); lbb.putFloat(500f)
                }
            }
            os.write(bb.array())
        }
        File(sparseDir, "cameras.txt").writeText("1 PINHOLE 640 480 500 500 320 240\n")
        File(sparseDir, "images.txt").writeText("1 1 0 0 0 0 0 0 1 f00000.jpg\n\n")
        File(hybridLaserDir, "f00000.f32").writeBytes(lbb.array())
        val hybridJson = JSONObject()
            .put("images", JSONArray().put(JSONObject().put("file", "f00000.jpg").put("frame", 0).put("sensor_timestamp_ns", 1_000_000_000L)))
            .put("laser_frames", JSONArray().put(JSONObject().put("frame", 0).put("sensor_timestamp_ns", 1_000_000_000L).put("laser", "hybrid/laser/f00000.f32")))
        File(scanDir, "hybrid/hybrid.json").writeText(hybridJson.toString())

        val initPly = File(scanDir, "init.ply")
        assertTrue(LaserInit.build(Scan(scanDir), initPly))
        val chk = File(scanDir, "init.d3gs")
        assertTrue(Cross3dgsCheckpoint.fromPly(initPly, chk))
        assertEquals(count, Cross3dgsCheckpoint.readCount(chk))
        val cb = ByteBuffer.wrap(chk.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        // Z of first Gaussian must land at COLMAP's 0.25 (not metric 0.50)!
        assertEquals(0.25f, cb.getFloat(24), 0.01f)
    }

    private fun poseFromCentreAndYawZ(cx: Double, cy: Double, cz: Double, yawRad: Double): LaserMvsFusion.CameraPose {
        val q = doubleArrayOf(cos(yawRad / 2.0), 0.0, 0.0, sin(yawRad / 2.0))
        val r = LaserMvsFusion.quatToMat(q)
        val t = doubleArrayOf(
            -(r[0] * cx + r[1] * cy + r[2] * cz),
            -(r[3] * cx + r[4] * cy + r[5] * cz),
            -(r[6] * cx + r[7] * cy + r[8] * cz),
        )
        return LaserMvsFusion.CameraPose(r, t, q)
    }

    private fun poseToRowMajor16(p: LaserMvsFusion.CameraPose): String = listOf(
        p.r[0], p.r[1], p.r[2], p.t[0],
        p.r[3], p.r[4], p.r[5], p.t[1],
        p.r[6], p.r[7], p.r[8], p.t[2],
        0.0, 0.0, 0.0, 1.0,
    ).joinToString(" ")

    @Test
    fun vioAnchoredInterpolationRecoversCurvedMotionUnderArbitraryWorldOffset() {
        // True camera follows a curved arc in BA world:
        //   A (t=0): C=(0.0, 0.0, 0.0), yaw=0
        //   M (t=0.5): C=(0.10, 0.08, 0.02), yaw=20 deg  <-- 8 cm curved detour off the straight chord!
        //   B (t=1): C=(0.20, 0.0, 0.0), yaw=30 deg
        val baA = poseFromCentreAndYawZ(0.0, 0.0, 0.0, 0.0)
        val baMTrue = poseFromCentreAndYawZ(0.10, 0.08, 0.02, Math.toRadians(20.0))
        val baB = poseFromCentreAndYawZ(0.20, 0.0, 0.0, Math.toRadians(30.0))

        // VIO observes the exact same motion in a completely different world frame:
        // rotated 90 deg around Z and shifted by (+1.5, -2.0, +0.4) m!
        val qWorld = doubleArrayOf(cos(Math.PI / 4.0), 0.0, 0.0, sin(Math.PI / 4.0))
        val rw = LaserMvsFusion.quatToMat(qWorld)
        fun toVioWorld(ba: LaserMvsFusion.CameraPose): LaserMvsFusion.CameraPose {
            // R_vio = R_ba * R_w, C_vio = R_w^T * C_ba + origin
            val rVio = DoubleArray(9)
            for (i in 0 until 3) for (j in 0 until 3) {
                rVio[i * 3 + j] = ba.r[i * 3] * rw[j] + ba.r[i * 3 + 1] * rw[3 + j] + ba.r[i * 3 + 2] * rw[6 + j]
            }
            val c = ba.centre()
            val cv = doubleArrayOf(
                rw[0] * c[0] + rw[3] * c[1] + rw[6] * c[2] + 1.5,
                rw[1] * c[0] + rw[4] * c[1] + rw[7] * c[2] - 2.0,
                rw[2] * c[0] + rw[5] * c[1] + rw[8] * c[2] + 0.4,
            )
            val tVio = doubleArrayOf(
                -(rVio[0] * cv[0] + rVio[1] * cv[1] + rVio[2] * cv[2]),
                -(rVio[3] * cv[0] + rVio[4] * cv[1] + rVio[5] * cv[2]),
                -(rVio[6] * cv[0] + rVio[7] * cv[1] + rVio[8] * cv[2]),
            )
            return LaserMvsFusion.CameraPose(rVio, tVio, LaserMvsFusion.matToQuat(rVio))
        }

        val vioA = toVioWorld(baA)
        val vioM = toVioWorld(baMTrue)
        val vioB = toVioWorld(baB)

        val recovered = LaserMvsFusion.interpolatePoseVioAnchored(baA, baB, vioA, vioB, vioM, 0.5, 1.0)
        assertNotNull(recovered)
        val cm = recovered!!.centre()
        // Curved detour (0.10, 0.08, 0.02) recovered to sub-micron accuracy (linear SLERP would give y=0.00!).
        assertEquals(0.10, cm[0], 1e-6)
        assertEquals(0.08, cm[1], 1e-6)
        assertEquals(0.02, cm[2], 1e-6)
        for (i in 0 until 9) assertEquals(baMTrue.r[i], recovered.r[i], 1e-6)
    }

    @Test
    fun vioAnchoredInterpolationRejectsCorruptedVioJumpAndFallsBackInLoader() {
        val baA = poseFromCentreAndYawZ(0.0, 0.0, 0.0, 0.0)
        val baB = poseFromCentreAndYawZ(0.10, 0.0, 0.0, 0.0)
        // VIO suffered a 45-degree glitch between A and B -> sanity gate must return null.
        val vioA = poseFromCentreAndYawZ(0.0, 0.0, 0.0, 0.0)
        val vioM = poseFromCentreAndYawZ(0.05, 0.0, 0.0, Math.toRadians(22.0))
        val vioB = poseFromCentreAndYawZ(0.10, 0.0, 0.0, Math.toRadians(45.0))
        val rejected = LaserMvsFusion.interpolatePoseVioAnchored(baA, baB, vioA, vioB, vioM, 0.5, 1.0)
        assertEquals(null, rejected)
    }

    @Test
    fun loadBaLaserFramesUsesVioAnchoringForIntermediateLaserFrames() {
        val scanDir = tmp.newFolder("scan-vio-anchor")
        val sparseDir = File(scanDir, "sparse/0").apply { mkdirs() }
        File(scanDir, "images").mkdirs()
        val hybridLaserDir = File(scanDir, "hybrid/laser").apply { mkdirs() }

        val ba0 = poseFromCentreAndYawZ(0.0, 0.0, 0.0, 0.0)
        val ba5True = poseFromCentreAndYawZ(0.10, 0.06, 0.0, 0.0) // 6 cm curved arc off chord
        val ba10 = poseFromCentreAndYawZ(0.20, 0.0, 0.0, 0.0)

        File(sparseDir, "cameras.txt").writeText("1 PINHOLE 640 480 500 500 320 240\n")
        File(sparseDir, "images.txt").writeText(
            "1 1 0 0 0 0 0 0 1 f00000.jpg\n\n" +
                "2 1 0 0 0 -0.2 0 0 1 f00010.jpg\n\n",
        )
        val f5 = File(hybridLaserDir, "f00005.f32")
        val lbb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        lbb.putFloat(0f); lbb.putFloat(0f); lbb.putFloat(400f)
        f5.writeBytes(lbb.array())

        val hybridJson = JSONObject()
            .put(
                "images",
                JSONArray()
                    .put(JSONObject().put("file", "f00000.jpg").put("frame", 0).put("sensor_timestamp_ns", 1_000_000_000L).put("vio_pose16", poseToRowMajor16(ba0)))
                    .put(JSONObject().put("file", "f00010.jpg").put("frame", 10).put("sensor_timestamp_ns", 2_000_000_000L).put("vio_pose16", poseToRowMajor16(ba10))),
            )
            .put(
                "laser_frames",
                JSONArray()
                    .put(JSONObject().put("frame", 5).put("sensor_timestamp_ns", 1_500_000_000L).put("laser", "hybrid/laser/f00005.f32").put("vio_pose16", poseToRowMajor16(ba5True))),
            )
        File(scanDir, "hybrid/hybrid.json").writeText(hybridJson.toString())

        val poses = LaserMvsFusion.readColmapPoses(File(sparseDir, "images.txt"))!!
        val frames = LaserMvsFusion.loadBaLaserFramesTyped(Scan(scanDir), poses, scale = 1.0, vioToPoseScale = 1.0)
        assertEquals(1, frames.size)
        assertTrue("intermediate frame with valid VIO must be VIO-anchored", frames[0].vioAnchored)
        val c = frames[0].pose.centre()
        assertEquals(0.10, c[0], 1e-5)
        assertEquals(0.06, c[1], 1e-5)
        assertEquals(0.0, c[2], 1e-5)
    }
}
