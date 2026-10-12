package com.tomppi.enderslicer.engine

import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Nozzle path for PrusaSlicer output. Unlike GcodeNozzlePath, geometry uses
 * Prusa’s own per-segment ;WIDTH: and per-layer ;HEIGHT: markers instead of
 * estimating bead width from extrusion deltas: no quantization noise, no fat
 * windows, no estimation errors — what Prusa planned is what is drawn.
 */
data class PrusaNozzlePath(
    val moves: FloatArray,
    val sourceMoveIndices: IntArray,
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
    val extrusionMoveCount: Int,
    val travelMoveCount: Int,
    val sourceMoveCount: Int,
    val layerCount: Int,
    val truncated: Boolean,
) {
    val moveCount: Int get() = moves.size / VALUES_PER_MOVE

    init {
        require(sourceMoveIndices.size == moveCount)
    }

    enum class Kind(val code: Float) {
        TRAVEL(0f),
        EXTRUSION(1f),
    }

    companion object {
        const val VALUES_PER_MOVE = 10
        const val X1 = 0
        const val Y1 = 1
        const val Z1 = 2
        const val X2 = 3
        const val Y2 = 4
        const val Z2 = 5
        const val SPEED = 6
        const val KIND = 7
        /** Bead width from the ;WIDTH: marker (authoritative). */
        const val WIDTH = 8
        /** Layer height from the ;HEIGHT: marker (authoritative). */
        const val HEIGHT = 9
    }
}

/** Parses a PrusaSlicer gcode file into [PrusaNozzlePath]. */
object PrusaNozzlePathParser {
    internal const val DEFAULT_MAX_MOVES = 1_000_000
    private const val MOTION_EPSILON = 1e-7
    private const val CANCELLATION_INTERVAL = 2_048

    fun parse(file: File): PrusaNozzlePath = parse(file, DEFAULT_MAX_MOVES)

    fun parse(file: File, progress: (Float) -> Unit): PrusaNozzlePath =
        parse(file, DEFAULT_MAX_MOVES, progress)

    internal fun parse(file: File, maxMoves: Int): PrusaNozzlePath = parse(file, maxMoves) {}

