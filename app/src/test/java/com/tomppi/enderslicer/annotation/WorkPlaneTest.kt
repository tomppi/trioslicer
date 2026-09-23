package com.tomppi.enderslicer.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Placement geometry. A tap gives a ray, and a ray does not say where along
 * itself the point belongs - the plane supplies that. These pin the two cases
 * the interaction depends on: landing on a horizontal plane at a known height,
 * and changing height without disturbing X or Y.
 */
class WorkPlaneTest {

    @Test
    fun aStraightDownRayLandsDirectlyBelowItsOrigin() {
        val point = WorkPlane.intersectHorizontal(
            origin = Point3(12f, -7f, 50f),
            direction = Point3(0f, 0f, -1f),
            planeZ = 10f,
        )
        assertEquals(12f, point!!.x, 1e-3f)
        assertEquals(-7f, point.y, 1e-3f)
        assertEquals(10f, point.z, 1e-3f)
    }

    @Test
    fun anAngledRayLandsWhereItCrossesThePlane() {
        // 45 degrees in X: crossing 40mm down moves 40mm across.
        val point = WorkPlane.intersectHorizontal(
            origin = Point3(0f, 0f, 50f),
            direction = Point3(0.70710678f, 0f, -0.70710678f),
            planeZ = 10f,
        )
        assertEquals(40f, point!!.x, 1e-2f)
        assertEquals(0f, point.y, 1e-2f)
        assertEquals(10f, point.z, 1e-3f)
    }

    @Test
    fun aRayParallelToThePlaneHasNoAnswer() {
        assertNull(
            WorkPlane.intersectHorizontal(
                origin = Point3(0f, 0f, 50f),
                direction = Point3(1f, 0f, 0f),
                planeZ = 10f,
            ),
        )
    }

    @Test
    fun aPlaneBehindTheCameraHasNoAnswer() {
        // Pointing away from a plane that is behind the ray's origin.
        assertNull(
            WorkPlane.intersectHorizontal(
                origin = Point3(0f, 0f, 10f),
                direction = Point3(0f, 0f, 1f),
                planeZ = -10f,
            ),
        )
    }

    @Test
    fun heightAdjustmentChangesOnlyZ() {
        val anchor = Point3(5f, 6f, 20f)
        // Camera looking horizontally along +Y, so the vertical plane faces it.
        val z = WorkPlane.intersectVerticalForZ(
            origin = Point3(5f, -100f, 20f),
            direction = Point3(0f, 1f, 0f),
            anchor = anchor,
        )
        assertEquals("the ray passes level with the anchor", 20f, z!!, 1e-2f)
    }

    @Test
    fun aRisingRayRaisesTheHeight() {
        val anchor = Point3(0f, 0f, 20f)
        // Straight at the camera's vertical plane, tilted upward.
        val z = WorkPlane.intersectVerticalForZ(
            origin = Point3(0f, -100f, 20f),
            direction = Point3(0f, 0.70710678f, 0.70710678f),
            anchor = anchor,
        )
        assertEquals("half the travel is upward over the same distance", 120f, z!!, 1f)
    }

    @Test
    fun aRayWithNoHorizontalComponentCannotDefineAVerticalPlane() {
        assertNull(
            WorkPlane.intersectVerticalForZ(
                origin = Point3(0f, 0f, 0f),
                direction = Point3(0f, 0f, -1f),
                anchor = Point3(1f, 1f, 1f),
            ),
        )
    }
}
