package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

class ViewerOrientationMathTest {
    private val tolerance = 1e-4f

    @Test
    fun unrotatedModelViewMapsPlateAxesToScreenAxes() {
        val v = ViewerOrientationMath.axisScreenVectors(
            yawDegrees = 0f,
            pitchDegrees = 0f,
            cameraElevation = ViewerOrientationMath.MODEL_VIEW_ELEVATION,
        )
        // Screen right is world X.
        assertEquals(1f, v[0], tolerance)
        assertEquals(0f, v[1], tolerance)
        // The tilted camera foreshortens Y and Z by the same tilt factor.
        val tilt = 1f / sqrt(1.0 + ViewerOrientationMath.MODEL_VIEW_ELEVATION * ViewerOrientationMath.MODEL_VIEW_ELEVATION).toFloat()
        assertEquals(0f, v[2], tolerance)
        assertEquals(ViewerOrientationMath.MODEL_VIEW_ELEVATION * tilt, v[3], tolerance)
        assertEquals(0f, v[4], tolerance)
        assertEquals(tilt, v[5], tolerance)
    }

    @Test
    fun defaultModelOrientationLeavesThePlateArrowsFullLengthAndZForeshortened() {
        val v = ViewerOrientationMath.axisScreenVectors(
            yawDegrees = -28f,
            pitchDegrees = 58f,
            cameraElevation = ViewerOrientationMath.MODEL_VIEW_ELEVATION,
        )
        assertEquals(1f, hypot(v[0], v[1]), tolerance)
        assertEquals(1f, hypot(v[2], v[3]), tolerance)
        // Z points almost straight at the camera at the default view.
        assertTrue(hypot(v[4], v[5]) < 0.01f)
    }

    @Test
    fun yawSpinsThePlateArrowsInScreenSpace() {
        val v = ViewerOrientationMath.axisScreenVectors(
            yawDegrees = 90f,
            pitchDegrees = 0f,
            cameraElevation = ViewerOrientationMath.MODEL_VIEW_ELEVATION,
        )
        val tilt = 1f / sqrt(1.0 + ViewerOrientationMath.MODEL_VIEW_ELEVATION * ViewerOrientationMath.MODEL_VIEW_ELEVATION).toFloat()
        // X now points up-screen, foreshortened by the tilted camera.
        assertEquals(0f, v[0], tolerance)
        assertEquals(ViewerOrientationMath.MODEL_VIEW_ELEVATION * tilt, v[1], tolerance)
        // Y points screen-left at full length.
        assertEquals(-1f, v[2], tolerance)
        assertEquals(0f, v[3], tolerance)
        // Z is unchanged by yaw.
        assertEquals(0f, v[4], tolerance)
        assertEquals(tilt, v[5], tolerance)
    }

    @Test
    fun projectedAxesNeverExceedUnitLength() {
        for (yaw in -180 until 180 step 30) {
            for (pitch in -60..60 step 20) {
                val v = ViewerOrientationMath.axisScreenVectors(
                    yawDegrees = yaw.toFloat(),
                    pitchDegrees = pitch.toFloat(),
                    cameraElevation = ViewerOrientationMath.PATH_VIEW_ELEVATION,
                )
                for (axis in 0 until 3) {
                    val length = hypot(v[axis * 2], v[axis * 2 + 1])
                    assertTrue("length $length exceeds 1 at yaw $yaw pitch $pitch", length <= 1f + tolerance)
                }
            }
        }
    }
}
