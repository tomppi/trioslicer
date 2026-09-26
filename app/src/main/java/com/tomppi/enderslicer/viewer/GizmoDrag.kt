package com.tomppi.enderslicer.viewer

import kotlin.math.sqrt

/**
 * Turns a finger drag on a gizmo handle into the number the handle stands for.
 *
 * A ring has no scale of its own - turning it is a rotation, and how many degrees
 * a pixel is worth is a choice - while an arrow is measured against its own
 * projection on screen: the handle is drawn however long it is, so the pixels it
 * covers say exactly how many millimetres a pixel is worth along that axis.
 */
internal object GizmoDrag {
    /** How close a touch has to land to count as grabbing a handle. */
    const val TOUCH_RADIUS_PX = 40f

    /**
     * Millimetres along an axis for a screen drag, from the axis' own projection.
     *
     * [fromX]/[fromY] and [toX]/[toY] are the projected base and tip of the
     * arrow, [lengthMm] the distance between them in millimetres. When the axis
     * points at the viewer that projection collapses to nothing, and the drag
     * falls back to [fallbackMillimetresPerPixel] on the vertical axis, which is
     * how such a handle is used in practice.
     */
    fun axisMillimetres(
        deltaXPx: Float,
        deltaYPx: Float,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        lengthMm: Float,
        fallbackMillimetresPerPixel: Float,
    ): Float {
        if (!deltaXPx.isFinite() || !deltaYPx.isFinite()) return 0f
        if (!lengthMm.isFinite() || lengthMm <= 0f) return 0f
        val axisX = toX - fromX
        val axisY = toY - fromY
        val pixels = sqrt(axisX * axisX + axisY * axisY)
        // Under a pixel of travel for the whole arrow: the axis is end-on.
        if (!pixels.isFinite() || pixels < 1f) {
            return -deltaYPx * fallbackMillimetresPerPixel
        }
        val directionX = axisX / pixels
        val directionY = axisY / pixels
        val along = deltaXPx * directionX + deltaYPx * directionY
        return along * lengthMm / pixels
    }

    /**
     * Degrees for a screen drag on a ring, from the ring's own radius on screen.
     *
     * Dragging tangentially around a ring turns it as far as the finger travels:
     * a drag the length of the ring's radius is [degreesPerRadius] degrees, so the
     * same gesture feels the same on a small part and a large one.
     */
    fun ringDegrees(
        deltaXPx: Float,
        deltaYPx: Float,
        centreX: Float,
        centreY: Float,
        touchX: Float,
        touchY: Float,
        radiusPx: Float,
        degreesPerRadius: Float = 60f,
    ): Float {
        if (!deltaXPx.isFinite() || !deltaYPx.isFinite()) return 0f
        if (!radiusPx.isFinite() || radiusPx <= 0f) return 0f
        // Tangent at the grab point, in screen space: the direction the finger
        // moves to turn the ring the way it is drawn.
        val toTouchX = touchX - centreX
        val toTouchY = touchY - centreY
        val distance = sqrt(toTouchX * toTouchX + toTouchY * toTouchY)
        if (distance < 1f) return 0f
        val tangentX = -toTouchY / distance
        val tangentY = toTouchX / distance
        val along = deltaXPx * tangentX + deltaYPx * tangentY
        // Even if the value is out of range, the arithmetic is still defined.
        require(degreesPerRadius.isFinite()) { "Degrees per radius must be finite" }
        return along / radiusPx * degreesPerRadius
    }
}