    /**
     * Parses with an explicit move budget: the preview passes the budget its device profile
     * derived, so a phone with RAM to spare folds nothing and a low-memory one keeps the
     * conservative figure. The budget never drops a move either way - it only merges.
     */
    fun parse(file: File, maxMoves: Int, progress: (Float) -> Unit): PrusaNozzlePath {
        require(file.isFile && file.length() > 0L) { "Generated G-code is not available for nozzle-path preview" }
        require(maxMoves > 1) { "Nozzle-path move limit must retain at least the first and final move" }

        // The retained set is an even sample of the whole print, so the number of
        // spatial moves has to be known before the first move is emitted. The Cura
        // nozzle path counts first for the same reason; sharing its counter and
        // its sampling keeps both previews identical (a print above the cap used
        // to keep every move here while the Cura preview sampled to the cap).
        // ORCA accepts both layer spellings this view can see - PrusaSlicer's
        // ";LAYER_CHANGE" and the Bambu envelope's "; CHANGE_LAYER" - so one counter
        // serves both engines this parser is asked to preview.
        val sourceMoveCount = GcodeNozzlePathParser.countSpatialMoves(file, GcodeDialect.ORCA, progress)
        require(sourceMoveCount > 0) { "No nozzle moves were found in the G-code" }
        // A move the budget cannot keep is folded into the run it continues, never dropped -
        // the same rule GcodeNozzlePathParser folds by, so one print drawn through either
        // engine closes its runs in the same places. The budget is a memory guard, not a
        // sampling policy: it may only make moves longer.
        val retainedCount = if (sourceMoveCount <= maxMoves) sourceMoveCount else maxMoves

        // Collect the folded moves with their marker-driven width/height.
        val accumulator = FloatAccumulator(PrusaNozzlePath.VALUES_PER_MOVE * retainedCount)
        val sourceIndices = ArrayList<Int>(retainedCount)
        val modalState = GcodeModalState()
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var feedRateMmPerMinute = 0.0
        var width = 0.44f
        var height = 0.2f
        var printStarted = false
        var printDone = false
        var layerCount = 0
        var sourceIndex = 0
        var extrusionMoves = 0
        var travelMoves = 0
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var linesRead = 0

        // The span being folded: the moves that will become one straight drawn move.
        var merging = false
        var mergingKind = PrusaNozzlePath.Kind.TRAVEL.code
        var mergingDirX = 0.0
        var mergingDirY = 0.0
        var mergingStartX = 0.0
        var mergingStartY = 0.0
        var mergingStartZ = 0.0
        var mergingSpeed = 0f
        var mergingWidth = width
        var mergingHeight = height
        var mergingMoves = 0
        var mergingSourceIndex = 0

        // The sample spreads the budget over the print: a run may close at a sampled move once
        // it has stood for its share of the moves, so a capped preview is still drawn all the
        // way to the end instead of folding the whole file into a handful of long lines.
        val overBudget = sourceMoveCount > maxMoves
        val spanTarget = if (overBudget) max(1.0, sourceMoveCount.toDouble() / maxMoves) else 0.0

        /**
         * Whether this move genuinely continues the run being folded, so the one straight move
         * drawn for the run still covers it: the same kind of move, in the same direction, and
         * no further off the run's line than [GcodeNozzlePathParser.FOLD_DRIFT_MM].
         */
        fun continuesSpan(sx: Double, sy: Double, ex: Double, ey: Double, kind: Float): Boolean {
            if (!merging) return false
            if (kind != mergingKind) return false
            val dx = ex - sx
            val dy = ey - sy
            val length = sqrt(dx * dx + dy * dy)
            if (length > 0.0 &&
                (dx / length) * mergingDirX + (dy / length) * mergingDirY < GcodeNozzlePathParser.FOLD_COLLINEAR_DOT
            ) {
                return false
            }
            val drift = kotlin.math.abs((ex - mergingStartX) * -mergingDirY + (ey - mergingStartY) * mergingDirX)
            return drift <= GcodeNozzlePathParser.FOLD_DRIFT_MM
        }

        /** Hands one drawn straight move to the preview, and says what kind it was. */
        fun emitSpan(
            fromX: Double, fromY: Double, fromZ: Double,
            toX: Double, toY: Double, toZ: Double,
            spanKind: Float,
            spanSpeed: Float,
            spanWidth: Float,
            spanHeight: Float,
            spanSourceIndex: Int,
        ) {
            sourceIndices.add(spanSourceIndex)
            accumulator.add(
                fromX.toFloat(), fromY.toFloat(), fromZ.toFloat(),
                toX.toFloat(), toY.toFloat(), toZ.toFloat(),
                spanSpeed,
                spanKind,
                spanWidth,
                spanHeight,
            )
            if (spanKind == PrusaNozzlePath.Kind.EXTRUSION.code) extrusionMoves++ else travelMoves++
        }

        val totalBytes = file.length().coerceAtLeast(1L)
        progressReader(file) { bytes ->
            // The count pass already reported 0..0.5, so this pass finishes the bar.
            progress((0.5 + bytes.toDouble() / totalBytes * 0.5).toFloat().coerceIn(0f, 1f))
        }.useLines { lines ->
            lines.forEach { rawLine ->
                linesRead++
                if (linesRead % CANCELLATION_INTERVAL == 0 && Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Prusa nozzle-path parsing was cancelled")
                }
                val line = rawLine.trimStart()
                // Region: skip the ;TYPE:Custom start block and the trailing
                // end gcode + prusaslicer_config dump; keep the printed part only.
                if (line.startsWith(";LAYER_CHANGE") || line.startsWith("; CHANGE_LAYER")) {
                    printStarted = true
                    layerCount++
                    return@forEach
                }
                if (!printStarted) return@forEach
                if (printDone) return@forEach
                if (line.startsWith(";TYPE:Custom") || line.startsWith("; FEATURE: Custom")) {
                    printDone = true
                    return@forEach
                }
                if (line.startsWith(";WIDTH:") || line.startsWith("; LINE_WIDTH:")) {
                    line.substringAfter(':').trim().toFloatOrNull()?.let { parsed ->
                        width = parsed.coerceIn(0.10f, 2.0f)
                    }
                    return@forEach
                }
                if (line.startsWith(";HEIGHT:") || line.startsWith("; LAYER_HEIGHT:")) {
                    line.substringAfter(':').trim().toFloatOrNull()?.let { parsed ->
                        height = parsed.coerceIn(0.02f, 2.0f)
                    }
                    return@forEach
                }
                val command = GcodeCommand.parse(rawLine) ?: return@forEach
                GcodeCommandPolicy.requirePreviewSafe(command, sourceIndex)
                if (modalState.apply(command)) return@forEach
                when (command.opcode) {
                    "G92" -> {
                        command.value('X')?.let { x = it }
                        command.value('Y')?.let { y = it }
                        command.value('Z')?.let { z = it }
                    }
                    "G28" -> { x = 0.0; y = 0.0; z = 0.0 }
                    "G0", "G1" -> {
                        val startX = x
                        val startY = y
                        val startZ = z
                        val nextX = modalState.position(x, command.value('X'))
                        val nextY = modalState.position(y, command.value('Y'))
                        val nextZ = modalState.position(z, command.value('Z'))
                        command.value('F')?.let { feedRateMmPerMinute = it }
                        x = nextX
                        y = nextY
                        z = nextZ
                        val dx = nextX - startX
                        val dy = nextY - startY
                        val dz = nextZ - startZ
                        val distance = sqrt(dx * dx + dy * dy + dz * dz)
                        if (distance <= MOTION_EPSILON) return@forEach

                        val kind = if (command.value('E') != null) {
                            PrusaNozzlePath.Kind.EXTRUSION
                        } else {
                            PrusaNozzlePath.Kind.TRAVEL
                        }
                        val sourceIndexOfMove = sourceIndex
                        sourceIndex++
                        // The bounds cover every source move, folded or not, so the preview
                        // still frames the whole print.
                        minX = minOf(minX, startX.toFloat(), nextX.toFloat())
                        minY = minOf(minY, startY.toFloat(), nextY.toFloat())
                        minZ = minOf(minZ, startZ.toFloat(), nextZ.toFloat())
                        maxX = maxOf(maxX, startX.toFloat(), nextX.toFloat())
                        maxY = maxOf(maxY, startY.toFloat(), nextY.toFloat())
                        maxZ = maxOf(maxZ, startZ.toFloat(), nextZ.toFloat())

                        val speed = (feedRateMmPerMinute / 60.0).coerceAtLeast(0.0).toFloat()
                        val requested = GcodeNozzlePathParser.shouldRetain(sourceIndexOfMove, sourceMoveCount, maxMoves)
                        val continues = continuesSpan(startX, startY, nextX, nextY, kind.code)
                        // The budget may only make a span longer, never fewer: a span closes at a
                        // sampled move once it has spent its share of the budget, at the first and
                        // last move, and never across a corner or a kind change.
                        val closeHere = !overBudget ||
                            sourceIndexOfMove == 0 ||
                            sourceIndexOfMove == sourceMoveCount - 1 ||
                            (requested && mergingMoves + 1 >= spanTarget)

                        if (continues && !closeHere) {
                            // Fold this move into the span: it continues the run, so the one
                            // straight line drawn for the span still covers it.
                            mergingMoves++
                            mergingSourceIndex = sourceIndexOfMove
                            mergingSpeed = speed
                            mergingWidth = width
                            mergingHeight = height
                            return@forEach
                        }
                        if (merging && !continues) {
                            // This move does not continue the run: the span ends at the move
                            // before it, and the printer's own turn is drawn, not a chord
                            // across it.
                            emitSpan(
                                mergingStartX, mergingStartY, mergingStartZ,
                                startX, startY, startZ,
                                mergingKind, mergingSpeed, mergingWidth, mergingHeight, mergingSourceIndex,
                            )
                            merging = false
                        }
                        if (closeHere) {
                            emitSpan(
                                if (merging) mergingStartX else startX,
                                if (merging) mergingStartY else startY,
                                if (merging) mergingStartZ else startZ,
                                nextX, nextY, nextZ,
                                if (merging) mergingKind else kind.code,
                                speed, width, height, sourceIndexOfMove,
                            )
                            merging = false
                        } else {
                            // This move starts the next span.
                            merging = true
                            mergingKind = kind.code
                            val foldX = nextX - startX
                            val foldY = nextY - startY
                            val foldLength = sqrt(foldX * foldX + foldY * foldY)
                            if (foldLength > 0.0) {
                                mergingDirX = foldX / foldLength
                                mergingDirY = foldY / foldLength
                            } else {
                                mergingDirX = 0.0
                                mergingDirY = 0.0
                            }
                            mergingStartX = startX
                            mergingStartY = startY
                            mergingStartZ = startZ
                            mergingSpeed = speed
                            mergingWidth = width
                            mergingHeight = height
                            mergingMoves = 1
                            mergingSourceIndex = sourceIndexOfMove
                        }
                    }
                }
            }
        }

        require(accumulator.size > 0) { "No nozzle moves remained after preview sampling" }
        progress(1f)
        return PrusaNozzlePath(
            moves = accumulator.toArray(),
            sourceMoveIndices = sourceIndices.toIntArray(),
            minX = minX, minY = minY, minZ = minZ,
            maxX = maxX, maxY = maxY, maxZ = maxZ,
            extrusionMoveCount = extrusionMoves,
            travelMoveCount = travelMoves,
            sourceMoveCount = sourceMoveCount,
            layerCount = layerCount,
            truncated = sourceMoveCount > maxMoves,
        )
    }
}