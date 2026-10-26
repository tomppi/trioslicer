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
     * How long an arrow has to project to be its own ruler. Under this it is
     * pointing at the viewer, and the drag is read on the vertical screen axis
     * at the fallback scale instead.
     */
    const val MIN_PROJECTION_PX = 12f

    /** Two candidates this close on screen are decided by which is nearer instead. */
    private const val SCREEN_TIE_PX = 6f

    /** A point the touch might be grabbing, and what the choice needs to judge it. */
    data class HandleCandidate(val screenDistancePx: Float, val depthMm: Float)

    /**
     * Which candidate the finger grabbed, or -1 for none.
     *
     * Screen distance alone decides: the gizmo is an affordance drawn over the
     * model, so a finger that lands on a handle within [touchRadiusPx] is aiming
     * at the handle it can see, whatever geometry stands behind it. This used to
     * also reject handles deeper than the model's surface under the finger -
     * "reaching through the part to a ring on its far side" felt wrong - but on a
     * two-part plate a lid covers the X and Y arrows sitting at the lid's own
     * mid-plane, so every touch on them fell through to the camera and the object
     * could not be moved by them at all. A handle the user can see is theirs.
     *
     * When two candidates land within a few pixels of each other - the two sides
     * of a ring seen almost edge-on, or the three arrows sharing a pivot - the
     * nearer one on screen wins, and an exact tie is broken by depth (the one
     * closest to the eye is the one that looks on top).
     */
    fun chooseHandle(
        candidates: List<HandleCandidate>,
        touchRadiusPx: Float,
    ): Int {
        if (!touchRadiusPx.isFinite() || touchRadiusPx <= 0f) return -1
        var best = -1
        var bestScreen = Float.MAX_VALUE
        var bestDepth = Float.MAX_VALUE
        candidates.forEachIndexed { index, candidate ->
            if (!candidate.screenDistancePx.isFinite() || !candidate.depthMm.isFinite()) {
                return@forEachIndexed
            }
            if (candidate.screenDistancePx > touchRadiusPx) return@forEachIndexed
            val closer = candidate.screenDistancePx < bestScreen - SCREEN_TIE_PX
            val tiedButNearer = kotlin.math.abs(candidate.screenDistancePx - bestScreen) <= SCREEN_TIE_PX &&
                candidate.depthMm < bestDepth
            if (best < 0 || closer || tiedButNearer) {
                best = index
                bestScreen = candidate.screenDistancePx
                bestDepth = candidate.depthMm
            }
        }
        return best
    }

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
        // An arrow drawn this short on screen is pointing at the viewer, and its
        // own pixels are no use as a ruler: dividing by them turns a finger's
        // forty pixels into tens of millimetres - the move gizmo's vertical
        // arrow is unusable that way, and a joint's own axis has to be draggable
        // at every camera angle. Such a handle is used on the vertical screen
        // axis instead, at the fallback's own scale.
        if (!pixels.isFinite() || pixels < MIN_PROJECTION_PX) {
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
