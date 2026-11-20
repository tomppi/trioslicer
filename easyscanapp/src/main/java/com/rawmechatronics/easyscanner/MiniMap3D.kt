package com.rawmechatronics.easyscanner

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orbit camera for the 3D minimap. The world is OpenVINS' gravity-aligned frame:
 * +Z is up, so the trajectory is ground motion in X/Y with height in Z.
 *
 * Projection is orthographic: for a camera azimuth [yawDeg] around +Z and an
 * elevation [pitchDeg] above the ground plane, a world point is rotated into a
 * screen basis and scaled so [radius] fits the minimap. Pure math, no Android
 * types, so it is unit tested on the JVM.
 */
class MiniMap3D {
    var yawDeg: Float = DEFAULT_YAW
    var pitchDeg: Float = DEFAULT_PITCH
    var zoom: Float = 1f

    /** Camera target (world metres) and the bounding radius it frames. */
    var cx = 0f
        private set
    var cy = 0f
        private set
    var cz = 0f
        private set
    var minZ = 0f
        private set
    var radius = 0.05f
        private set
    var hasFit = false
        private set

    /** True once the starting orientation was chosen or the user rotated the view. */
    var aligned = false
        private set

    /** Update the framed box from the trajectory. [smoothing] = 1 snaps, < 1 eases. */
    fun fit(points: Array<FloatArray>, smoothing: Float = 0.2f) {
        var loX = Float.MAX_VALUE
        var loY = Float.MAX_VALUE
        var loZ = Float.MAX_VALUE
        var hiX = -Float.MAX_VALUE
        var hiY = -Float.MAX_VALUE
        var hiZ = -Float.MAX_VALUE
        var n = 0
        for (p in points) {
            if (p.size < 3 || !p[0].isFinite() || !p[1].isFinite() || !p[2].isFinite()) continue
            n++
            if (p[0] < loX) loX = p[0]
            if (p[0] > hiX) hiX = p[0]
            if (p[1] < loY) loY = p[1]
            if (p[1] > hiY) hiY = p[1]
            if (p[2] < loZ) loZ = p[2]
            if (p[2] > hiZ) hiZ = p[2]
        }
        if (n == 0) return
        val ncx = (loX + hiX) * 0.5f
        val ncy = (loY + hiY) * 0.5f
        val ncz = (loZ + hiZ) * 0.5f
        // Radius of the bounding sphere around the box centre.
        var r = 0.02f
        for (p in points) {
            if (p.size < 3 || !p[0].isFinite() || !p[1].isFinite() || !p[2].isFinite()) continue
            val dx = p[0] - ncx
            val dy = p[1] - ncy
            val dz = p[2] - ncz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > r) r = d
        }
        val s = smoothing.coerceIn(0f, 1f)
        if (!hasFit || s >= 1f) {
            cx = ncx; cy = ncy; cz = ncz; radius = r; minZ = loZ
            hasFit = true
        } else {
            cx += (ncx - cx) * s
            cy += (ncy - cy) * s
            cz += (ncz - cz) * s
            radius += (r - radius) * s
            minZ += (loZ - minZ) * s
        }
    }

    /** Drag to orbit: horizontal pixels turn around, vertical pixels tilt. */
    fun orbit(dxPx: Float, dyPx: Float, density: Float = 1f) {
        yawDeg -= dxPx / density * 0.35f
        pitchDeg = (pitchDeg - dyPx / density * 0.30f).coerceIn(MIN_PITCH, MAX_PITCH)
        if (yawDeg > 180f) yawDeg -= 360f
        if (yawDeg < -180f) yawDeg += 360f
    }

    fun zoomBy(factor: Float) {
        zoomTo(zoom * factor)
    }

    fun zoomTo(z: Float) {
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun resetOrbit() {
        yawDeg = DEFAULT_YAW
        pitchDeg = DEFAULT_PITCH
        zoom = 1f
        aligned = false
    }

    /**
     * Choose the starting orientation so that [dx],[dy] (world ground direction,
     * e.g. the first movement) points up the screen. Returns false if the
     * direction is too short to be meaningful.
     */
    fun alignToDirection(dx: Float, dy: Float): Boolean {
        val len = kotlin.math.hypot(dx, dy)
        if (len < 1e-3f) return false
        yawDeg = Math.toDegrees(kotlin.math.atan2(-dy.toDouble(), -dx.toDouble())).toFloat()
        aligned = true
        return true
    }

    /** Deterministic fallback: +X right, +Y up on screen. */
    fun useNorthUp() {
        yawDeg = -90f
        aligned = true
    }

    fun markUserRotated() {
        aligned = true
    }

    /**
     * Project a world point into minimap pixels relative to the box centre.
     * out = [screenX, screenY, depth]; screenY grows downward like a Canvas.
     */
    fun project(x: Float, y: Float, z: Float, size: Float, out: FloatArray) {
        val a = Math.toRadians(yawDeg.toDouble())
        val e = Math.toRadians(pitchDeg.toDouble())
        val sa = sin(a); val ca = cos(a)
        val se = sin(e); val ce = cos(e)
        val qx = x - cx; val qy = y - cy; val qz = z - cz
        // right = (-sinA, cosA, 0); up = (-cosA*sinE, -sinA*sinE, cosE); forward = -camera direction
        val sx = -sa * qx + ca * qy
        val sy = -(-ca * se * qx - sa * se * qy + ce * qz)
        val depth = -ce * ca * qx - ce * sa * qy - se * qz
        val s = 0.42f * size * zoom / radius.coerceAtLeast(0.01f)
        out[0] = (sx * s).toFloat()
        out[1] = (sy * s).toFloat()
        out[2] = depth.toFloat()
    }

    /** Grid spacing that gives 4-10 lines across the framed radius. */
    fun gridStep(): Float {
        val target = radius * 0.35f
        for (step in GRID_STEPS) {
            if (step >= target) return step
        }
        return GRID_STEPS.last()
    }

    companion object {
        const val DEFAULT_YAW = -35f
        const val DEFAULT_PITCH = 45f
        const val MIN_PITCH = -85f
        const val MAX_PITCH = 85f
        const val MIN_ZOOM = 0.35f
        const val MAX_ZOOM = 5f
        private val GRID_STEPS = floatArrayOf(0.01f, 0.02f, 0.05f, 0.1f, 0.2f, 0.5f, 1f, 2f, 5f)
    }
}
