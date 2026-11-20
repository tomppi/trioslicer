package com.rawmechatronics.easyscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniMap3DTest {

    private fun points(vararg p: FloatArray) = arrayOf(*p)
    private fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    private fun checkFits(cam: MiniMap3D, pts: Array<FloatArray>, size: Float = 200f) {
        val out = FloatArray(3)
        for (pt in pts) {
            cam.project(pt[0], pt[1], pt[2], size, out)
            assertTrue("projected x out of box: " + out[0], out[0] in -size * 0.5f..size * 0.5f)
            assertTrue("projected y out of box: " + out[1], out[1] in -size * 0.5f..size * 0.5f)
        }
    }

    @Test
    fun centreProjectsToBoxCentre() {
        val cam = MiniMap3D()
        val pts = points(p(0f, 0f, 0f), p(1f, 0f, 0f), p(0f, 1f, 0f), p(0f, 0f, 1f))
        cam.fit(pts, smoothing = 1f)
        val out = FloatArray(3)
        cam.project(cam.cx, cam.cy, cam.cz, 200f, out)
        assertEquals(0f, out[0], 1e-3f)
        assertEquals(0f, out[1], 1e-3f)
    }

    @Test
    fun worldUpProjectsUpAtLevelView() {
        val cam = MiniMap3D()
        cam.yawDeg = 0f
        cam.pitchDeg = 0f
        cam.fit(points(p(0f, 0f, 0f), p(1f, 0f, 0f)), smoothing = 1f)
        val below = FloatArray(3)
        val above = FloatArray(3)
        cam.project(cam.cx, cam.cy, cam.cz - 0.2f, 200f, below)
        cam.project(cam.cx, cam.cy, cam.cz + 0.2f, 200f, above)
        assertTrue("world +Z must project upward (smaller y), got " + above[1] + " vs " + below[1], above[1] < below[1])
        assertEquals(-above[1], below[1], 1e-3f)
    }

    @Test
    fun yawTurnsTheGroundPlane() {
        val cam = MiniMap3D()
        cam.yawDeg = 0f
        cam.pitchDeg = 0f
        cam.fit(points(p(0f, 0f, 0f), p(1f, 0f, 0f)), smoothing = 1f)
        val out = FloatArray(3)
        cam.project(cam.cx + 0.5f, cam.cy, cam.cz, 200f, out)
        // looking along -X at yaw 0: a point on +X is straight ahead (depth only)
        assertEquals(0f, out[0], 1e-3f)
        cam.yawDeg = 90f
        cam.project(cam.cx + 0.5f, cam.cy, cam.cz, 200f, out)
        assertTrue("after a 90 degree yaw +X must leave the centre, got " + out[0], kotlin.math.abs(out[0]) > 1f)
    }

    @Test
    fun fitKeepsTheWholePathInsideTheBox() {
        val cam = MiniMap3D()
        val pts = points(p(0f, 0f, 0f), p(1.2f, 0.4f, 0.2f), p(-0.3f, 1.1f, -0.1f))
        cam.fit(pts, smoothing = 1f)
        checkFits(cam, pts)
        cam.orbit(120f, 60f)
        checkFits(cam, pts)
        val out = FloatArray(3)
        cam.project(pts[1][0], pts[1][1], pts[1][2], 200f, out)
        val before = kotlin.math.hypot(out[0], out[1])
        cam.zoomTo(2.5f)
        cam.project(pts[1][0], pts[1][1], pts[1][2], 200f, out)
        val after = kotlin.math.hypot(out[0], out[1])
        assertTrue("zoom must magnify: " + before + " -> " + after, after > before * 2f)
    }

    @Test
    fun orbitClampsAndResetRestoresDefaultView() {
        val cam = MiniMap3D()
        cam.orbit(0f, -10000f)
        assertEquals(MiniMap3D.MAX_PITCH, cam.pitchDeg, 1e-3f)
        cam.orbit(0f, 10000f)
        assertEquals(MiniMap3D.MIN_PITCH, cam.pitchDeg, 1e-3f)
        cam.zoomBy(1000f)
        assertEquals(MiniMap3D.MAX_ZOOM, cam.zoom, 1e-3f)
        cam.zoomBy(0.0001f)
        assertEquals(MiniMap3D.MIN_ZOOM, cam.zoom, 1e-3f)
        cam.resetOrbit()
        assertEquals(MiniMap3D.DEFAULT_YAW, cam.yawDeg, 1e-3f)
        assertEquals(MiniMap3D.DEFAULT_PITCH, cam.pitchDeg, 1e-3f)
        assertEquals(1f, cam.zoom, 1e-3f)
    }

    @Test
    fun gridStepGrowsWithRadius() {
        val cam = MiniMap3D()
        cam.fit(points(p(0f, 0f, 0f), p(0.1f, 0f, 0f)), smoothing = 1f)
        val small = cam.gridStep()
        cam.fit(points(p(0f, 0f, 0f), p(3f, 0f, 0f)), smoothing = 1f)
        val large = cam.gridStep()
        assertTrue("grid step should grow: " + small + " -> " + large, large > small)
    }

    @Test
    fun alignedStartPutsFirstMovementUpOnScreen() {
        val cam = MiniMap3D()
        cam.fit(points(p(0f, 0f, 0f), p(1f, 0f, 0f)), smoothing = 1f)
        assertTrue(cam.alignToDirection(1f, 0f))
        val out = FloatArray(3)
        cam.project(cam.cx + 0.5f, cam.cy, cam.cz, 200f, out)
        assertTrue("motion direction must go up the screen, got y=" + out[1], out[1] < -1f)
        assertEquals(0f, out[0], 2f)
    }

    @Test
    fun northUpFallbackAndFitTrackTheFloor() {
        val cam = MiniMap3D()
        cam.fit(points(p(0f, 0f, 1f), p(1f, 0f, 3f)), smoothing = 1f)
        assertEquals(1f, cam.minZ, 1e-3f)
        cam.useNorthUp()
        assertTrue(cam.aligned)
        val out = FloatArray(3)
        // +Y must be up the screen in the north-up fallback
        cam.project(cam.cx, cam.cy + 0.5f, cam.cz, 200f, out)
        assertTrue("+Y must project up, got y=" + out[1], out[1] < -1f)
    }

    @Test
    fun resetOrbitAsksForRealignment() {
        val cam = MiniMap3D()
        cam.alignToDirection(1f, 0f)
        cam.resetOrbit()
        assertTrue(!cam.aligned)
    }

    @Test
    fun singlePointIsSafe() {
        val cam = MiniMap3D()
        cam.fit(points(p(1f, 2f, 3f)), smoothing = 1f)
        val out = FloatArray(3)
        cam.project(1f, 2f, 3f, 200f, out)
        assertEquals(0f, out[0], 1e-3f)
        assertEquals(0f, out[1], 1e-3f)
    }
}
