package com.rawmechatronics.easyscanner

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Narrow-ridge laser stripe verifier.
 *
 * The raw hue/chroma stripe detector can fire false positives on warm indoor objects (wood,
 * beige fabric, skin, red/orange objects, or colored edges) when no physical laser is shining.
 * A real laser stripe is a bright, narrow ridge (2–8 px wide) that stands out sharply above
 * BOTH outer shoulders perpendicular to the stripe, whereas flat colored surfaces and color
 * step edges remain high on at least one shoulder.
 */
object LaserStripeFilter {

    data class YuvPlanes(
        val width: Int,
        val height: Int,
        val y: ByteBuffer,
        val yStride: Int,
        val u: ByteBuffer,
        val v: ByteBuffer,
        val uvStride: Int,
        val uvPixelStride: Int,
    )

    data class Verdict(
        val accepted: Boolean,
        val passedPoints: Int,
        val totalPoints: Int,
    )

    /**
     * Verifies `LASER_RAW` points in [result] against the live YUV frame. If fewer than
     * [MIN_PASS_POINTS] or [MIN_PASS_FRACTION] of points exhibit a true bright narrow ridge
     * above both perpendicular shoulders, strips all laser layers from [result] in place.
     */
    fun filterInPlace(result: FrameResult, planes: YuvPlanes, color: String): Verdict {
        var rawOffset = -1
        var rawCount = 0
        var hasAnyLaser = false
        result.forEachLayer { kind, stride, count, offset ->
            if (kind == FrameResult.Layer.LASER_RAW && stride == 2 && count > 0) {
                rawOffset = offset
                rawCount = count
                hasAnyLaser = true
            } else if (kind == FrameResult.Layer.LASER_CAM || kind == FrameResult.Layer.LASER_IN ||
                kind == FrameResult.Layer.LASER_OUT || kind == FrameResult.Layer.LASER_FIT
            ) {
                if (count > 0) hasAnyLaser = true
            }
        }
        if (!hasAnyLaser) return Verdict(true, 0, 0)
        if (rawOffset < 0 || rawCount < MIN_PASS_POINTS) {
            result.clearLaserLayers()
            return Verdict(false, 0, rawCount)
        }

        val v = verifyRawPoints(result.data, rawOffset, rawCount, planes, color)
        if (!v.accepted) {
            result.clearLaserLayers()
        }
        return v
    }

    /** Core narrow-ridge check on `[x0, y0, x1, y1, ...]` pixel coordinates. */
    fun verifyRawPoints(
        coords: FloatArray,
        offset: Int,
        count: Int,
        planes: YuvPlanes,
        color: String,
    ): Verdict {
        if (count < MIN_PASS_POINTS || planes.width < 16 || planes.height < 16) {
            return Verdict(false, 0, count)
        }
        val mode = when (color.lowercase()) {
            "green" -> MODE_GREEN
            "auto" -> MODE_AUTO
            else -> MODE_RED
        }
        val scale = (max(planes.width, planes.height) / 960f).coerceAtLeast(0.6f)
        val coreOff = max(1, (2.5f * scale).roundToInt())
        val shDist = max(8, (14f * scale).roundToInt())
        val shOuter = shDist + max(2, (3f * scale).roundToInt())

        // Global fallback perpendicular direction if local tangent is degenerate.
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until count) {
            val px = coords[offset + i * 2]
            val py = coords[offset + i * 2 + 1]
            if (px < minX) minX = px; if (px > maxX) maxX = px
            if (py < minY) minY = py; if (py > maxY) maxY = py
        }
        val globalNx = if ((maxX - minX) >= (maxY - minY)) 0f else 1f
        val globalNy = if ((maxX - minX) >= (maxY - minY)) 1f else 0f

