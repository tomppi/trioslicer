package com.rawmechatronics.easyscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class PatchMapTest {

    private fun rotY(rad: Double): DoubleArray {
        val c = cos(rad); val s = sin(rad)
        return doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
    }

    private fun rotX(rad: Double): DoubleArray {
        val c = cos(rad); val s = sin(rad)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
    }

    private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        return o
    }

    private fun poseAt(r: DoubleArray, centre: DoubleArray): RigidPose {
        val t = doubleArrayOf(
            -(r[0] * centre[0] + r[1] * centre[1] + r[2] * centre[2]),
            -(r[3] * centre[0] + r[4] * centre[1] + r[5] * centre[2]),
            -(r[6] * centre[0] + r[7] * centre[1] + r[8] * centre[2]),
        )
        return RigidPose(r, t)
    }

    private fun idRot() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    private fun camera() = DepthCamera(441.0, 435.0, 320.0, 240.0, 640, 480)

    /** Non-planar world surface z(x, y) with a smooth Gaussian dome + sinusoidal relief so all 6 DoF are observable. */
    private fun surfaceZ(x: Double, y: Double): Double {
        val r2 = x * x + y * y
        return 0.52 - 0.07 * exp(-r2 / 0.015) + 0.018 * sin(x * 18.0) * cos(y * 16.0)
    }

    private fun renderSyntheticDepth(pose: RigidPose, cam: DepthCamera, scale: Double): Pair<FloatArray, FloatArray> {
        // Compute true metric depth on the 518x518 grid by 6-step ray-surface intersection.
        val depth = FloatArray(AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE)
        val c = pose.cameraCentre()
        for (y in 0 until AdaptiveDepthMesh.SIZE) {
            val vf = (y + 0.5) * cam.height / AdaptiveDepthMesh.SIZE
            for (x in 0 until AdaptiveDepthMesh.SIZE) {
                val uf = (x + 0.5) * cam.width / AdaptiveDepthMesh.SIZE
                val ray = cam.undistort(uf, vf)
                val dWorld = doubleArrayOf(
                    pose.r[0] * ray[0] + pose.r[3] * ray[1] + pose.r[6],
                    pose.r[1] * ray[0] + pose.r[4] * ray[1] + pose.r[7],
                    pose.r[2] * ray[0] + pose.r[5] * ray[1] + pose.r[8],
                )
                var z = 0.5
                repeat(6) {
                    val wx = c[0] + dWorld[0] * z
                    val wy = c[1] + dWorld[1] * z
                    val zSurf = surfaceZ(wx, wy)
                    z = ((zSurf - c[2]) / dWorld[2]).coerceIn(0.1, 2.0)
                }
                depth[y * AdaptiveDepthMesh.SIZE + x] = (z / scale).toFloat()
            }
        }
        // Also sample 120 exact metric laser points along a vertical stripe in this camera.
        val laser = ArrayList<Float>(120 * 3)
        for (k in 0 until 120) {
            val v = 60.0 + k * 3.0
            val u = 320.0
            val ray = cam.undistort(u, v)
            val dWorld = doubleArrayOf(
                pose.r[0] * ray[0] + pose.r[3] * ray[1] + pose.r[6],
                pose.r[1] * ray[0] + pose.r[4] * ray[1] + pose.r[7],
                pose.r[2] * ray[0] + pose.r[5] * ray[1] + pose.r[8],
            )
            var z = 0.5
            repeat(6) {
                val wx = c[0] + dWorld[0] * z
                val wy = c[1] + dWorld[1] * z
                val zSurf = surfaceZ(wx, wy)
                z = ((zSurf - c[2]) / dWorld[2]).coerceIn(0.1, 2.0)
            }
            laser.add((ray[0] * z).toFloat())
            laser.add((ray[1] * z).toFloat())
            laser.add(z.toFloat())
        }
        return depth to FloatArray(laser.size) { laser[it] }
    }

    @Test
    fun markerBoardTransferGivesExactZeroDriftPose() {
        val worldToCam0 = poseAt(mul3(rotX(0.15), rotY(-0.22)), doubleArrayOf(0.12, -0.05, 0.04))
        val worldToBoard = poseAt(mul3(rotY(0.35), rotX(-0.12)), doubleArrayOf(0.05, 0.08, 0.55))
        val worldToCam1 = poseAt(mul3(rotY(0.18), rotX(0.09)), doubleArrayOf(0.24, 0.03, 0.02))
        // T_board->cam = T_world->cam * inv(T_world->board)
        val boardToCam0 = LaserIcp.compose(worldToCam0, LaserIcp.invert(worldToBoard))
        val boardToCam1 = LaserIcp.compose(worldToCam1, LaserIcp.invert(worldToBoard))

        val recovered = LaserIcp.poseFromSharedBoard(worldToCam0, boardToCam0, boardToCam1)
        assertEquals(0.0, LaserIcp.translationDeltaM(worldToCam1, recovered), 1e-9)
        assertEquals(0.0, LaserIcp.rotationDeltaDeg(worldToCam1, recovered), 1e-7)
    }

    @Test
    fun icpRecoversKnownTranslationAndRotationDrift() {
        val cam = camera()
        val truePose0 = poseAt(idRot(), doubleArrayOf(0.0, 0.0, 0.0))
        val (depth0, _) = renderSyntheticDepth(truePose0, cam, scale = 1.0)
        val rgb = ByteArray(AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE * 3) { 128.toByte() }
        val mesh0 = AdaptiveDepthMesh(truePose0, cam, depth0, scale = 1.0, offset = 0.0, rgb518 = rgb).buildMesh()
        val surf = LaserIcp.SpatialSurface(0.025)
        surf.addPoints(mesh0.positions, mesh0.normals)

        // True camera 1 shifted 5 cm right, yawed slightly; initial VIO pose has 12 mm + 3 deg drift!
        val truePose1 = poseAt(rotY(-0.08), doubleArrayOf(0.05, 0.0, 0.0))
        val driftedPose1 = poseAt(
            mul3(rotX(Math.toRadians(1.8)), rotY(-0.08 + Math.toRadians(2.5))),
            doubleArrayOf(0.05 + 0.010, -0.006, 0.007),
        )
        // Sample 400 exact points on the true surface in camera 1 coordinates.
        val (depth1, _) = renderSyntheticDepth(truePose1, cam, scale = 1.0)
        val src = ArrayList<Float>()
        for (y in 40 until AdaptiveDepthMesh.SIZE - 40 step 22) {
            val vf = (y + 0.5) * cam.height / AdaptiveDepthMesh.SIZE
            for (x in 40 until AdaptiveDepthMesh.SIZE - 40 step 22) {
                val uf = (x + 0.5) * cam.width / AdaptiveDepthMesh.SIZE
                val ray = cam.undistort(uf, vf)
                val z = depth1[y * AdaptiveDepthMesh.SIZE + x].toDouble()
                src.add((ray[0] * z).toFloat()); src.add((ray[1] * z).toFloat()); src.add(z.toFloat())
            }
        }
        val res = LaserIcp.align(surf, FloatArray(src.size) { src[it] }, driftedPose1)
        assertTrue("ICP should accept good overlap, reason=${res.reason}", res.accepted)
        assertTrue("final median residual should be < 1.0 mm, was ${res.finalMedianResidualMm}",
            res.finalMedianResidualMm < 1.0)
        val initErrMm = LaserIcp.translationDeltaM(truePose1, driftedPose1) * 1000.0
        val finalErrMm = LaserIcp.translationDeltaM(truePose1, res.refinedPose) * 1000.0
        assertTrue("ICP should reduce translation drift ($initErrMm -> $finalErrMm mm)", finalErrMm < 2.5)
        assertTrue("ICP should reduce rotation drift", LaserIcp.rotationDeltaDeg(truePose1, res.refinedPose) < 0.6)
    }

    @Test
    fun overlappingMovingPatchesFuseWithPerPatchScaleAndNonOverlappingCreatesSubmap() {
        val cam = camera()
        val rgb = ByteArray(AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE * 3) { 140.toByte() }
        val map = PatchMap()

        // Patch 0 at origin with DA3 relative scale = 2.0
        val pose0 = poseAt(idRot(), doubleArrayOf(0.0, 0.0, 0.0))
        val (depth0, laser0) = renderSyntheticDepth(pose0, cam, scale = 2.0)
        val o0 = map.addPatch(pose0, null, cam, depth0, scale = 2.0, rgb518 = rgb, laserCamM = laser0)
        assertEquals(1, o0.patchCount)
        assertEquals(1, o0.submapCount)

        // Small tilt with laser still inside Patch 0 -> tilting mode must NOT trigger a new patch.
        val tiltPose = poseAt(rotY(Math.toRadians(6.0)), doubleArrayOf(0.003, 0.0, 0.0))
        val covTilt = map.evaluateCoverage(tiltPose, null, laser0)
        assertEquals("tilting", covTilt.motionMode)
        assertFalse("tilting within coverage must not spawn duplicate patch", covTilt.shouldCapture)

        // Move phone 6 cm sideways -> moving mode triggers a new patch with its OWN relative scale = 3.4 + 6 mm drift!
        val truePose1 = poseAt(rotY(-0.06), doubleArrayOf(0.06, 0.0, 0.0))
        val driftedPose1 = poseAt(rotY(-0.06), doubleArrayOf(0.065, -0.004, 0.004))
        val (depth1, laser1) = renderSyntheticDepth(truePose1, cam, scale = 3.4)
        val covMove = map.evaluateCoverage(driftedPose1, null, laser1)
        assertEquals("moving", covMove.motionMode)
        assertTrue("moving 6 cm must trigger new patch", covMove.shouldCapture)

        val o1 = map.addPatch(driftedPose1, null, cam, depth1, scale = 3.4, rgb518 = rgb, laserCamM = laser1)
        assertTrue("overlapping patch must fuse into active submap: ${o1.registration?.reason}", o1.fusedIntoActiveSubmap)
        assertEquals(1, o1.submapCount)
        assertEquals(2, o1.patchCount)
        assertNotNull(o1.registration)
        assertTrue(o1.registration!!.finalMedianResidualMm < 1.5)

        val merged = map.buildActiveSubmapMesh()
        assertNotNull(merged)
        assertTrue(merged!!.triangleCount > 30_000)

        // Patch 2 far away (1.5 m offset, zero overlap) -> MUST stay in a separate submap!
        val farPose = poseAt(idRot(), doubleArrayOf(1.5, 0.0, 0.0))
        val (depth2, laser2) = renderSyntheticDepth(farPose, cam, scale = 1.8)
        val o2 = map.addPatch(farPose, null, cam, depth2, scale = 1.8, rgb518 = rgb, laserCamM = laser2)
        assertFalse("non-overlapping patch must not be forced into submap 0", o2.fusedIntoActiveSubmap)
        assertTrue(o2.createdNewSubmap)
        assertEquals(2, o2.submapCount)
        assertEquals(3, o2.patchCount)
    }

    @Test
    fun overlapScaleAttachmentRecoversNewPatchScaleWithoutLaser() {
        val cam = camera()
        val rgb = ByteArray(AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE * 3) { 140.toByte() }
        val map = PatchMap()
        val pose0 = poseAt(idRot(), doubleArrayOf(0.0, 0.0, 0.0))
        val (depth0, _) = renderSyntheticDepth(pose0, cam, scale = 2.0)
        map.addPatch(pose0, null, cam, depth0, scale = 2.0, rgb518 = rgb, laserCamM = FloatArray(0))

        // Move 6 cm right & 4 cm closer with laser OFF and a completely different DA3 scale = 3.6!
        val pose1 = poseAt(rotY(-0.08), doubleArrayOf(0.06, 0.0, 0.04))
        val (depth1, _) = renderSyntheticDepth(pose1, cam, scale = 3.6)
        val estScale = map.estimateScaleFromExistingSurface(pose1, null, cam, depth1)
        assertNotNull("overlap scale attachment should succeed", estScale)
        assertEquals(3.6, estScale!!, 0.08)
    }

    @Test
    fun coarseToFineIcpRecoversLarge55mmVioDrift() {
        val cam = camera()
        val truePose0 = poseAt(idRot(), doubleArrayOf(0.0, 0.0, 0.0))
        val (depth0, _) = renderSyntheticDepth(truePose0, cam, scale = 1.0)
        val rgb = ByteArray(AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE * 3) { 128.toByte() }
        val mesh0 = AdaptiveDepthMesh(truePose0, cam, depth0, scale = 1.0, offset = 0.0, rgb518 = rgb).buildMesh()
        val surf = LaserIcp.SpatialSurface(0.025)
        surf.addPoints(mesh0.positions, mesh0.normals)

        val truePose1 = poseAt(rotY(-0.06), doubleArrayOf(0.05, 0.0, 0.0))
        // 55 mm drift (more than 2x the 25 mm fine radius!)
        val driftedPose1 = poseAt(rotY(-0.06), doubleArrayOf(0.05 + 0.040, -0.025, 0.028))
        val (depth1, _) = renderSyntheticDepth(truePose1, cam, scale = 1.0)
        val src = ArrayList<Float>()
        for (y in 40 until AdaptiveDepthMesh.SIZE - 40 step 22) {
            val vf = (y + 0.5) * cam.height / AdaptiveDepthMesh.SIZE
            for (x in 40 until AdaptiveDepthMesh.SIZE - 40 step 22) {
                val uf = (x + 0.5) * cam.width / AdaptiveDepthMesh.SIZE
                val ray = cam.undistort(uf, vf)
                val z = depth1[y * AdaptiveDepthMesh.SIZE + x].toDouble()
                src.add((ray[0] * z).toFloat()); src.add((ray[1] * z).toFloat()); src.add(z.toFloat())
            }
        }
        val res = LaserIcp.align(surf, FloatArray(src.size) { src[it] }, driftedPose1)
        assertTrue("coarse-to-fine ICP should recover 55mm drift, reason=${res.reason}", res.accepted)
        assertTrue("residual must converge < 1.5mm, was ${res.finalMedianResidualMm}", res.finalMedianResidualMm < 1.5)
    }

    @Test
    fun liveRejectionKeepsAccumulatedMeshAndReturningViewStitchesIntoIt() {
        val cam = camera()
        val rgb = ByteArray(AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE * 3) { 140.toByte() }
        val map = PatchMap(registrationFailure = PatchMap.RegistrationFailure.KEEP_ACTIVE_MAP)
        val roughIcp = LaserIcp.Config(maxCorrDistM = 0.04, maxMedianResidualMm = 15.0, maxCorrectionTranslationM = 0.12)
        val noLaser = FloatArray(0)
        val pose0 = poseAt(idRot(), doubleArrayOf(0.0, 0.0, 0.0))
        val pose1 = poseAt(rotY(-0.06), doubleArrayOf(0.06, 0.0, 0.0))
        val returning = poseAt(rotY(-0.06), doubleArrayOf(0.08, 0.0, 0.0))
        val rejectedPose = poseAt(idRot(), doubleArrayOf(1.5, 0.0, 0.0))

        fun add(pose: RigidPose, scale: Double, laser: Boolean = false): PatchMap.AddPatchOutcome {
            val (depth, stripe) = renderSyntheticDepth(pose, cam, scale)
            return map.addPatch(pose, null, cam, depth, scale, rgb518 = rgb,
                laserCamM = if (laser) stripe else noLaser, icpConfigOverride = roughIcp)
        }

        try {
            assertEquals(0, add(pose0, 2.0).patchId)
            val seedMesh = map.buildActiveSubmapMesh()!!
            val firstStitch = add(pose1, 3.0)
            assertTrue("overlapping view must fuse: ${firstStitch.registration?.reason}", firstStitch.fusedIntoActiveSubmap)
            assertEquals(1, firstStitch.patchId)
            val retained = map.latestPatch()
            val retainedPose = map.poseInActiveSubmap(returning)
            val accumulated = map.buildActiveSubmapMesh()!!
            val stats = map.activeTsdfStats()!!
            assertTrue("first stitch should extend coverage", accumulated.boundsMax[0] > seedMesh.boundsMax[0] + 0.01f)
            assertFalse("return view was already covered before the failed stitch",
                map.evaluateCoverage(returning, null, noLaser).shouldCapture)

            val rejected = add(rejectedPose, 1.8, laser = true)
            assertFalse(rejected.fusedIntoActiveSubmap)
            assertFalse(rejected.createdNewSubmap)
            assertEquals(null, rejected.patchId)
            assertNotNull(rejected.registration)
            assertEquals(1, map.submapCount())
            assertEquals(0, map.activeSubmapId())
            assertEquals(2, map.patchCount())
            assertTrue("the same mesh entry must stay active", retained === map.latestPatch())
            assertEquals(0.0, LaserIcp.translationDeltaM(retainedPose, map.poseInActiveSubmap(returning)), 1e-9)
            assertEquals(stats.integratedPatches, map.activeTsdfStats()!!.integratedPatches)
            assertEquals(stats.totalLaserHits, map.activeTsdfStats()!!.totalLaserHits)
            assertEquals(accumulated.triangleCount, map.buildActiveSubmapMesh()!!.triangleCount)

            val sameView = map.evaluateCoverage(rejectedPose, null, noLaser)
            assertFalse("a stationary failed view should not keep triggering DA3", sameView.shouldCapture)
            assertEquals("retry-wait", sameView.reason)
            val retry = map.evaluateCoverage(returning, null, noLaser)
            assertTrue("returning into overlap must be eligible for a retry", retry.shouldCapture)
            assertEquals("retry", retry.reason)

            val recovered = add(returning, 3.6)
            assertTrue("recovery must stitch into the retained submap: ${recovered.registration?.reason}", recovered.fusedIntoActiveSubmap)
            assertEquals(0, recovered.submapId)
            assertEquals(2, recovered.patchId)
            assertEquals(3, recovered.patchCount)
            assertEquals(1, recovered.submapCount)
            assertEquals(stats.integratedPatches + 1, map.activeTsdfStats()!!.integratedPatches)
            assertEquals("covered", map.evaluateCoverage(returning, null, noLaser).reason)
        } finally {
            map.release()
        }
    }

    @Test
    fun puzzleNoveltyGateSkipsAlreadyCoveredViewAndAcceptsUncoveredSide() {
        val cam = camera()
        val rgb = ByteArray(AdaptiveDepthMesh.SIZE * AdaptiveDepthMesh.SIZE * 3) { 140.toByte() }
        val map = PatchMap(registrationFailure = PatchMap.RegistrationFailure.KEEP_ACTIVE_MAP)
        try {
            val pose0 = poseAt(idRot(), doubleArrayOf(0.0, 0.0, 0.0))
            val (depth0, laser0) = renderSyntheticDepth(pose0, cam, scale = 2.0)
            assertEquals(0, map.addPatch(pose0, null, cam, depth0, scale = 2.0, rgb518 = rgb, laserCamM = laser0).patchId)

            // Tiny 5 mm wiggle over the exact same spot must be skipped when minNoveltyFraction is set.
            val wiggle = poseAt(idRot(), doubleArrayOf(0.005, 0.0, 0.0))
            val (depthW, laserW) = renderSyntheticDepth(wiggle, cam, scale = 2.0)
            val dup = map.addPatch(
                wiggle, null, cam, depthW, scale = 2.0, rgb518 = rgb, laserCamM = laserW,
                minNoveltyFraction = 0.08f,
            )
            assertFalse("duplicate already-covered view must not be pasted", dup.fusedIntoActiveSubmap)
            assertEquals(null, dup.patchId)
            assertEquals(1, map.patchCount())
            assertTrue("reason should explain already covered: ${dup.registration?.reason}",
                dup.registration?.reason?.startsWith("already covered") == true)

            // Moving 18 cm sideways exposes plenty of uncovered puzzle area -> must snap in cleanly.
            val sidePose = poseAt(rotY(-0.12), doubleArrayOf(0.18, 0.0, 0.0))
            val (depthS, laserS) = renderSyntheticDepth(sidePose, cam, scale = 2.5)
            val added = map.addPatch(
                sidePose, null, cam, depthS, scale = 2.5, rgb518 = rgb, laserCamM = laserS,
                minNoveltyFraction = 0.08f,
            )
            assertTrue("side view with uncovered area must fit and fuse: ${added.registration?.reason}", added.fusedIntoActiveSubmap)
            assertEquals(2, map.patchCount())
        } finally {
            map.release()
        }
    }
}
