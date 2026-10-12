package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.engine.GcodeNozzlePath
import com.tomppi.enderslicer.engine.PrusaNozzlePath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a parsed nozzle path into ribbon windows, one per move, into a
 * [RibbonChunkStaging] area.
 *
 * The two dialects build the same 18-vertex window from the same move layout and
 * differ only in where the bead size comes from; both were private loops inside
 * their GL renderers. They live here - free of Android and GL - so the geometry,
 * and the memory it costs, can be exercised on the JVM: the Path tab's crash was
 * a build that needed 616 MB of geometry in one go, and only a test that builds a
 * 900,000-move file can keep that from coming back.
 *
 * One window per move, always. The parser's move budget is the only thing that may
 * reduce the count, and it merges the moves it cannot keep into the next one -
 * never drops them, because a dropped move is a hole in a wall.
 */

/** Micro-segments (sub-0.05 mm) emit with zero width. */
private const val HAIRLINE_WIDTH_RATIO = 0.5f
private const val RIBBON_THIN_LAYER_TINT = 0.995f
private const val RIBBON_LAYER_TINT = 0.96f
private const val TOP_AMBIENT = 0.97f
private const val SIDE_TOP_AMBIENT = 0.92f
private const val SIDE_BASE_AMBIENT = 0.74f
private const val FINE_SIDE_BASE_AMBIENT = 0.88f
private const val FINE_SIDE_TOP_AMBIENT = 0.95f

