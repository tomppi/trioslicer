package com.tomppi.enderslicer.window

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * Keeps dense WebGL tool UIs usable on narrow phone windows without treating a
 * foldable as permanently large or permanently small. The current window's
 * smallest-width class changes when a foldable is opened or closed.
 */
internal object ToolWindowOrientationPolicy {
    const val EXPANDED_WINDOW_MIN_WIDTH_DP = 600

    fun apply(activity: Activity) {
        val desired = requestedOrientationFor(
            activity.resources.configuration.smallestScreenWidthDp,
        )
        if (activity.requestedOrientation != desired) {
            activity.requestedOrientation = desired
        }
    }

    internal fun requestedOrientationFor(smallestWidthDp: Int): Int =
        if (smallestWidthDp in 1 until EXPANDED_WINDOW_MIN_WIDTH_DP) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
}
