package com.tomppi.enderslicer.viewer

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shared ribbon geometry for the nozzle-path renderers.
 *
 * Both G-code dialects paint the same 18-vertex window - top face plus two side
 * faces - from the same 10-value move layout. What differs is only where the
 * bead width comes from (Prusa's authoritative ;WIDTH: markers versus Cura's
 * extrusion delta estimation) and how the window is coloured.
 *
 * The geometry lives here because a window's corners must be offset by the
 * boundary normals of the JOINT, not by the window's own perpendicular: that is
 * what makes neighbouring windows share their corner coordinates exactly, so a
 * wall is one smooth strip instead of a row of separate quads with notches at
 * every corner.
 */
internal object RibbonPathGeometry {

    /** Layout of the move buffers of [GcodeNozzlePath] and [PrusaNozzlePath]. */
    const val VALUES_PER_MOVE = 10
    const val X1 = 0
    const val Y1 = 1
    const val Z1 = 2
    const val X2 = 3
    const val Y2 = 4
    const val Z2 = 5
    const val SPEED = 6
    const val KIND = 7
    /** Bead width (Prusa) or extrusion delta (Cura); interpreted by widthAt. */
    const val WIDTH_OR_DELTA_E = 8
    /** Layer height (Prusa) or parsed layer height (Cura). */
    const val HEIGHT = 9

    /** Top face (2 triangles) plus two side faces (2 triangles each). */
    const val WINDOW_VERTICES = 18
    const val BUILD_PROGRESS_STRIDE = 16_384
    /** Runs split when the next segment turns more than this from the first. */
    const val TURN_SPLIT_DOT = 0.65f
    /** How close two window ends must be to count as one chained run. */
    const val CHAIN_EPS = 0.05f

    private const val EPS = 1e-7f
    private const val MITER_MIN_SQ = 1e-3f

    /** Unit vector of a chord, or null when the chord is degenerate. */
    fun unitDirection(dx: Float, dy: Float): Pair<Float, Float>? {
        val length = sqrt(dx * dx + dy * dy)
        return if (length > EPS) Pair(dx / length, dy / length) else null
    }

    /**
     * Boundary normal of a joint between two window chords.
     *
     * Inside one continuous chain this is the miter of both tangents, so the
     * closing window and the opening window share the exact same corner. At a
     * chain end (run boundary, travel, width step) the remaining side caps with
     * ITS OWN tangent, so bead ends stay perpendicular to the bead and are never
     * skewed by a neighbour.
     */
    fun jointNormal(inDir: Pair<Float, Float>?, outDir: Pair<Float, Float>?): Pair<Float, Float> {
        if (inDir != null && outDir != null) {
            val mx = inDir.first + outDir.first
            val my = inDir.second + outDir.second
            val m2 = mx * mx + my * my
            if (m2 >= MITER_MIN_SQ) {
                val length = sqrt(m2)
                return Pair(-my / length, mx / length)
            }
        }
        if (inDir != null) return Pair(-inDir.second, inDir.first)
        if (outDir != null) return Pair(-outDir.second, outDir.first)
        return Pair(1f, 0f)
    }

    /**
     * True when moves a and b are one continuous extrusion chain: both extrude,
     * their widths match within [widthTolerance], and b starts where a ended.
     */
    fun extrusionSeg(a: Int, b: Int, moves: FloatArray, widthAt: (Int) -> Float, widthTolerance: Float): Boolean {
        if (a < 0 || b >= moves.size / VALUES_PER_MOVE) return false
        val oa = a * VALUES_PER_MOVE
        val ob = b * VALUES_PER_MOVE
        if (moves[oa + KIND] != 1f) return false
        if (moves[ob + KIND] != 1f) return false
        if (kotlin.math.abs(widthAt(a) - widthAt(b)) > widthTolerance) return false
        val dx = moves[ob + X1] - moves[oa + X2]
        val dy = moves[ob + Y1] - moves[oa + Y2]
        return dx * dx + dy * dy <= CHAIN_EPS * CHAIN_EPS
    }

