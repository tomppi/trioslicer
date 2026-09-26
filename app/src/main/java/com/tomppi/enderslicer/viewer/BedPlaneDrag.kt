package com.tomppi.enderslicer.viewer

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Turns a finger drag on the plate into a movement across the build plate.
 *
 * The viewer keeps its camera still and rotates the scene by pitch and then yaw,
 * so a screen-space drag has to be rotated back into plate coordinates before it
 * can move the model. The result is deliberately flat - only the plate's X and Y
 * come back - because a model that followed the finger's vertical component as
 * well would lift off the bed or sink into it.
 */
internal object BedPlaneDrag {
    /** Millimetres covered by one pixel at the model's plane. */
    fun millimetresPerPixel(
        distanceMm: Float,
        viewportHeightPx: Int,
        fieldOfViewDegrees: Float,
        eyeDistanceScale: Float,
    ): Float {
        if (!distanceMm.isFinite() || distanceMm <= 0f) return 0f
        val eyeDistance = distanceMm * eyeDistanceScale
        val visibleHeight = 2f * eyeDistance * tan(Math.toRadians(fieldOfViewDegrees / 2.0)).toFloat()
        return visibleHeight / maxOf(viewportHeightPx, 1).toFloat()
    }

    /**
     * The plate-plane movement for a screen drag, in millimetres.
     *
     * A finger moving right has to move the model right *on screen*, which for a
     * rotated scene is not the plate's X axis; and a finger moving up has to move
     * it away from the viewer along the plate rather than upwards off the bed.
     */
    fun plateDeltaMm(
        deltaXPx: Float,
        deltaYPx: Float,
        millimetresPerPixel: Float,
        yawDegrees: Float,
        pitchDegrees: Float,
    ): FloatArray {
        if (!deltaXPx.isFinite() || !deltaYPx.isFinite() || !millimetresPerPixel.isFinite()) {
            return floatArrayOf(0f, 0f)
        }
        // Screen axes in view space: +X right, +Y up, and a finger dragged down
        // moves the model down. A screen drag has no depth, so Z stays zero.
        val viewX = deltaXPx * millimetresPerPixel
        val viewY = -deltaYPx * millimetresPerPixel

        // Undo the renderer's scene rotation, Rx(pitch) * Rz(yaw), by rotating
        // about X by -pitch and then about Z by -yaw.
        val pitchRadians = Math.toRadians(-pitchDegrees.toDouble())
        val cosPitch = cos(pitchRadians).toFloat()
        val sinPitch = sin(pitchRadians).toFloat()
        val afterPitchX = viewX
        val afterPitchY = cosPitch * viewY
        val afterPitchZ = sinPitch * viewY

        val yawRadians = Math.toRadians(-yawDegrees.toDouble())
        val cosYaw = cos(yawRadians).toFloat()
        val sinYaw = sin(yawRadians).toFloat()
        val plateX = cosYaw * afterPitchX - sinYaw * afterPitchY
        val plateY = sinYaw * afterPitchX + cosYaw * afterPitchY

        // afterPitchZ is the component along the plate normal and is dropped: the
        // bed is where the model has to stay.
        return floatArrayOf(plateX, plateY)
    }
}
