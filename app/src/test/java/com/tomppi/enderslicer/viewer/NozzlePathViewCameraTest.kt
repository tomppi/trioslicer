package com.tomppi.enderslicer.viewer

import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Both nozzle-path renderers scale their viewport from the true eye distance,
 * and the orthographic measurement view is no exception: it used to derive its
 * half-height from the fitted look-at distance, which drew the part 17.7%
 * larger than the perspective view and moved it 17.7% further than the finger
 * on a two-finger drag.
 */
class NozzlePathViewCameraTest {

    @Test
    fun eyeDistanceScaleMatchesTheCameraElevation() {
        // The renderers place the eye at (0, -distance, 0.62 * distance).
        assertEquals(sqrt(1f + 0.62f * 0.62f), NozzlePathViewCamera.CAMERA_EYE_DISTANCE_SCALE, 1e-4f)
    }

    @Test
    fun viewportScaleUsesTheEyeDistanceNotTheLookAtDistance() {
        val distance = 200f
        val eyeDistance = distance * sqrt(1f + 0.62f * 0.62f)
        val expectedHalfHeight = eyeDistance * tan(Math.toRadians(21.0)).toFloat()

        assertEquals(
            expectedHalfHeight,
            NozzlePathViewCamera.orthographicHalfHeight(distance, 42f),
            1e-2f,
        )
    }

    @Test
    fun orthographicProjectionSpansTheHeightThePanAssumes() {
        for (distance in floatArrayOf(2f, 60f, 200f, 420f)) {
            assertEquals(
                NozzlePathViewCamera.visibleHeightAtPivot(distance, 42f),
                2f * NozzlePathViewCamera.orthographicHalfHeight(distance, 42f),
                1e-4f,
            )
        }
    }
}
