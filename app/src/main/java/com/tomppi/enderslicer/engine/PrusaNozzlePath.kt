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
    private const val DEFAULT_MAX_MOVES = 1_000_000
    private const val MOTION_EPSILON = 1e-7
    private const val CANCELLATION_INTERVAL = 2_048

    fun parse(file: File): PrusaNozzlePath = parse(file, DEFAULT_MAX_MOVES)

    fun parse(file: File, progress: (Float) -> Unit): PrusaNozzlePath =
        parse(file, DEFAULT_MAX_MOVES, progress)

    internal fun parse(file: File, maxMoves: Int): PrusaNozzlePath = parse(file, maxMoves) {}

    internal fun parse(file: File, maxMoves: Int, progress: (Float) -> Unit): PrusaNozzlePath {
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
        val retainedCount = if (sourceMoveCount <= maxMoves) sourceMoveCount else maxMoves

        // Collect the sampled moves with their marker-driven width/height.
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

        fun emit(kind: PrusaNozzlePath.Kind, sx: Double, sy: Double, sz: Double, ex: Double, ey: Double, ez: Double) {
            val retainedIndex = sourceIndex
            sourceIndex++
            // The bounds cover every source move, sampled or not, so the preview
            // still frames the whole print.
            minX = minOf(minX, sx.toFloat(), ex.toFloat())
            minY = minOf(minY, sy.toFloat(), ey.toFloat())
            minZ = minOf(minZ, sz.toFloat(), ez.toFloat())
            maxX = maxOf(maxX, sx.toFloat(), ex.toFloat())
            maxY = maxOf(maxY, sy.toFloat(), ey.toFloat())
            maxZ = maxOf(maxZ, sz.toFloat(), ez.toFloat())
            if (!GcodeNozzlePathParser.shouldRetain(retainedIndex, sourceMoveCount, maxMoves)) return
            sourceIndices.add(retainedIndex)
            accumulator.add(
                sx.toFloat(), sy.toFloat(), sz.toFloat(),
                ex.toFloat(), ey.toFloat(), ez.toFloat(),
                (feedRateMmPerMinute / 60.0).coerceAtLeast(0.0).toFloat(),
                kind.code,
                width,
                height,
            )
            if (kind == PrusaNozzlePath.Kind.EXTRUSION) extrusionMoves++ else travelMoves++
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
                        val nextX = modalState.position(x, command.value('X'))
                        val nextY = modalState.position(y, command.value('Y'))
                        val nextZ = modalState.position(z, command.value('Z'))
                        command.value('F')?.let { feedRateMmPerMinute = it }
                        val dx = nextX - x
                        val dy = nextY - y
                        val dz = nextZ - z
                        val distance = sqrt(dx * dx + dy * dy + dz * dz)
                        if (distance > MOTION_EPSILON) {
                            val kind = if (command.value('E') != null) PrusaNozzlePath.Kind.EXTRUSION else PrusaNozzlePath.Kind.TRAVEL
                            emit(kind, x, y, z, nextX, nextY, nextZ)
                        }
                        x = nextX
                        y = nextY
                        z = nextZ
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