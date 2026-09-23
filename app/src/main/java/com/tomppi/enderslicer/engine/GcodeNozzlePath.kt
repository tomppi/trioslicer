package com.tomppi.enderslicer.engine

import java.io.File
import kotlin.math.sqrt

/** Ordered spatial moves used by the start-to-finish nozzle-path preview. */
data class GcodeNozzlePath(
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
        const val DELTA_E = 8
        const val LAYER_HEIGHT = 9
    }
}

object GcodeNozzlePathParser {
    private const val DEFAULT_MAX_MOVES = 1_000_000
    private const val MOTION_EPSILON = 1e-7
    private const val LAYER_HEIGHT_MIN_MM = 0.010
    private const val LAYER_HEIGHT_MAX_MM = 0.500
    private const val EXTRUSION_EPSILON = 1e-7
    private const val CANCELLATION_INTERVAL = 2_048
    fun parse(file: File): GcodeNozzlePath = parse(file, DEFAULT_MAX_MOVES, GcodeDialect.CURA)

    fun parse(file: File, dialect: GcodeDialect, progress: (Float) -> Unit = {}): GcodeNozzlePath =
        parse(file, DEFAULT_MAX_MOVES, dialect, progress)

    internal fun parse(file: File, maxMoves: Int): GcodeNozzlePath =
        parse(file, maxMoves, GcodeDialect.CURA)

    internal fun parse(file: File, maxMoves: Int, dialect: GcodeDialect): GcodeNozzlePath =
        parse(file, maxMoves, dialect) {}

    internal fun parse(file: File, maxMoves: Int, dialect: GcodeDialect, progress: (Float) -> Unit): GcodeNozzlePath {

        val totalBytes = file.length().coerceAtLeast(1L)
        val sourceMoveCount = countSpatialMoves(file, dialect) { fraction ->
            progress(fraction)
        }
        require(sourceMoveCount > 0) { "No spatial nozzle moves were found in the G-code" }

        val prusaRegion = PrusaPrintRegion(dialect)

        // The retained move count is known exactly after the count pass, so
        // pre-size both accumulators: giant prints no longer pay the doubling
        // growth copies (each one can be 40-160 MB at the worst moment).
        val retainedCount = if (sourceMoveCount <= maxMoves) sourceMoveCount else maxMoves
        val accumulator = FloatAccumulator(GcodeNozzlePath.VALUES_PER_MOVE * retainedCount)
        val sourceIndices = IntAccumulator(retainedCount)
        val modalState = GcodeModalState()
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var e = 0.0
        var feedRateMmPerMinute = 0.0
        var speedFactor = 1.0
        var sourceIndex = 0
        var extrusionMoves = 0
        var travelMoves = 0
        var retainedPreviousZ = 0.0
        var currentLayerHeight = 0.0
        var hasRetainedZ = false
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var linesRead = 0

        progressReader(file) { bytes ->
            progress((0.5 + bytes.toDouble() / totalBytes * 0.5).toFloat().coerceIn(0f, 1f))
        }.useLines { lines ->
            lines.forEach { rawLine ->
                linesRead++
                checkCancellation(linesRead)
                if (prusaRegion.beforePrint(rawLine)) return@forEach
                val command = GcodeCommand.parse(rawLine) ?: return@forEach
                GcodeCommandPolicy.requirePreviewSafe(command, sourceIndex)
                GcodeCommandPolicy.speedFactor(command)?.let {
                    speedFactor = it
                    return@forEach
                }
                if (modalState.apply(command)) return@forEach
                when (command.opcode) {
                    "G28" -> {
                        x = 0.0
                        y = 0.0
                        z = 0.0
                    }
                    "G92" -> {
                        command.value('X')?.let { x = it }
                        command.value('Y')?.let { y = it }
                        command.value('Z')?.let { z = it }
                        command.value('E')?.let { e = it }
                    }
                    "G0", "G1" -> {
                        val startX = x
                        val startY = y
                        val startZ = z
                        val nextX = modalState.position(x, command.value('X'))
                        val nextY = modalState.position(y, command.value('Y'))
                        val nextZ = modalState.position(z, command.value('Z'))
                        val nextE = modalState.extrusion(e, command.value('E'))
                        command.value('F')?.let { feedRateMmPerMinute = it }
                        x = nextX
                        y = nextY
                        z = nextZ
                        val deltaE = nextE - e
                        e = nextE

                        if (!isSpatialMove(startX, startY, startZ, nextX, nextY, nextZ)) return@forEach
                        val retainedSourceIndex = sourceIndex
                        val keep = shouldRetain(retainedSourceIndex, sourceMoveCount, maxMoves)
                        sourceIndex++
                        val sx = startX.toFloat()
                        val sy = startY.toFloat()
                        val sz = startZ.toFloat()
                        val ex = nextX.toFloat()
                        val ey = nextY.toFloat()
                        val ez = nextZ.toFloat()
                        minX = minOf(minX, sx, ex)
                        minY = minOf(minY, sy, ey)
                        minZ = minOf(minZ, sz, ez)
                        maxX = maxOf(maxX, sx, ex)
                        maxY = maxOf(maxY, sy, ey)
                        maxZ = maxOf(maxZ, sz, ez)
                        if (!keep) return@forEach

                        val kind = if (deltaE > EXTRUSION_EPSILON) {
                            extrusionMoves++
                            GcodeNozzlePath.Kind.EXTRUSION
                        } else {
                            travelMoves++
                            GcodeNozzlePath.Kind.TRAVEL
                        }
                        // Layer height for this move: the z rise of the current
                        // layer (captures adaptive layer heights), guarded against
                        // z-hop travel spikes. 0 means unknown until the first rise.
                        val rise = nextZ - retainedPreviousZ
                        val moveLayerHeight = when {
                            !hasRetainedZ -> currentLayerHeight
                            rise > LAYER_HEIGHT_MIN_MM && rise <= LAYER_HEIGHT_MAX_MM -> {
                                currentLayerHeight = rise
                                rise
                            }
                            else -> currentLayerHeight
                        }
                        retainedPreviousZ = nextZ
                        hasRetainedZ = true
                        accumulator.add(
                            sx, sy, sz, ex, ey, ez,
                            (feedRateMmPerMinute / 60.0 * speedFactor).coerceAtLeast(0.0).toFloat(),
                            kind.code,
                            deltaE.toFloat(),
                            moveLayerHeight.toFloat(),
                        )
                        sourceIndices.add(retainedSourceIndex)
                    }
                }
            }
        }

        require(accumulator.size > 0) { "No nozzle moves remained after preview sampling" }
        progress(1f)
        return GcodeNozzlePath(
            moves = accumulator.toArray(),
            sourceMoveIndices = sourceIndices.toArray(),
            minX = minX,
            minY = minY,
            minZ = minZ,
            maxX = maxX,
            maxY = maxY,
            maxZ = maxZ,
            extrusionMoveCount = extrusionMoves,
            travelMoveCount = travelMoves,
            sourceMoveCount = sourceMoveCount,
            truncated = sourceMoveCount > maxMoves,
        )
    }

