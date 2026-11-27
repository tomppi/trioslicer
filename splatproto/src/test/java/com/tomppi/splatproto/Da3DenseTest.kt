package com.tomppi.splatproto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Da3DenseTest {

    private fun rotationY(rad: Double): DoubleArray {
        val c = cos(rad); val s = sin(rad)
        return doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
    }

    private fun rotationX(rad: Double): DoubleArray {
        val c = cos(rad); val s = sin(rad)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
    }

    private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
        return out
    }

    private fun poseFromCentre(r: DoubleArray, centre: DoubleArray): Da3Dense.Pose {
        // x_cam = R * (x_world - C) = R * x_world - R * C
        val t = doubleArrayOf(
            -(r[0] * centre[0] + r[1] * centre[1] + r[2] * centre[2]),
            -(r[3] * centre[0] + r[4] * centre[1] + r[5] * centre[2]),
            -(r[6] * centre[0] + r[7] * centre[1] + r[8] * centre[2]),
        )
        return Da3Dense.Pose(r, t)
    }

    private fun poseTo16(p: Da3Dense.Pose, tScale: Double = 1.0): DoubleArray = doubleArrayOf(
        p.r[0], p.r[1], p.r[2], p.t[0] * tScale,
        p.r[3], p.r[4], p.r[5], p.t[1] * tScale,
        p.r[6], p.r[7], p.r[8], p.t[2] * tScale,
        0.0, 0.0, 0.0, 1.0,
    )

    private fun writeScanDir(
        vioPose: DoubleArray?,
        boardPoseMm: DoubleArray? = null,
        withColmap: Boolean = false,
    ): Scan {
        val dir = kotlin.io.path.createTempDirectory("da3-test-").toFile()
        File(dir, "images").mkdirs()
        val scan = Scan(dir)
        val camJson = JSONObject()
            .put("index", 0).put("label", "EasyScanner").put("cameraId", "0")
            .put("physicalId", JSONObject.NULL).put("sensorOrientation", 0)
            .put("width", 640).put("height", 480)
            .put("fx", 441.07).put("fy", 434.62).put("cx", 329.40).put("cy", 239.85)
            .put("k1", 0.1825).put("k2", -0.6099).put("p1", 0.0).put("p2", 0.0).put("k3", 0.0)
            .put("calibrated", true).put("lensPosition", JSONObject.NULL)
        val imgJson = JSONObject()
            .put("file", "f00000.jpg").put("camera", 0).put("rig", -1)
            .put("up", JSONArray().put(0.0).put(1.0).put(0.0))
            .put("timestampNs", 1_000_000L).put("exposureNs", 0L).put("iso", 0)
            .put("focusDiopters", 0.0).put("torch", false).put("rollingShutterSkewNs", 0L)
        if (vioPose != null) imgJson.put("vioPose", JSONArray().apply { vioPose.forEach { put(it) } })
        val metaJson = JSONObject().put("format", 1)
            .put("cameras", JSONArray().put(camJson))
            .put("images", JSONArray().put(imgJson))
        scan.meta.writeText(metaJson.toString(2))
        if (boardPoseMm != null) {
            val hdir = File(dir, "hybrid").apply { mkdirs() }
            val row = JSONObject()
                .put("file", "f00000.jpg")
                .put("frame", 0)
                .put("board_pose16", boardPoseMm.joinToString(" "))
                .put("vio_pose16", vioPose?.joinToString(" ") ?: JSONObject.NULL)
            val doc = JSONObject().put("images", JSONArray().put(row)).put("laser_frames", JSONArray().put(row))
            File(hdir, "hybrid.json").writeText(doc.toString(2))
        }
        if (withColmap) {
            val sp = File(dir, "sparse/0").apply { mkdirs() }
            File(sp, "images.txt").writeText(
                "# Image list\n1 1 0 0 0 -5.0 -12.0 -0.2 1 f00000.jpg\n0 0 -1\n"
            )
        }
        return scan
    }

    @Test
    fun prefersVioOverBoardAndColmapByDefault() {
        val vioP = poseFromCentre(rotationY(0.25), doubleArrayOf(0.1, -0.2, 0.3))
        val brdP = poseFromCentre(rotationX(-0.15), doubleArrayOf(0.4, 0.5, 0.6))
        val scan = writeScanDir(poseTo16(vioP, 1.0), poseTo16(brdP, 1000.0), withColmap = true)

        val sel = Da3Dense.selectPoses(scan)
        assertEquals(Da3Dense.PoseSource.VIO, sel.source)
        val c = sel.poses["f00000.jpg"]!!.cameraCentre()
        assertEquals(0.1, c[0], 1e-6)
        assertEquals(-0.2, c[1], 1e-6)
        assertEquals(0.3, c[2], 1e-6)

        // Explicit preferBoard still works when requested.
        val brdSel = Da3Dense.selectPoses(scan, preferVio = true, preferBoard = true)
        assertEquals(Da3Dense.PoseSource.BOARD, brdSel.source)
        val bc = brdSel.poses["f00000.jpg"]!!.cameraCentre()
        assertEquals(0.4, bc[0], 1e-6)
        assertEquals(0.5, bc[1], 1e-6)
        assertEquals(0.6, bc[2], 1e-6)
    }

    @Test
    fun fallsBackToColmapOnlyWhenNoValidVioExists() {
        val noVio = writeScanDir(vioPose = null, withColmap = true)
        val sel1 = Da3Dense.selectPoses(noVio)
        assertEquals(Da3Dense.PoseSource.COLMAP, sel1.source)
        assertNotNull(sel1.poses["f00000.jpg"])

        // All-zero VIO matrix must be rejected, triggering COLMAP fallback.
        val zeroVio = writeScanDir(vioPose = DoubleArray(16), withColmap = true)
        val sel2 = Da3Dense.selectPoses(zeroVio)
        assertEquals(Da3Dense.PoseSource.COLMAP, sel2.source)
    }

    @Test
    fun vioPosesWorkWithoutSparseColmapDirectory() {
        val vioP = poseFromCentre(rotationY(0.1), doubleArrayOf(0.05, 0.0, -0.1))
        val scan = writeScanDir(poseTo16(vioP, 1.0), withColmap = false)
        val sel = Da3Dense.selectPoses(scan)
        assertEquals(Da3Dense.PoseSource.VIO, sel.source)
        assertEquals(1, sel.poses.size)
    }

    @Test
    fun singleFrameSelectionPrefersFirstFrameWithUsableLaser() {
        val dir = kotlin.io.path.createTempDirectory("da3-pick-").toFile()
        val small = File(dir, "small.f32").apply { writeBytes(ByteArray(5 * 12)) }
        val strong = File(dir, "strong.f32").apply { writeBytes(ByteArray(40 * 12)) }
        val registered = listOf("f0.jpg" to 0, "f1.jpg" to 0, "f2.jpg" to 0)
        val laserMap = mapOf("f1.jpg" to small, "f2.jpg" to strong)
        val picked = Da3Dense.selectFrames(registered, laserMap, maxFrames = 1)
        assertEquals(1, picked.size)
        assertEquals("f2.jpg", picked.first().first)
    }

    @Test
    fun cameraProjectAndUndistortRoundTripMatchesBrownConrady() {
        val cam = Da3Dense.Camera(
            fx = 441.074, fy = 434.617, cx = 329.399, cy = 239.852,
            width = 640, height = 480, k1 = 0.18246, k2 = -0.60987,
        )
        val z = 0.55
        for (ix in -4..4) {
            for (iy in -3..3) {
                val x = ix * 0.06
                val y = iy * 0.06
                val uv = cam.project(x, y, z)
                assertNotNull(uv)
                val ray = cam.undistort(uv!![0], uv[1])
                assertEquals(x / z, ray[0], 1e-5)
                assertEquals(y / z, ray[1], 1e-5)
            }
        }
    }

    @Test
    fun singleFramePatchPlacementInVioWorldRecoversTrueSurfaceAndLaserAlignment() {
        val cam = Da3Dense.Camera(
            fx = 441.074, fy = 434.617, cx = 329.399, cy = 239.852,
            width = 640, height = 480, k1 = 0.18246, k2 = -0.60987,
        )
        val r = mul3(rotationX(0.22), rotationY(-0.35))
        val trueCentre = doubleArrayOf(0.18, -0.27, 0.42)
        val pose = poseFromCentre(r, trueCentre)
        val trueScale = 2.35

        // Ground-truth tilted plane in camera coordinates: z(x, y) = 0.52 + 0.08 * x - 0.05 * y (metres).
        fun trueDepthForRay(rx: Double, ry: Double): Double = 0.52 / (1.0 - 0.08 * rx + 0.05 * ry)

        val depth = FloatArray(Da3Dense.SIZE * Da3Dense.SIZE)
        for (y in 0 until Da3Dense.SIZE) {
            val vf = (y + 0.5) * cam.height / Da3Dense.SIZE
            for (x in 0 until Da3Dense.SIZE) {
                val uf = (x + 0.5) * cam.width / Da3Dense.SIZE
                val ray = cam.undistort(uf, vf)
                val zTrue = trueDepthForRay(ray[0], ray[1])
                // Add a small +6 mm systematic bump on a horizontal band so laser refinement is exercised.
                val bump = if (y in 200..260) 0.006 else 0.0
                depth[y * Da3Dense.SIZE + x] = ((zTrue + bump) / trueScale).toFloat()
            }
        }

        // Build 200 metric laser points on the true plane along a vertical stripe near u = 380.
        val laserList = ArrayList<Float>()
        for (k in 0 until 200) {
            val v = 40.0 + k * 2.0
            val u = 380.0 + 10.0 * sin(k * 0.04)
            val ray = cam.undistort(u, v)
            val zTrue = trueDepthForRay(ray[0], ray[1])
            laserList.add((ray[0] * zTrue).toFloat())
            laserList.add((ray[1] * zTrue).toFloat())
            laserList.add(zTrue.toFloat())
        }
        val laserCamM = FloatArray(laserList.size) { laserList[it] }

        val pairs = Da3Dense.laserPairs(depth, laserCamM, cam)
        assertEquals(200, pairs.size)
        val fit = Da3Dense.fitAffine(pairs, 20)
        assertNotNull(fit)
        assertEquals(trueScale, fit!!.scale, 0.02)

        val patch = Da3Dense.buildSingleFramePatch(
            frameFile = "f00000.jpg",
            frameIndex = 0,
            depth = depth,
            scale = fit.scale,
            offset = fit.offset,
            pose = pose,
            camera = cam,
            laserCamM = laserCamM,
            isMaskedOut = { _, _ -> false },
            sampleRgb = { _, _ -> intArrayOf(120, 140, 160) },
        )

        val pm = patch.placement
        assertEquals(Da3Dense.GRID * Da3Dense.GRID, patch.vertexCount)
        assertTrue("expected coarse mesh triangles", pm.meshFaces > 100_000)
        assertEquals(trueCentre[0], pm.cameraCentreM[0], 1e-6)
        assertEquals(trueCentre[1], pm.cameraCentreM[1], 1e-6)
        assertEquals(trueCentre[2], pm.cameraCentreM[2], 1e-6)
        assertTrue("reprojection round-trip should be exact, was ${pm.maxReprojErrPx}", pm.maxReprojErrPx < 1e-3)
        assertTrue("expected laser refinement cells on STRIDE grid", pm.laserRefinedGridCells >= 80)
        assertTrue("post-refine world alignment should be within 2.5 mm, was ${pm.postRefineWorldMedianErrMm}",
            pm.postRefineWorldMedianErrMm < 2.5)

        // Verify world-space laser bounds sit inside the VIO-world patch bounds.
        assertNotNull(pm.laserBoundsMinM)
        assertNotNull(pm.laserBoundsMaxM)
        for (axis in 0..2) {
            assertTrue(pm.laserBoundsMinM!![axis] >= pm.patchBoundsMinM[axis] - 0.01f)
            assertTrue(pm.laserBoundsMaxM!![axis] <= pm.patchBoundsMaxM[axis] + 0.01f)
        }

        // Verify mesh PLY writes cleanly with the declared vertex and face counts.
        val meshFile = File(kotlin.io.path.createTempDirectory("da3-mesh-").toFile(), "mesh.ply")
        Da3Dense.writeMeshPly(meshFile, patch.positions, patch.colors, patch.vertexCount, patch.faces)
        assertTrue(meshFile.isFile && meshFile.length() > 100_000L)
        val header = meshFile.inputStream().bufferedReader().use { r ->
            buildString {
                while (true) {
                    val line = r.readLine() ?: break
                    append(line).append('\n')
                    if (line == "end_header") break
                }
            }
        }
        assertTrue(header.contains("element vertex ${patch.vertexCount}"))
        assertTrue(header.contains("element face ${pm.meshFaces}"))
    }

    @Test
    fun meshDropsTrianglesAcrossDepthDiscontinuities() {
        val cam = Da3Dense.Camera(500.0, 500.0, 320.0, 240.0, 640, 480)
        val pose = poseFromCentre(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0))
        val depth = FloatArray(Da3Dense.SIZE * Da3Dense.SIZE) { idx ->
            val x = idx % Da3Dense.SIZE
            if (x < Da3Dense.SIZE / 2) 0.5f else 1.5f
        }
        val patch = Da3Dense.buildSingleFramePatch(
            frameFile = "step.jpg", frameIndex = 0, depth = depth, scale = 1.0, offset = 0.0,
            pose = pose, camera = cam, laserCamM = FloatArray(0),
            isMaskedOut = { _, _ -> false }, sampleRgb = { _, _ -> intArrayOf(10, 20, 30) },
        )
        // Expect one missing column of quads across the step: (GRID-2)*(GRID-1)*2 faces.
        val expectedFaces = (Da3Dense.GRID - 2) * (Da3Dense.GRID - 1) * 2
        assertEquals(expectedFaces, patch.placement.meshFaces)
        // Also verify no face has an edge jumping > 0.2 m in Z.
        for (f in 0 until patch.faces.size / 3) {
            val a = patch.faces[f * 3]; val b = patch.faces[f * 3 + 1]; val c = patch.faces[f * 3 + 2]
            val za = patch.positions[a * 3 + 2]; val zb = patch.positions[b * 3 + 2]; val zc = patch.positions[c * 3 + 2]
            assertTrue(abs(za - zb) < 0.1f && abs(za - zc) < 0.1f && abs(zb - zc) < 0.1f)
        }
    }

    @Test
    fun motionModeClassifiesTiltingMovingAndOutliers() {
        val id = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        // Tilting: 1 cm jitter at 0.5 m depth => parallax 0.02 < 0.04
        val tiltPoses = (0 until 20).map { i ->
            poseFromCentre(rotationY(i * 0.02), doubleArrayOf(0.0005 * i, 0.0, 0.0))
        }
        val tilt = Da3Dense.computeMotionModeFromSamples(tiltPoses, medianLaserDepthM = 0.5)
        assertEquals("tilting", tilt.mode)
        assertTrue(tilt.parallax < Da3Dense.MOTION_PARALLAX_THRESHOLD)

        // Same tilting sequence plus 2 VIO jump outliers at 1.5 m -> robust span still sees tilting.
        val withOutliers = tiltPoses + listOf(
            poseFromCentre(id, doubleArrayOf(1.4, 0.0, 0.0)),
            poseFromCentre(id, doubleArrayOf(1.5, 0.0, 0.0)),
        )
        val robustTilt = Da3Dense.computeMotionModeFromSamples(withOutliers, medianLaserDepthM = 0.5)
        assertEquals("tilting", robustTilt.mode)
        assertTrue(robustTilt.rawTranslationSpanM > 1.3)
        assertTrue(robustTilt.translationSpanM < 0.02)

        // Moving: 15 cm translation at 0.5 m depth => parallax ~0.30 >= 0.04
        val movePoses = (0 until 20).map { i ->
            poseFromCentre(id, doubleArrayOf(0.008 * i, 0.0, 0.0))
        }
        val move = Da3Dense.computeMotionModeFromSamples(movePoses, medianLaserDepthM = 0.5)
        assertEquals("moving", move.mode)
        assertTrue(move.parallax >= Da3Dense.MOTION_PARALLAX_THRESHOLD)
    }

    @Test
    fun writeDa3ReportMergesWithoutForgingReconstructionOk() {
        val dir = kotlin.io.path.createTempDirectory("da3-report-").toFile()
        val report = File(dir, "report.json")
        val mm = Da3Dense.MotionMode("tilting", 10, 0.01, 0.01, 0.5, 0.02)
        Da3Dense.writeDa3Report(report, Da3Dense.PoseSource.VIO, true, mm, null, 67081)
        val first = JSONObject(report.readText())
        assertFalse(first.has("ok"))
        assertEquals("tilting", first.getString("motionMode"))
        assertEquals("vio", first.getJSONObject("da3").getString("poseSource"))

        // Existing SfM fields are preserved when merging.
        report.writeText(JSONObject().put("ok", true).put("sparsePoints", 616).toString())
        Da3Dense.writeDa3Report(report, Da3Dense.PoseSource.VIO, true, mm, null, 67081)
        val merged = JSONObject(report.readText())
        assertTrue(merged.getBoolean("ok"))
        assertEquals(616, merged.getInt("sparsePoints"))
        assertEquals("vio", merged.getJSONObject("da3").getString("poseSource"))
    }

    @Test
    fun verifiesRealScan114140VioPoseAndLaserProjectionIfPresent() {
        val real = File("/root/scan114140")
        if (!File(real, "scan.json").isFile || !File(real, "hybrid/laser/f00000.f32").isFile) return
        // Mirror scan114140 layout into a temp scan dir where hybrid.json lives at hybrid/hybrid.json.
        val tmp = kotlin.io.path.createTempDirectory("da3-real-").toFile()
        File(real, "scan.json").copyTo(File(tmp, "scan.json"))
        File(tmp, "hybrid").mkdirs()
        File(real, "hybrid.json").copyTo(File(tmp, "hybrid/hybrid.json"))
        val laserDir = File(tmp, "hybrid/laser").apply { mkdirs() }
        File(real, "hybrid/laser/f00000.f32").copyTo(File(laserDir, "f00000.f32"))

        val scan = Scan(tmp)
        val sel = Da3Dense.selectPoses(scan)
        assertEquals(Da3Dense.PoseSource.VIO, sel.source)
        assertEquals(128, sel.poses.size)

        val cameras = Da3Dense.parseCameras(JSONObject(scan.meta.readText()))
        val cam = cameras[0]!!
        val pose0 = sel.poses["f00000.jpg"]!!
        val laser0 = Da3Dense.readLaserPointsMeters(File(laserDir, "f00000.f32"))
        assertEquals(344 * 3, laser0.size)

        // Every laser point from frame 0 must project cleanly inside 640x480.
        var inBounds = 0
        var i = 0
        while (i + 2 < laser0.size) {
            val uv = cam.project(laser0[i].toDouble(), laser0[i + 1].toDouble(), laser0[i + 2].toDouble())
            if (uv != null && uv[0] in 0.0..cam.width.toDouble() && uv[1] in 0.0..cam.height.toDouble()) inBounds++
            i += 3
        }
        assertEquals(344, inBounds)

        // Verify single-frame patch placement in the real VIO pose using a synthetic constant-depth DA3 map.
        val synthDepth = FloatArray(Da3Dense.SIZE * Da3Dense.SIZE) { 1.0f }
        val fit = Da3Dense.fitAffine(Da3Dense.laserPairs(synthDepth, laser0, cam), 20)
        assertNotNull(fit)
        val patch = Da3Dense.buildSingleFramePatch(
            frameFile = "f00000.jpg", frameIndex = 0, depth = synthDepth,
            scale = fit!!.scale, offset = 0.0, pose = pose0, camera = cam, laserCamM = laser0,
            isMaskedOut = { _, _ -> false }, sampleRgb = { _, _ -> intArrayOf(200, 200, 200) },
        )
        assertTrue(patch.placement.laserRefinedGridCells > 100)
        assertTrue("reprojection error on real Fold intrinsics must be tiny", patch.placement.maxReprojErrPx < 1e-3)
        assertTrue("post-refine world alignment must be sub-5mm", patch.placement.postRefineWorldMedianErrMm < 5.0)

        // Also copy all real laser frames from scan114140 and verify full-sweep AdaptiveDepthMesh refinement.
        val srcLaserDir = File(real, "hybrid/laser")
        srcLaserDir.listFiles { f -> f.name.endsWith(".f32") }?.forEach { f ->
            val dst = File(laserDir, f.name)
            if (!dst.exists()) f.copyTo(dst)
        }
        val sweep = Da3Dense.loadLaserSweep(scan, Da3Dense.PoseSource.VIO)
        assertEquals(382, sweep.size)
        val depthCam = com.rawmechatronics.easyscanner.DepthCamera(
            cam.fx, cam.fy, cam.cx, cam.cy, cam.width, cam.height,
            cam.k1, cam.k2, cam.p1, cam.p2, cam.k3,
        )
        val adaptive = com.rawmechatronics.easyscanner.AdaptiveDepthMesh(
            refPose = com.rawmechatronics.easyscanner.RigidPose(pose0.r, pose0.t),
            camera = depthCam,
            rawDepth = synthDepth,
            scale = fit.scale,
            offset = 0.0,
            rgb518 = ByteArray(Da3Dense.SIZE * Da3Dense.SIZE * 3) { 160.toByte() },
        )
        var totalAccepted = 0
        var totalOutliers = 0
        for (sf in sweep) {
            val pts = Da3Dense.readLaserPointsMeters(sf.laserFile)
            val (st, _) = adaptive.addLaserFrame(sf.pose, pts)
            totalAccepted += st.acceptedPoints
            totalOutliers += st.rejectedOutliers
        }
        val meshData = adaptive.buildMesh()
        assertTrue("expected thousands of swept laser points accepted, got $totalAccepted", totalAccepted > 10_000)
        assertTrue("expected hundreds of fine laser-refined blocks, got ${meshData.refinedBlocks}", meshData.refinedBlocks > 200)
        assertTrue("expected adaptive mesh triangles > coarse baseline", meshData.triangleCount > 34_000)
    }

    @Test
    fun coverageKeyframeSelectionUsesOneForTiltingAndSpacedFramesForMoving() {
        val id = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val dir = kotlin.io.path.createTempDirectory("da3-cov-").toFile()
        val laserFile = File(dir, "f.f32").apply { writeBytes(ByteArray(30 * 12)) }
        val reg = (0 until 20).map { i -> "f$i.jpg" to 0 }
        val laserMap = reg.associate { (name, _) -> name to laserFile }
        val poses = (0 until 20).associate { i -> "f$i.jpg" to poseFromCentre(id, doubleArrayOf(i * 0.01, 0.0, 0.0)) }

        val tiltMode = Da3Dense.MotionMode("tilting", 20, 0.01, 0.01, 0.5, 0.02)
        val tiltSel = Da3Dense.selectCoverageKeyframes(reg, poses, laserMap, tiltMode)
        assertEquals(1, tiltSel.size)

        val moveMode = Da3Dense.MotionMode("moving", 20, 0.19, 0.19, 0.5, 0.38)
        val moveSel = Da3Dense.selectCoverageKeyframes(reg, poses, laserMap, moveMode)
        // Threshold is 0.05 * 0.5 = 0.025 m => step every 3 frames (3 cm) across 19 cm => ~7 keyframes.
        assertTrue("expected 5..9 coverage-spaced keyframes, got ${moveSel.size}", moveSel.size in 5..9)
    }
}
