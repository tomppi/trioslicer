package com.rawmechatronics.imucalib.core

import kotlin.math.sqrt

/**
 * The recorded image timestamp belongs to the whole frame, but a rolling-shutter
 * sensor exposes different image rows at different times: a target in the top of
 * the image is seen earlier than one in the bottom. The board-pose calibration
 * therefore times the board at the mean image row of its corners, while a
 * Kalibr run on a board placed elsewhere in the image sees a different time.
 *
 * This measures that mean row for a board whose camera pose is known, so the
 * caller can compensate the solved time offset to a chosen reference row.
 */
object BoardTiming {

    /** Mean image row [px] of the board's ChArUco corners, or null if none project. */
    fun meanCornerRow(
        poses: List<BoardPose>,
        cam: CalibCameraModel,
        squaresX: Int = 4,
        squaresY: Int = 6,
        squareMm: Double = 45.0,
    ): Double? {
        if (poses.isEmpty()) return null
        val corners = ArrayList<DoubleArray>((squaresX - 1) * (squaresY - 1))
        for (j in 0 until squaresY - 1) {
            for (i in 0 until squaresX - 1) {
                // board poses carry metres; the board geometry is given in millimetres
                corners.add(doubleArrayOf((i + 1) * squareMm * 0.001, (j + 1) * squareMm * 0.001, 0.0))
            }
        }
        var sum = 0.0
        var n = 0
        for (bp in poses) {
            val r = bp.rWc
            val p = bp.pWc
            // camera pose in the board frame -> X_cam = R^T (X_board - p)
            for (x in corners) {
                val dx = x[0] - p[0]
                val dy = x[1] - p[1]
                val dz = x[2] - p[2]
                val xc = r[0] * dx + r[3] * dy + r[6] * dz
                val yc = r[1] * dx + r[4] * dy + r[7] * dz
                val zc = r[2] * dx + r[5] * dy + r[8] * dz
                if (!zc.isFinite() || zc < 1e-6) continue
                val xn = xc / zc
                val yn = yc / zc
                val r2 = xn * xn + yn * yn
                val k1 = cam.dist.getOrElse(0) { 0.0 }
                val k2 = cam.dist.getOrElse(1) { 0.0 }
                val p1 = cam.dist.getOrElse(2) { 0.0 }
                val p2 = cam.dist.getOrElse(3) { 0.0 }
                val radial = 1.0 + k1 * r2 + k2 * r2 * r2
                val xd = xn * radial + 2.0 * p1 * xn * yn + p2 * (r2 + 2.0 * xn * xn)
                val yd = yn * radial + p1 * (r2 + 2.0 * yn * yn) + 2.0 * p2 * xn * yn
                val v = cam.fy * yd + cam.cy
                if (!v.isFinite()) continue
                sum += v
                n++
            }
        }
        return if (n == 0) null else sum / n
    }

    /** Sanity helper used by tests and the UI. */
    fun rms(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            s += d * d
        }
        return sqrt(s / a.size)
    }
}