    /**
     * Counts the spatial moves the preview would emit, reporting progress over
     * the first half of the bar. Shared with [PrusaNozzlePathParser], which needs
     * the same total before it can sample its own retained set identically.
     */
    internal fun countSpatialMoves(file: File, dialect: GcodeDialect, report: (Float) -> Unit = {}): Int {
        val totalBytes = file.length().coerceAtLeast(1L)
        val prusaRegion = PrusaPrintRegion(dialect)
        val modalState = GcodeModalState()
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var count = 0
        var linesRead = 0
        progressReader(file) { bytes ->
            report((bytes.toDouble() / totalBytes * 0.5).toFloat().coerceIn(0f, 0.5f))
        }.useLines { lines ->
            lines.forEach { rawLine ->
                linesRead++
                checkCancellation(linesRead)
                if (prusaRegion.beforePrint(rawLine)) return@forEach
                val command = GcodeCommand.parse(rawLine) ?: return@forEach
                GcodeCommandPolicy.requirePreviewSafe(command, count)
                GcodeCommandPolicy.speedFactor(command)?.let { return@forEach }
                if (modalState.apply(command)) return@forEach
                when (command.opcode) {
                    "G28" -> {
                        x = 0.0
                        y = 0.0
                        z = 0.0
                    }
                    "G92" -> {
                        command.value('X')?.let { x = it }
                        command.value('Y')?.let { y = it }
                        command.value('Z')?.let { z = it }
                    }
                    "G0", "G1" -> {
                        val nextX = modalState.position(x, command.value('X'))
                        val nextY = modalState.position(y, command.value('Y'))
                        val nextZ = modalState.position(z, command.value('Z'))
                        if (isSpatialMove(x, y, z, nextX, nextY, nextZ)) count++
                        x = nextX
                        y = nextY
                        z = nextZ
                    }
                }
            }
        }
        return count
    }

    private fun isSpatialMove(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): Boolean {
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        return sqrt(dx * dx + dy * dy + dz * dz) > MOTION_EPSILON
    }

    /**
     * Even sample over the whole print: the first and last move are always kept
     * and the remaining budget is spread across the interior, so a capped preview
     * still shows the shape of the part. Shared with [PrusaNozzlePathParser].
     */
    internal fun shouldRetain(index: Int, sourceCount: Int, limit: Int): Boolean {
        if (sourceCount <= limit) return true
        if (index == 0 || index == sourceCount - 1) return true
        val interiorLimit = limit - 2
        val interiorIndex = index - 1
        val interiorCount = sourceCount - 2
        val before = interiorIndex.toLong() * interiorLimit / interiorCount
        val after = (interiorIndex.toLong() + 1L) * interiorLimit / interiorCount
        return after > before
    }

    /**
     * PrusaSlicer gcode wraps the print in ;TYPE:Custom start/end blocks plus a
     * trailing ; prusaslicer_config dump; those moves (prime lines, present-print,
     * Z lifts to 120mm) are not part of the part and pollute the nozzle-path view.
     * The print region is [first layer marker .. first custom block after it].
     *
     * Both spellings of that block belong here - OrcaSlicer's Bambu envelope
     * writes "; FEATURE: Custom" - because the retained preview's own region gate
     * accepts both: a counter that stopped at one spelling would size the sample
     * from a move count the emitter never reaches.
     */
    private class PrusaPrintRegion(private val dialect: GcodeDialect) {
        private var printStarted = false
        private var printDone = false

        /** True when [rawLine] belongs to the start/end gcode and must be ignored. */
        fun beforePrint(rawLine: String): Boolean {
            if (!dialect.isPrusaFamily) return false
            val trimmed = rawLine.trimStart()
            if (!printStarted) {
                if (dialect.opensLayer(trimmed)) printStarted = true
                return true
            }
            if (!printDone &&
                (trimmed.startsWith(";TYPE:Custom") || trimmed.startsWith("; FEATURE: Custom"))
            ) {
                printDone = true
            }
            return printDone
        }
    }

    private fun checkCancellation(linesRead: Int) {
        if (linesRead % CANCELLATION_INTERVAL == 0 && Thread.currentThread().isInterrupted) {
            throw InterruptedException("Nozzle-path parsing was cancelled")
        }
    }

    private class IntAccumulator(initialCapacity: Int = 2048) {
        private var values = IntArray(initialCapacity)
        private var size = 0

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): IntArray = values.copyOf(size)
    }
}
