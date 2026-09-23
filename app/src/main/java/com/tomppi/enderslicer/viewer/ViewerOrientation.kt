package com.tomppi.enderslicer.viewer

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Turntable orientation in degrees, shared between the GL viewers and the gizmo overlay. */
data class ViewerOrientation(val yawDegrees: Float, val pitchDegrees: Float)

/**
 * Screen-space projection math for the corner XYZ gizmo. The GL viewers use a
 * turntable scheme: the scene rotates by Rz(yaw) then Rx(pitch) while the
 * camera looks from (0, -distance, elevation * distance) with up = +Z.
 * Projecting each unit world axis through the same rotation and the camera
 * rotation (parallel projection, no translation or perspective) yields the
 * on-screen direction that axis takes in the 3D view, so the gizmo arrows
 * rotate exactly with the model.
 */
object ViewerOrientationMath {
    /** Camera elevation of the model view (eye z = 0.62 * distance). */
    const val MODEL_VIEW_ELEVATION = 0.62f

    /** Camera elevation of the nozzle path view (eye z = 0.62 * distance). */
    const val PATH_VIEW_ELEVATION = 0.62f

    /**
     * Returns the screen-space direction (foreshortened, length <= 1) of the
     * world X, Y and Z axes as [x0, y0, x1, y1, x2, y2].
     */
    fun axisScreenVectors(yawDegrees: Float, pitchDegrees: Float, cameraElevation: Float): FloatArray {
        val yawR = Math.toRadians(yawDegrees.toDouble())
        val pitchR = Math.toRadians(pitchDegrees.toDouble())
        val cosYaw = cos(yawR).toFloat()
        val sinYaw = sin(yawR).toFloat()
        val cosPitch = cos(pitchR).toFloat()
        val sinPitch = sin(pitchR).toFloat()
        val tilt = 1f / sqrt(1.0 + cameraElevation.toDouble() * cameraElevation).toFloat()

        fun project(axisX: Float, axisY: Float, axisZ: Float): FloatArray {
            // Rz(yaw)
            val x1 = axisX * cosYaw - axisY * sinYaw
            val y1 = axisX * sinYaw + axisY * cosYaw
            // Rx(pitch)
            val y2 = y1 * cosPitch - axisZ * sinPitch
            val z2 = y1 * sinPitch + axisZ * cosPitch
            // Camera rotation only: screen right = eye X, screen up = eye Y.
            return floatArrayOf(x1, (cameraElevation * y2 + z2) * tilt)
        }

        val xAxis = project(1f, 0f, 0f)
        val yAxis = project(0f, 1f, 0f)
        val zAxis = project(0f, 0f, 1f)
        return floatArrayOf(xAxis[0], xAxis[1], yAxis[0], yAxis[1], zAxis[0], zAxis[1])
    }
}