/** CuraEngine's build: bead width from the extrusion delta, colour from the speed. */
internal class CuraRibbonBuilder(
    private val path: GcodeNozzlePath,
    private val beadHeightMm: Float,
    private val beadLineWidthMm: Float,
    private val filamentAreaMm2: Float,
    private val onProgress: ((Float) -> Unit)? = null,
) {
    /** Extrusion windows emitted up to and including move m. */
    val extrusionPrefix = IntArray(path.moveCount + 1)

    /** Travel lines emitted up to and including move m. */
    val travelPrefix = IntArray(path.moveCount + 1)

    /** Windows handed to the staging area; one per retained extrusion move. */
    var extrusionWindows = 0
        private set

    /** Travel lines handed to the staging area. */
    var travelSegments = 0
        private set

    /**
     * Builds the runs of moves in [firstMove] until [lastMove] - the whole path by default,
     * or one band of it when the preview only holds the layers around the scrub. The speed
     * ramp is measured over the WHOLE path either way, so a band's colours do not shift as
     * the band moves.
     */
    fun build(staging: RibbonChunkStaging, firstMove: Int = 0, lastMove: Int = path.moveCount) {
        val source = path.moves
        var minSpeed = Float.POSITIVE_INFINITY
        var maxSpeed = Float.NEGATIVE_INFINITY
        for (moveIndex in 0 until path.moveCount) {
            val moveOffset = moveIndex * GcodeNozzlePath.VALUES_PER_MOVE
            if (source[moveOffset + GcodeNozzlePath.KIND] == GcodeNozzlePath.Kind.EXTRUSION.code) {
                val speed = source[moveOffset + GcodeNozzlePath.SPEED]
                minSpeed = min(minSpeed, speed)
                maxSpeed = max(maxSpeed, speed)
            }
        }
        val speedSpan = maxSpeed - minSpeed
        var extrusionMoves = 0
        var travelMoves = 0
        var lastReportedMove = 0
        // Paint in contiguous same-kind windows: one window per move, so a long
        // print stays continuous instead of alternating gaps.
        var moveIndex = firstMove
        while (moveIndex < lastMove) {
            if (moveIndex - lastReportedMove >= RibbonPathGeometry.BUILD_PROGRESS_STRIDE) {
                lastReportedMove = moveIndex
                onProgress?.invoke(
                    ((moveIndex - firstMove).toFloat() / max(lastMove - firstMove, 1)).coerceIn(0f, 1f),
                )
            }
            val windowOffset = moveIndex * GcodeNozzlePath.VALUES_PER_MOVE
            val kind = source[windowOffset + GcodeNozzlePath.KIND]
            val boundsX1 = source[windowOffset + GcodeNozzlePath.X1]
            val boundsY1 = source[windowOffset + GcodeNozzlePath.Y1]
            val boundsZ1 = source[windowOffset + GcodeNozzlePath.Z1]
            val boundsX2 = source[windowOffset + GcodeNozzlePath.X2]
            val boundsY2 = source[windowOffset + GcodeNozzlePath.Y2]
            val boundsZ2 = source[windowOffset + GcodeNozzlePath.Z2]
            // Natural run: consecutive same-kind moves that stay collinear
            // (turn-split at sharp reversals).
            var runEnd = moveIndex + 1
            var firstDx = boundsX2 - boundsX1
            var firstDy = boundsY2 - boundsY1
            val firstLen = sqrt(firstDx * firstDx + firstDy * firstDy)
            if (firstLen > 1e-7f) { firstDx /= firstLen; firstDy /= firstLen }
            while (runEnd < lastMove &&
                source[runEnd * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.KIND] == kind) {
                val ro = runEnd * GcodeNozzlePath.VALUES_PER_MOVE
                var ndx = source[ro + GcodeNozzlePath.X2] - source[ro + GcodeNozzlePath.X1]
                var ndy = source[ro + GcodeNozzlePath.Y2] - source[ro + GcodeNozzlePath.Y1]
                val nlen = sqrt(ndx * ndx + ndy * ndy)
                if (nlen > 1e-7f && firstDx * ndx / nlen + firstDy * ndy / nlen < RibbonPathGeometry.TURN_SPLIT_DOT) break
                runEnd++
            }
            for (m in moveIndex until runEnd) {
                extrusionPrefix[m + 1] = extrusionMoves
                travelPrefix[m + 1] = travelMoves
            }
            val lastOffset = (runEnd - 1) * GcodeNozzlePath.VALUES_PER_MOVE
            val sx = source[windowOffset + GcodeNozzlePath.X1]
            val sy = source[windowOffset + GcodeNozzlePath.Y1]
            val sz = source[windowOffset + GcodeNozzlePath.Z1]
            val ex = source[lastOffset + GcodeNozzlePath.X2]
            val ey = source[lastOffset + GcodeNozzlePath.Y2]
            val ez = source[lastOffset + GcodeNozzlePath.Z2]
            // Run-wide totals: one width for the WHOLE natural run so every
            // window of the same line shares identical geometry -
            // micro-layer borders never alternate width/height.
            val runChord = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy))
            var runDeltaE = 0f
            for (m in moveIndex until runEnd) {
                val mo = m * GcodeNozzlePath.VALUES_PER_MOVE
                runDeltaE += source[mo + GcodeNozzlePath.DELTA_E]
            }
            val runHeight = source[lastOffset + GcodeNozzlePath.LAYER_HEIGHT]
            val speedRatio = if (kind == GcodeNozzlePath.Kind.EXTRUSION.code && speedSpan > 0f) {
                ((source[windowOffset + GcodeNozzlePath.SPEED] - minSpeed) / speedSpan).coerceIn(0f, 1f)
            } else 0f
            val color = if (kind == GcodeNozzlePath.Kind.EXTRUSION.code) {
                hsv(extrusionHue(speedRatio), RIBBON_SATURATION, RIBBON_VALUE, 1f)
            } else floatArrayOf(0.50f, 0.54f, 0.64f, 0.20f)
            if (kind == GcodeNozzlePath.Kind.EXTRUSION.code) {
                // One window per move. Each window uses the run deltaE scaled to
                // ITS OWN chord, so the resolved width equals the run width
                // exactly (same numerator/denominator ratio).
                var windowStart = moveIndex
                while (windowStart < runEnd) {
                    val windowLast = windowStart
                    val wo = windowStart * GcodeNozzlePath.VALUES_PER_MOVE
                    val wlo = windowLast * GcodeNozzlePath.VALUES_PER_MOVE
                    val wsx = source[wo + GcodeNozzlePath.X1]
                    val wsy = source[wo + GcodeNozzlePath.Y1]
                    val wsz = source[wo + GcodeNozzlePath.Z1]
                    val wex = source[wlo + GcodeNozzlePath.X2]
                    val wey = source[wlo + GcodeNozzlePath.Y2]
                    val wez = source[wlo + GcodeNozzlePath.Z2]
                    val wChord = sqrt((wex - wsx) * (wex - wsx) + (wey - wsy) * (wey - wsy))
                    val windowDeltaE = if (runChord > 1e-6f) runDeltaE * (wChord / runChord) else runDeltaE
                    // Window boundaries are joints of the run: miter inside the
                    // run so the closing and opening windows share their corner
                    // coordinates, and a perpendicular cap where the run ends so
                    // bead ends are never skewed by a neighbour.
                    val currentDir = RibbonPathGeometry.unitDirection(wex - wsx, wey - wsy)
                    val previousDir = if (windowStart > moveIndex) {
                        val po = (windowStart - 1) * GcodeNozzlePath.VALUES_PER_MOVE
                        RibbonPathGeometry.unitDirection(
                            source[po + GcodeNozzlePath.X2] - source[po + GcodeNozzlePath.X1],
                            source[po + GcodeNozzlePath.Y2] - source[po + GcodeNozzlePath.Y1],
                        )
                    } else null
                    val nextDir = if (windowLast + 1 < runEnd) {
                        val no = (windowLast + 1) * GcodeNozzlePath.VALUES_PER_MOVE
                        RibbonPathGeometry.unitDirection(
                            source[no + GcodeNozzlePath.X2] - source[no + GcodeNozzlePath.X1],
                            source[no + GcodeNozzlePath.Y2] - source[no + GcodeNozzlePath.Y1],
                        )
                    } else null
                    val startNormal = RibbonPathGeometry.jointNormal(previousDir, currentDir)
                    val endNormal = RibbonPathGeometry.jointNormal(currentDir, nextDir)
                    staging.window { positions, normals, colors, ambientValues ->
                        addRibbonMove(
                            positions, normals, colors, ambientValues,
                            wsx, wsy, wsz, wex, wey, wez,
                            windowDeltaE,
                            runHeight,
                            startNormal.first, startNormal.second,
                            endNormal.first, endNormal.second,
                            color,
                        )
                    }
                    extrusionMoves++
                    for (m in windowStart until windowLast) {
                        extrusionPrefix[m + 1] = extrusionMoves
                        travelPrefix[m + 1] = travelMoves
                    }
                    extrusionPrefix[windowLast + 1] = extrusionMoves
                    windowStart = windowLast + 1
                }
                extrusionPrefix[runEnd] = extrusionMoves
                travelPrefix[runEnd] = travelMoves
            } else {
                // Pure-Z layer transitions are not meaningful travel paths;
                // 599 of them at 0.08 mm would paint vertical scratches over
                // the whole part.
                val travelDx = ex - sx
                val travelDy = ey - sy
                val travelDz = ez - sz
                val travelLen = sqrt(travelDx * travelDx + travelDy * travelDy + travelDz * travelDz)
                val verticalOnly = travelLen > 1e-7f && sqrt(travelDx * travelDx + travelDy * travelDy) < travelLen * 0.25f
                if (!verticalOnly) {
                    staging.travel { positions, colors ->
                        positions += sx; positions += sy; positions += sz
                        positions += ex; positions += ey; positions += ez
                        repeat(2) {
                            colors += color[0]; colors += color[1]
                            colors += color[2]; colors += color[3]
                        }
                    }
                    travelMoves++
                }
                for (m in moveIndex until runEnd) {
                    travelPrefix[m + 1] = travelMoves
                    extrusionPrefix[m + 1] = extrusionMoves
                }
            }
            moveIndex = runEnd
        }
        extrusionWindows = extrusionMoves
        travelSegments = travelMoves
    }

    /**
     * Emits the physical bead for one extrusion window: a colored top face at
     * the path Z plus shaded left/right side faces hanging one layer below it.
     * The width comes from the actual flow (deltaE * filament area / length /
     * layer height) bounded by the settings line width, so the geometry follows
     * the sliced settings (layer height, line width, flow).
     */
    private fun addRibbonMove(
        vertex: FloatSink,
        normals: FloatSink,
        colors: FloatSink,
        ambient: FloatSink,
        sx: Float, sy: Float, sz: Float,
        ex: Float, ey: Float, ez: Float,
        deltaE: Float,
        parsedLayerHeight: Float,
        startNx: Float, startNy: Float,
        endNx: Float, endNy: Float,
        color: FloatArray,
    ) {
        val dx = ex - sx
        val dy = ey - sy
        val length = sqrt(dx * dx + dy * dy)
        val width = resolveBeadWidthMm(
            lengthMm = length,
            deltaE = deltaE,
            parsedLayerHeight = parsedLayerHeight,
            layerHeightFallback = beadHeightMm,
            lineWidth = beadLineWidthMm,
            filamentArea = filamentAreaMm2,
        )
        val height = if (parsedLayerHeight > 0.02f && parsedLayerHeight <= 2.0f) parsedLayerHeight else beadHeightMm
        // A degenerate (sub-cutoff) window would leave a hole in the wall; draw
        // it as a hairline bead so micro-layer prints stay solid. The inspector
        // keeps the true zero via its own readout path.
        val render = if (width <= 0f) beadLineWidthMm * HAIRLINE_WIDTH_RATIO else width
        // Subtle per-layer tint: odd layers are a touch darker so stacked beads
        // read as separate layers. On fine-layer prints the parity tint would
        // band every micro-layer, so fade it out.
        val level = if (height > 0f) (ez / height).roundToInt() else 0
        val parity = if (level and 1 == 0) 1f else {
            if (height < FINE_LAYER_HEIGHT_MM) RIBBON_THIN_LAYER_TINT else RIBBON_LAYER_TINT
        }
        // On fine layers the side/top contrast at micro-layer scale creates
        // scalloped "teeth" along curved rims; soften it so micro-beads read as
        // one continuous surface.
        val fine = height <= FINE_LAYER_HEIGHT_MM
        // Geometry is shared with the Prusa renderer: corners are offset by the
        // boundary normals of the joint, so this window and its neighbours share
        // their corner coordinates exactly and walls stay seamless.
        RibbonPathGeometry.addStrip(
            vertex, normals, colors, ambient,
            sx, sy, sz, ex, ey, ez,
            render, height,
            startNx, startNy, endNx, endNy,
            color,
            parity * TOP_AMBIENT,
            parity * (if (fine) FINE_SIDE_BASE_AMBIENT else SIDE_BASE_AMBIENT),
            parity * (if (fine) FINE_SIDE_TOP_AMBIENT else SIDE_TOP_AMBIENT),
        )
    }
}