        var passed = 0
        var tested = 0
        val stride = max(1, count / 120)
        var i = 0
        while (i < count) {
            val px = coords[offset + i * 2]
            val py = coords[offset + i * 2 + 1]
            if (px.isFinite() && py.isFinite()) {
                val i0 = max(0, i - 3)
                val i1 = min(count - 1, i + 3)
                val tx = coords[offset + i1 * 2] - coords[offset + i0 * 2]
                val ty = coords[offset + i1 * 2 + 1] - coords[offset + i0 * 2 + 1]
                val tlen = hypot(tx.toDouble(), ty.toDouble()).toFloat()
                val nx: Float
                val ny: Float
                if (tlen > 2f) {
                    nx = -ty / tlen
                    ny = tx / tlen
                } else {
                    nx = globalNx
                    ny = globalNy
                }
                tested++
                if (isRidgePoint(planes, px, py, nx, ny, coreOff, shDist, shOuter, mode)) {
                    passed++
                }
            }
            i += stride
        }

        val minNeeded = max(MIN_PASS_POINTS / stride.coerceAtLeast(1), 6)
        val frac = if (tested > 0) passed.toFloat() / tested.toFloat() else 0f
        val ok = passed >= minNeeded && frac >= MIN_PASS_FRACTION
        return Verdict(ok, passed, tested)
    }

    private fun isRidgePoint(
        p: YuvPlanes,
        px: Float,
        py: Float,
        nx: Float,
        ny: Float,
        coreOff: Int,
        shDist: Int,
        shOuter: Int,
        mode: Int,
    ): Boolean {
        // Check that both shoulders stay inside the image; skip margin points gracefully.
        val lx1 = (px - nx * shDist).roundToInt()
        val ly1 = (py - ny * shDist).roundToInt()
        val rx1 = (px + nx * shDist).roundToInt()
        val ry1 = (py + ny * shDist).roundToInt()
        if (lx1 < 0 || lx1 >= p.width || ly1 < 0 || ly1 >= p.height ||
            rx1 < 0 || rx1 >= p.width || ry1 < 0 || ry1 >= p.height
        ) {
            return false
        }

        // Peak response, dominant channel, and luma across the narrow core [-coreOff..+coreOff].
        var peakResp = 0
        var peakDom = 0
        var peakLuma = 0
        for (k in -1..1) {
            val sx = (px + nx * (k * coreOff)).roundToInt().coerceIn(0, p.width - 1)
            val sy = (py + ny * (k * coreOff)).roundToInt().coerceIn(0, p.height - 1)
            val packed = sampleMetrics(p, sx, sy, mode)
            val resp = packed and 0xff
            val dom = (packed ushr 8) and 0xff
            val luma = (packed ushr 16) and 0xff
            if (resp > peakResp) peakResp = resp
            if (dom > peakDom) peakDom = dom
            if (luma > peakLuma) peakLuma = luma
        }
        // Real laser stripes have strong laser-hue chroma and bright dominant channel.
        if (peakResp < MIN_CORE_RESPONSE || peakDom < MIN_DOM_CHANNEL) return false

        // Two-tap average on each perpendicular shoulder to ignore single-pixel sensor noise.
        val lx2 = (px - nx * shOuter).roundToInt().coerceIn(0, p.width - 1)
        val ly2 = (py - ny * shOuter).roundToInt().coerceIn(0, p.height - 1)
        val rx2 = (px + nx * shOuter).roundToInt().coerceIn(0, p.width - 1)
        val ry2 = (py + ny * shOuter).roundToInt().coerceIn(0, p.height - 1)

        val l1 = sampleMetrics(p, lx1, ly1, mode)
        val l2 = sampleMetrics(p, lx2, ly2, mode)
        val r1 = sampleMetrics(p, rx1, ry1, mode)
        val r2 = sampleMetrics(p, rx2, ry2, mode)

        val leftResp = ((l1 and 0xff) + (l2 and 0xff)) / 2
        val rightResp = ((r1 and 0xff) + (r2 and 0xff)) / 2
        val maxShoulderResp = max(leftResp, rightResp)

        // Strict narrow-ridge check: peak must stand clearly above BOTH shoulders.
        // Rejects broad colored objects (both shoulders high) AND color step edges (one shoulder high).
        if (peakResp - maxShoulderResp >= MIN_CHROMA_CONTRAST &&
            maxShoulderResp <= (peakResp * MAX_SHOULDER_RATIO).toInt()
        ) {
            return true
        }

        // Fallback for an oversaturated white-hot laser core crossing a red/green object:
        // luma spikes sharply above both shoulders while still carrying strong laser-hue halo.
        val leftLuma = (((l1 ushr 16) and 0xff) + ((l2 ushr 16) and 0xff)) / 2
        val rightLuma = (((r1 ushr 16) and 0xff) + ((r2 ushr 16) and 0xff)) / 2
        val maxShoulderLuma = max(leftLuma, rightLuma)
        return peakLuma >= 215 && (peakLuma - maxShoulderLuma) >= 48 && peakResp >= MIN_CORE_RESPONSE
    }

    /** Returns `(luma << 16) | (domChan << 8) | response` from JFIF BT.601 YUV. */
    private fun sampleMetrics(p: YuvPlanes, x: Int, y: Int, mode: Int): Int {
        val yIdx = y * p.yStride + x
        val uvIdx = (y ushr 1) * p.uvStride + (x ushr 1) * p.uvPixelStride
        if (yIdx < 0 || yIdx >= p.y.limit() ||
            uvIdx < 0 || uvIdx >= p.u.limit() || uvIdx >= p.v.limit()
        ) {
            return 0
        }
        val yv = p.y.get(yIdx).toInt() and 0xff
        val cb = (p.u.get(uvIdx).toInt() and 0xff) - 128
        val cr = (p.v.get(uvIdx).toInt() and 0xff) - 128
        val r = (yv + ((359 * cr + 128) shr 8)).coerceIn(0, 255)
        val g = (yv - ((88 * cb + 183 * cr + 128) shr 8)).coerceIn(0, 255)
        val b = (yv + ((454 * cb + 128) shr 8)).coerceIn(0, 255)

        val resp: Int
        val dom: Int
        when (mode) {
            MODE_GREEN -> {
                resp = greenResponse(r, g, b)
                dom = g
            }
            MODE_AUTO -> {
                val rr = redResponse(r, g, b)
                val gg = greenResponse(r, g, b)
                if (rr >= gg) { resp = rr; dom = r } else { resp = gg; dom = g }
            }
            else -> {
                resp = redResponse(r, g, b)
                dom = r
            }
        }
        return (yv shl 16) or (dom shl 8) or resp
    }

    private fun redResponse(r: Int, g: Int, b: Int): Int {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val c = mx - mn
        if (c == 0 || 510 * c < (2 * MIN_CORE_SAT - 1) * mx) return 0
        val ok = when {
            r == mx -> g < b || 60 * (g - b) < 29 * c
            g == mx -> false
            else -> 60 * (r - g) >= 59 * c
        }
        return if (ok) c else 0
    }

    private fun greenResponse(r: Int, g: Int, b: Int): Int {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val c = mx - mn
        if (c == 0 || 510 * c < (2 * MIN_CORE_SAT - 1) * mx) return 0
        val ok = when {
            r == mx -> false
            g == mx -> 60 * (b - r) >= -41 * c
            else -> 60 * (r - g) < -59 * c
        }
        return if (ok) c else 0
    }

    private const val MODE_RED = 0
    private const val MODE_GREEN = 1
    private const val MODE_AUTO = 2

    private const val MIN_PASS_POINTS = 10
    private const val MIN_PASS_FRACTION = 0.25f
    private const val MIN_CORE_SAT = 65
    private const val MIN_CORE_RESPONSE = 32
    private const val MIN_DOM_CHANNEL = 98
    private const val MIN_CHROMA_CONTRAST = 22
    private const val MAX_SHOULDER_RATIO = 0.75f
}