    /**
     * Boundary normal at the joint between move k-1 and move k. Both sides of a
     * chain boundary get the same normal, which is what keeps the strip sealed.
     */
    fun boundaryNormal(
        k: Int,
        moves: FloatArray,
        moveCount: Int,
        widthAt: (Int) -> Float,
        widthTolerance: Float,
    ): Pair<Float, Float> {
        val inOffset = if (k > 0) (k - 1) * VALUES_PER_MOVE else -1
        val outOffset = if (k < moveCount) k * VALUES_PER_MOVE else -1
        val inExtrudes = inOffset >= 0 && moves[inOffset + KIND] == 1f
        val outExtrudes = outOffset >= 0 && moves[outOffset + KIND] == 1f
        val inDir = if (inOffset >= 0) unitDirection(moves[inOffset + X2] - moves[inOffset + X1], moves[inOffset + Y2] - moves[inOffset + Y1]) else null
        val outDir = if (outOffset >= 0) unitDirection(moves[outOffset + X2] - moves[outOffset + X1], moves[outOffset + Y2] - moves[outOffset + Y1]) else null
        val chained = inExtrudes && outExtrudes &&
            extrusionSeg(k - 1, k, moves, widthAt, widthTolerance)
        return if (chained) {
            jointNormal(inDir, outDir)
        } else {
            jointNormal(
                inDir.takeIf { inExtrudes },
                outDir.takeIf { outExtrudes },
            )
        }
    }

    /** Extrusion speed range of a path, used for the speed-to-hue mapping. */
    fun speedRange(moves: FloatArray, moveCount: Int): Pair<Float, Float> {
        var minSpeed = Float.POSITIVE_INFINITY
        var maxSpeed = Float.NEGATIVE_INFINITY
        for (m in 0 until moveCount) {
            val o = m * VALUES_PER_MOVE
            if (moves[o + KIND] == 1f) {
                val speed = moves[o + SPEED]
                minSpeed = min(minSpeed, speed)
                maxSpeed = max(maxSpeed, speed)
            }
        }
        return Pair(minSpeed, maxSpeed)
    }

    /**
     * Walks the path in natural runs - consecutive moves of the same kind and
     * width that stay collinear - and reports windows of up to one move each.
     * The window callback receives the run's width and height so the caller can
     * colour and size the strip.
     */
    fun forEachWindow(
        moves: FloatArray,
        moveCount: Int,
        widthAt: (Int) -> Float,
        widthTolerance: Float,
        onProgress: ((Float) -> Unit)? = null,
        onWindow: (
            kind: Float,
            winStart: Int,
            winLast: Int,
            sx: Float, sy: Float, sz: Float,
            ex: Float, ey: Float, ez: Float,
            runWidth: Float,
            runHeight: Float,
        ) -> Unit,
    ) {
        var lastReportedMove = 0
        var moveIndex = 0
        while (moveIndex < moveCount) {
            if (onProgress != null && moveIndex - lastReportedMove >= BUILD_PROGRESS_STRIDE) {
                lastReportedMove = moveIndex
                onProgress((moveIndex.toFloat() / max(moveCount, 1)).coerceIn(0f, 1f))
            }
            val oi = moveIndex * VALUES_PER_MOVE
            val kind = moves[oi + KIND]
            val runWidth = widthAt(moveIndex)
            val runHeight = moves[oi + HEIGHT]
            // Natural run: same kind + collinear + same width marker.
            var runEnd = moveIndex + 1
            var fdx = moves[oi + X2] - moves[oi + X1]
            var fdy = moves[oi + Y2] - moves[oi + Y1]
            val firstLen = sqrt(fdx * fdx + fdy * fdy)
            if (firstLen > EPS) {
                fdx /= firstLen
                fdy /= firstLen
            }
            while (runEnd < moveCount &&
                moves[runEnd * VALUES_PER_MOVE + KIND] == kind &&
                kotlin.math.abs(widthAt(runEnd) - runWidth) <= widthTolerance
            ) {
                val ro = runEnd * VALUES_PER_MOVE
                var ndx = moves[ro + X2] - moves[ro + X1]
                var ndy = moves[ro + Y2] - moves[ro + Y1]
                val nlen = sqrt(ndx * ndx + ndy * ndy)
                if (nlen > EPS && fdx * ndx / nlen + fdy * ndy / nlen < TURN_SPLIT_DOT) break
                runEnd++
            }
            var winStart = moveIndex
            while (winStart < runEnd) {
                val winLast = winStart
                val wo = winStart * VALUES_PER_MOVE
                val wl = winLast * VALUES_PER_MOVE
                onWindow(
                    kind,
                    winStart,
                    winLast,
                    moves[wo + X1], moves[wo + Y1], moves[wo + Z1],
                    moves[wl + X2], moves[wl + Y2], moves[wl + Z2],
                    runWidth,
                    runHeight,
                )
                winStart = winLast + 1
            }
            moveIndex = runEnd
        }
    }