/** PrusaSlicer's build: the bead size is read from the file's own ;WIDTH:/;HEIGHT: markers. */
internal class PrusaRibbonBuilder(
    private val path: PrusaNozzlePath,
    private val onProgress: ((Float) -> Unit)? = null,
) {
    /** Ribbon windows emitted up to and including move m. */
    val ribbonPrefix = IntArray(path.moveCount + 1)

    /** Travel lines emitted up to and including move m. */
    val travelPrefix = IntArray(path.moveCount + 1)

    /** Windows handed to the staging area; one per extrusion move. */
    var ribbonWindows = 0
        private set

    /** Travel lines handed to the staging area. */
    var travelSegments = 0
        private set

    /**
     * Builds the runs of moves in [firstMove] until [lastMove] - the whole path by default, or
     * one band of it when the preview only holds the layers around the scrub. The speed ramp
     * is measured over the WHOLE path either way, so a band's colours do not shift as the band
     * moves.
     */
    fun build(staging: RibbonChunkStaging, firstMove: Int = 0, lastMove: Int = path.moveCount) {
        val source = path.moves
        val n = path.moveCount
        var windowCount = 0
        var travelCount = 0
        val (minSpeed, maxSpeed) = RibbonPathGeometry.speedRange(source, n)
        val speedSpan = maxSpeed - minSpeed
        RibbonPathGeometry.forEachWindow(
            source,
            firstMove,
            lastMove,
            { index -> source[index * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.WIDTH] },
            0f,
            onProgress,
        ) { kind, winStart, winLast, sx, sy, sz, ex, ey, ez, runWidth, runHeight ->
            if (kind == PrusaNozzlePath.Kind.EXTRUSION.code) {
                val wo = winStart * PrusaNozzlePath.VALUES_PER_MOVE
                val speedRatio = if (speedSpan > 0f) {
                    ((source[wo + PrusaNozzlePath.SPEED] - minSpeed) / speedSpan).coerceIn(0f, 1f)
                } else 0f
                val color = hsv(extrusionHue(speedRatio), RIBBON_SATURATION, RIBBON_VALUE, 1f)
                // Window boundaries are polyline points of the run: the
                // shared miter normal is identical for the closing window
                // and the next opening window, so corner vertices coincide
                // exactly (continuous strip - no junction slivers).
                val sN = boundaryNormal(winStart, source)
                val eN = boundaryNormal(winLast + 1, source)
                staging.window { positions, normals, colors, ambient ->
                    RibbonPathGeometry.addStrip(
                        positions, normals, colors, ambient,
                        sx, sy, sz, ex, ey, ez,
                        runWidth, runHeight,
                        sN.first, sN.second,
                        eN.first, eN.second,
                        color,
                        PrusaNozzlePathViewDefaults.TOP_AMBIENT,
                        PrusaNozzlePathViewDefaults.SIDE_BASE_AMBIENT,
                        PrusaNozzlePathViewDefaults.SIDE_TOP_AMBIENT,
                    )
                }
                windowCount++
            } else {
                // Skip pure-Z layer transitions (vertical scratches).
                val travelDx = ex - sx
                val travelDy = ey - sy
                val travelDz = ez - sz
                val travelLen = sqrt(travelDx * travelDx + travelDy * travelDy + travelDz * travelDz)
                val verticalOnly = travelLen > 1e-7f && sqrt(travelDx * travelDx + travelDy * travelDy) < travelLen * 0.25f
                if (!verticalOnly) {
                    staging.travel { positions, colors ->
                        positions += sx; positions += sy; positions += sz
                        positions += ex; positions += ey; positions += ez
                        repeat(2) {
                            colors += 0.50f; colors += 0.54f
                            colors += 0.64f; colors += 0.20f
                        }
                    }
                    travelCount++
                }
            }
            for (m in winStart until winLast) {
                ribbonPrefix[m + 1] = windowCount
                travelPrefix[m + 1] = travelCount
            }
            ribbonPrefix[winLast + 1] = windowCount
            travelPrefix[winLast + 1] = travelCount
        }
        ribbonWindows = windowCount
        travelSegments = travelCount
    }

    /**
     * Unit LEFT normal of the sweep at polyline point [k] - the point between
     * moves k-1 and k - as the miter of the two adjacent segment tangents.
     * Both the window that ends at this point and the window that starts at it
     * query the same index, so they receive the exact same normal and their
     * corner vertices coincide: the strip is continuous even across run
     * boundaries. Degenerate 180-degree reversals fall back to the incoming
     * tangent so the corner stays a butt joint, not a crossing.
     */
    private fun boundaryNormal(k: Int, source: FloatArray): Pair<Float, Float> =
        RibbonPathGeometry.boundaryNormal(
            k,
            source,
            source.size / PrusaNozzlePath.VALUES_PER_MOVE,
            { index -> source[index * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.WIDTH] },
            0f,
        )
}

/**
 * Two vertices per stored extrusion move, for the percentile-trimmed camera fit. Computed over
 * the WHOLE path once per path, so a banded preview still frames the printed part and not the
 * band it happens to be holding.
 */
internal fun extrusionBoundsVertices(path: GcodeNozzlePath): FloatArray {
    val source = path.moves
    val count = RibbonPathGeometry.countKind(
        source,
        path.moveCount,
        GcodeNozzlePath.Kind.EXTRUSION.code,
    )
    val sink = DirectFloatSink(max(count * 6, 16))
    for (move in 0 until path.moveCount) {
        val o = move * GcodeNozzlePath.VALUES_PER_MOVE
        if (source[o + GcodeNozzlePath.KIND] != GcodeNozzlePath.Kind.EXTRUSION.code) continue
        sink += source[o + GcodeNozzlePath.X1]
        sink += source[o + GcodeNozzlePath.Y1]
        sink += source[o + GcodeNozzlePath.Z1]
        sink += source[o + GcodeNozzlePath.X2]
        sink += source[o + GcodeNozzlePath.Y2]
        sink += source[o + GcodeNozzlePath.Z2]
    }
    return sink.toFloatArray()
}
