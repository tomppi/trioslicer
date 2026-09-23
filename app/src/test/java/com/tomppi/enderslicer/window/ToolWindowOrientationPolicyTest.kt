package com.tomppi.enderslicer.window

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolWindowOrientationPolicyTest {
    @Test
    fun compactPhoneAndFoldCoverWindowsUseSensorLandscape() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ToolWindowOrientationPolicy.requestedOrientationFor(360),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ToolWindowOrientationPolicy.requestedOrientationFor(599),
        )
    }

    @Test
    fun unfoldedFoldableAndTabletWindowsRemainOrientationFlexible() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ToolWindowOrientationPolicy.requestedOrientationFor(600),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ToolWindowOrientationPolicy.requestedOrientationFor(840),
        )
    }

    @Test
    fun unknownWindowClassDoesNotForceRotation() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ToolWindowOrientationPolicy.requestedOrientationFor(0),
        )
    }
}
