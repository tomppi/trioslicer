package com.tomppi.enderslicer.viewer

import kotlin.math.sqrt

/**
 * Turns a finger drag on a gizmo handle into the number the handle stands for.
 *
 * A ring is turned by following its own geometry - the drag is measured against
 * the direction the ring travels at the point that was grabbed - while an arrow
 * is measured against its own projection: the handle is drawn however long it is,
 * so the pixels it covers say exactly how many millimetres a pixel is worth along
 * that axis.
 */
internal object GizmoDrag {
    /** How close a touch has to land to count as grabbing a handle. */
    const val TOUCH_RADIUS_PX = 40f

    /** Degrees a drag the length of the ring's radius is worth. */
    const val DEGREES_PER_RADIUS = 60f

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
     * The direction a ring travels on screen where it was grabbed, and how big it
     * looks: tangentX, tangentY and radiusPx, or null when nothing is near.
     *
     * [projected] is the ring's points as screen x/y pairs, in the order the ring
     * is built - that is, in order of increasing angle about its axis - so the
     * step from one point to the next is the screen direction of a positive turn.
     * Guessing that direction from the touch instead would turn the near side of a
     * tilted ring one way and its far side the other, which is exactly what it
     * felt like.
     */
    fun ringReference(projected: FloatArray, touchX: Float, touchY: Float): FloatArray? {
        val count = projected.size / 2
        if (count < 4) return null
        var nearest = -1
        var nearestDistance = Float.MAX_VALUE
        var centreX = 0f
        var centreY = 0f
        for (index in 0 until count) {
            val x = projected[index * 2]
            val y = projected[index * 2 + 1]
            if (!x.isFinite() || !y.isFinite()) continue
            centreX += x
            centreY += y
            val dx = x - touchX
            val dy = y - touchY
            val distance = dx * dx + dy * dy
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = index
            }
        }
        if (nearest < 0) return null
        centreX /= count
        centreY /= count

        val before = ((nearest - 1) + count) % count
        val after = (nearest + 1) % count
        val tangentX = projected[after * 2] - projected[before * 2]
        val tangentY = projected[after * 2 + 1] - projected[before * 2 + 1]
        val length = sqrt(tangentX * tangentX + tangentY * tangentY)
        if (!length.isFinite() || length < 1e-3f) return null

        // The radius on screen: how far the grabbed point sits from the middle of
        // the projected ring.
        val radiusX = projected[nearest * 2] - centreX
        val radiusY = projected[nearest * 2 + 1] - centreY
        val radiusPx = sqrt(radiusX * radiusX + radiusY * radiusY)
        if (!radiusPx.isFinite() || radiusPx < 1f) return null
        return floatArrayOf(tangentX / length, tangentY / length, radiusPx)
    }

    /**
     * Degrees for a drag measured along a ring's tangent, where the tangent is the
     * screen direction of a positive turn and radiusPx is the ring's size on
     * screen.
     */
    fun ringDegrees(
        deltaXPx: Float,
        deltaYPx: Float,
        tangentXPx: Float,
        tangentYPx: Float,
        radiusPx: Float,
        degreesPerRadius: Float = DEGREES_PER_RADIUS,
    ): Float {
        if (!deltaXPx.isFinite() || !deltaYPx.isFinite()) return 0f
        if (!tangentXPx.isFinite() || !tangentYPx.isFinite()) return 0f
        if (!radiusPx.isFinite() || radiusPx <= 0f) return 0f
        if (!degreesPerRadius.isFinite()) return 0f
        val length = sqrt(tangentXPx * tangentXPx + tangentYPx * tangentYPx)
        if (length < 1e-4f) return 0f
        val along = (deltaXPx * tangentXPx + deltaYPx * tangentYPx) / length
        return along / radiusPx * degreesPerRadius
    }
}