    /**
     * Pushes one ribbon window: a top face at z + height and two side faces
     * reaching down to z. The start corners are offset by the start boundary
     * normal and the end corners by the end normal, so consecutive windows meet
     * exactly. Side faces use per-vertex (Gouraud) normals, which keeps walls
     * shaded continuously instead of banding per window.
     */
    fun addStrip(
        vertex: DirectFloatSink,
        normals: DirectFloatSink,
        colors: DirectFloatSink,
        ambient: DirectFloatSink,
        sx: Float, sy: Float, sz: Float,
        ex: Float, ey: Float, ez: Float,
        width: Float,
        height: Float,
        startNx: Float, startNy: Float,
        endNx: Float, endNy: Float,
        color: FloatArray,
        topAmbient: Float,
        sideBaseAmbient: Float,
        sideTopAmbient: Float,
    ) {
        val half = width * 0.5f
        val px = startNx * half
        val py = startNy * half
        val qx = endNx * half
        val qy = endNy * half
        // Quad corners: a/b at the start (offset by the START normal), c/d at
        // the end (offset by the END normal). A shared boundary gives the same
        // corner coordinates in the closing and the opening window.
        val ax = sx - px; val ay = sy - py
        val bx = sx + px; val by = sy + py
        val cx = ex + qx; val cy = ey + qy
        val dxd = ex - qx; val dyd = ey - qy
        fun pushColor(vertexCount: Int) {
            repeat(vertexCount) {
                colors += color[0]
                colors += color[1]
                colors += color[2]
                colors += color[3]
            }
        }

        // Top face, normal +Z.
        vertex += ax; vertex += ay; vertex += sz + height
        vertex += bx; vertex += by; vertex += sz + height
        vertex += cx; vertex += cy; vertex += ez + height
        vertex += ax; vertex += ay; vertex += sz + height
        vertex += cx; vertex += cy; vertex += ez + height
        vertex += dxd; vertex += dyd; vertex += ez + height
        repeat(6) { normals += 0f; normals += 0f; normals += 1f }
        pushColor(6)
        repeat(6) { ambient += topAmbient }

        // Left side face (- normal).
        vertex += ax; vertex += ay; vertex += sz
        vertex += dxd; vertex += dyd; vertex += ez
        vertex += dxd; vertex += dyd; vertex += ez + height
        normals += -startNx; normals += -startNy; normals += 0f
        normals += -endNx; normals += -endNy; normals += 0f
        normals += -endNx; normals += -endNy; normals += 0f
        pushColor(3)
        ambient += sideBaseAmbient
        ambient += sideBaseAmbient
        ambient += sideTopAmbient

        vertex += ax; vertex += ay; vertex += sz
        vertex += dxd; vertex += dyd; vertex += ez + height
        vertex += ax; vertex += ay; vertex += sz + height
        normals += -startNx; normals += -startNy; normals += 0f
        normals += -endNx; normals += -endNy; normals += 0f
        normals += -startNx; normals += -startNy; normals += 0f
        pushColor(3)
        ambient += sideBaseAmbient
        ambient += sideTopAmbient
        ambient += sideTopAmbient

        // Right side face (+ normal).
        vertex += bx; vertex += by; vertex += sz
        vertex += cx; vertex += cy; vertex += ez
        vertex += cx; vertex += cy; vertex += ez + height
        normals += startNx; normals += startNy; normals += 0f
        normals += endNx; normals += endNy; normals += 0f
        normals += endNx; normals += endNy; normals += 0f
        pushColor(3)
        ambient += sideBaseAmbient
        ambient += sideBaseAmbient
        ambient += sideTopAmbient

        vertex += bx; vertex += by; vertex += sz
        vertex += cx; vertex += cy; vertex += ez + height
        vertex += bx; vertex += by; vertex += sz + height
        normals += startNx; normals += startNy; normals += 0f
        normals += endNx; normals += endNy; normals += 0f
        normals += startNx; normals += startNy; normals += 0f
        pushColor(3)
        ambient += sideBaseAmbient
        ambient += sideTopAmbient
        ambient += sideTopAmbient
    }
}
