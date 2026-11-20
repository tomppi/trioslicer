package com.rawmechatronics.imucalib.core

/** Pinhole + radtan camera model (the VIO stream's camera model, 640x480). */
data class PinholeModel(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val k1: Double = 0.0,
    val k2: Double = 0.0,
    val p1: Double = 0.0,
    val p2: Double = 0.0,
) {
    /** Project a camera-frame point [m] to pixels; null behind the camera. */
    fun project(x: Double, y: Double, z: Double): DoubleArray? {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || z <= 1e-9) return null
        val xn = x / z
        val yn = y / z
        val r2 = xn * xn + yn * yn
        val radial = 1.0 + k1 * r2 + k2 * r2 * r2
        val xd = xn * radial + 2.0 * p1 * xn * yn + p2 * (r2 + 2.0 * xn * xn)
        val yd = yn * radial + p1 * (r2 + 2.0 * yn * yn) + 2.0 * p2 * xn * yn
        val u = fx * xd + cx
        val v = fy * yd + cy
        if (!u.isFinite() || !v.isFinite()) return null
        return doubleArrayOf(u, v)
    }
}

/** One observed board point at a camera timestamp [s]. [xyz] (metres, board frame)
 *  is set for ArUco marker corners; otherwise [id] indexes the ChArUco layout. */
data class CornerObservation(
    val t: Double,
    val id: Int,
    val u: Double,
    val v: Double,
    val xyz: DoubleArray? = null,
)

/** The printed ChArUco board: its corner ids and their metric positions in the board frame. */
data class BoardLayout(
    val squaresX: Int = 4,
    val squaresY: Int = 6,
    val squareMm: Double = 45.0,
) {
    val cornerCount: Int get() = (squaresX - 1) * (squaresY - 1)

    /** Board-frame corner position [m] for a ChArUco corner id, or null if out of range. */
    fun corner3d(id: Int): DoubleArray? {
        if (id < 0 || id >= cornerCount) return null
        val cols = squaresX - 1
        val row = id / cols
        val col = id % cols
        return doubleArrayOf(
            (col + 1) * squareMm * 0.001,
            (row + 1) * squareMm * 0.001,
            0.0,
        )
    }
}
