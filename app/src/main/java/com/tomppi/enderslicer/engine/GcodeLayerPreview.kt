package com.tomppi.enderslicer.engine

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Compact post-slice toolpath data for the interactive layer viewer. */
data class GcodeLayerPreview(
    val layers: List<Layer>,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val minSpeedMmPerSecond: Float,
    val maxSpeedMmPerSecond: Float,
    val minLayerHeightMm: Float,
    val maxLayerHeightMm: Float,
    /** Source paths across the whole print, before any folding. Authoritative for the footers. */
    val totalSegmentCount: Int,
    /**
     * Stored runs across the whole print. Equal to [totalSegmentCount] unless the move budget
     * merged runs, and never smaller than the paths the fold stands for.
     */
    val storedSegmentCount: Int = totalSegmentCount,
    /**
     * True when the move budget merged paths into longer straight runs. Nothing is ever dropped:
     * a folded preview draws every path of a layer, some of them as one straight move.
     */
    val folded: Boolean = false,
) {
    data class Layer(
        val number: Int,
        val z: Float,
        val height: Float,
        /** Packed x1, y1, x2, y2, speed-mm/s, feature-code values. */
        val segments: FloatArray,
        /** Source support/adhesion paths, counted before any folding. */
        val supportSegmentCount: Int,
        val supportInterfaceSegmentCount: Int,
        /**
         * Source paths before the fold. Authoritative for event placement and for every count
         * the footer shows; [segmentCount] is what the geometry holds.
         */
        val sourceSegmentCount: Int = segments.size / VALUES_PER_SEGMENT,
    ) {
        val segmentCount: Int get() = segments.size / VALUES_PER_SEGMENT
        val hasPrintablePaths: Boolean get() = sourceSegmentCount > 0
    }

    enum class Feature(val code: Int) {
        MODEL(0),
        SUPPORT(1),
        SUPPORT_INTERFACE(2),
        ADHESION(3),
        OTHER(4),
        ARC_OVERHANG(5),
        WAVE_OVERHANG(6),
        ;

        companion object {
            fun fromCode(code: Int): Feature = entries.firstOrNull { it.code == code } ?: OTHER
        }
    }

    companion object {
        const val VALUES_PER_SEGMENT = 6
    }
}

/** G-code layer-marking dialect. */
enum class GcodeDialect {
    /** Cura: ;LAYER:N starts each layer. */
    CURA,

    /** PrusaSlicer: ;LAYER_CHANGE (followed by ;Z: and ;HEIGHT:) starts each layer. */
    PRUSA,

    /**
     * OrcaSlicer: "; CHANGE_LAYER" (followed by "; Z_HEIGHT:" and "; LAYER_HEIGHT:") starts
     * each layer.
     *
     * OrcaSlicer is a PrusaSlicer fork, so the estimated-time and filament footer comments
     * are the same text; only the layer marker differs. It comes from the machine profile's
     * own layer_change_gcode rather than from the engine, which is why the Creality profiles
     * write it with a space after the semicolon.
     */
    ORCA,
    ;

    /** PrusaSlicer and its forks mark layers with a comment; Cura numbers them with ;LAYER:N. */
    val isPrusaFamily: Boolean get() = this != CURA

    /**
     * The comments that open a layer, in every spelling this engine writes.
     *
     * OrcaSlicer picks the envelope by vendor (\`GCode.cpp:4619-4622\`): a Bambu Lab printer gets
     * the Bambu spelling, every other vendor the PrusaSlicer one. Both therefore reach the app
     * from this engine, so both are accepted here.
     */
    val layerChangeMarkers: List<String>
        get() = when (this) {
            CURA -> emptyList()
            PRUSA -> listOf(";LAYER_CHANGE")
            ORCA -> listOf("; CHANGE_LAYER", ";LAYER_CHANGE")
        }

    /** The comments carrying a layer's Z height, written just after the layer marker. */
    val layerZMarkers: List<String>
        get() = when (this) {
            CURA -> emptyList()
            PRUSA -> listOf(";Z:")
            ORCA -> listOf("; Z_HEIGHT:", ";Z:")
        }

    /** True when [line] opens a layer in this dialect. */
    fun opensLayer(line: String): Boolean = layerChangeMarkers.any { line.startsWith(it) }

    /** True when [line] carries the Z height of the layer [opensLayer] just opened. */
    fun carriesLayerZ(line: String): Boolean = layerZMarkers.any { line.startsWith(it) }
}

object GcodeLayerPreviewParser {
    private const val MAX_SEGMENTS = 800_000
    private val RARE_FEATURE_PRIORITY = listOf(
        GcodeLayerPreview.Feature.WAVE_OVERHANG,
        GcodeLayerPreview.Feature.ARC_OVERHANG,
        GcodeLayerPreview.Feature.SUPPORT_INTERFACE,
        GcodeLayerPreview.Feature.SUPPORT,
        GcodeLayerPreview.Feature.ADHESION,
    )

    fun parse(file: File): GcodeLayerPreview = parse(file, GcodeDialect.CURA, MAX_SEGMENTS)

    fun parse(file: File, dialect: GcodeDialect): GcodeLayerPreview =
        parse(file, dialect, MAX_SEGMENTS)

    internal fun parse(file: File, maxSegments: Int): GcodeLayerPreview =
        parse(file, GcodeDialect.CURA, maxSegments)

    internal fun parse(file: File, dialect: GcodeDialect, maxSegments: Int): GcodeLayerPreview {
        require(file.isFile && file.length() > 0L) { "Generated G-code is not available for layer preview" }
        require(maxSegments > 0) { "Layer preview segment limit must be positive" }

        val source = scanSource(file, dialect)
        require(source.totalSegmentCount > 0) { "No printable layer paths were found in the G-code" }
        val reservedIndices = reserveRareFeatureSamples(source, maxSegments)
        // The budget spreads over the print, so a dense layer folds more than a sparse one and a
        // capped preview still reaches the last layer. It is a memory guard, never a sampling
        // policy: folding may only make a run longer, it may not drop a path.
        val overBudget = source.totalSegmentCount > maxSegments
        val spanTarget = if (overBudget) max(1.0, source.totalSegmentCount.toDouble() / maxSegments) else 0.0

        val layers = mutableListOf<GcodeLayerPreview.Layer>()
        var currentLayerNumber: Int? = null
        var currentLayerZ = 0f
        var prusaLayerCount = 0
        var currentSegments = FloatAccumulator(6 * 2048)
        var currentSupportCount = 0
        var currentSupportInterfaceCount = 0
        var feature = GcodeLayerPreview.Feature.OTHER

        val modalState = GcodeModalState()
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var e = 0.0
        var feedRateMmPerMinute = 0.0
        var speedFactor = 1.0

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var minSpeed = Float.POSITIVE_INFINITY
        var maxSpeed = Float.NEGATIVE_INFINITY
        var sourceSegmentIndex = 0
        var storedRuns = 0

        // The run being folded: a chain of moves that genuinely continue each other, drawn as
        // one straight move. It closes at the move that continues it once it has stood for its
        // share of the budget, and never across a corner or a feature change.
        var merging = false
        var mergingFeature = GcodeLayerPreview.Feature.OTHER
        var mergingStartX = 0f
        var mergingStartY = 0f
        var mergingEndX = 0f
        var mergingEndY = 0f
        var mergingDirX = 0.0
        var mergingDirY = 0.0
        var mergingSpeed = 0f
        var mergingMoves = 0
        var mergingSourceIndex = -1

        /** Stores the run being folded, which always ends where its last move ended. */
        fun flushRun() {
            if (!merging) return
            currentSegments.add(
                mergingStartX,
                mergingStartY,
                mergingEndX,
                mergingEndY,
                mergingSpeed,
                mergingFeature.code.toFloat(),
            )
            storedRuns++
            merging = false
        }

        fun finishLayer() {
            // A run still open when the layer ends belongs to the layer it was made in.
            flushRun()
            val number = currentLayerNumber ?: return
            layers += GcodeLayerPreview.Layer(
                number = number,
                z = currentLayerZ,
                height = 0f,
                segments = currentSegments.toArray(),
                supportSegmentCount = currentSupportCount,
                supportInterfaceSegmentCount = currentSupportInterfaceCount,
                sourceSegmentCount = source.layerSegmentCounts[number] ?: 0,
            )
            currentSegments = FloatAccumulator(6 * 2048)
            currentSupportCount = 0
            currentSupportInterfaceCount = 0
        }

        file.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trimStart()
                if (dialect.isPrusaFamily) {
                    if (dialect.opensLayer(line)) {
                        finishLayer()
                        prusaLayerCount++
                        currentLayerNumber = prusaLayerCount
                        feature = GcodeLayerPreview.Feature.OTHER
                        return@forEach
                    }
                    if (dialect.carriesLayerZ(line)) {
                        line.substringAfter(':').trim().toFloatOrNull()?.let { currentLayerZ = it }
                        return@forEach
                    }
                } else if (line.startsWith(";LAYER:")) {
                    finishLayer()
                    currentLayerNumber = line.substringAfter(':').trim().toIntOrNull()
                    currentLayerZ = z.toFloat()
                    feature = GcodeLayerPreview.Feature.OTHER
                    return@forEach
                }
                if (line.startsWith(";TYPE:") || line.startsWith("; FEATURE:")) {
                    feature = featureFromType(line.substringAfter(':').trim())
                    return@forEach
                }

                val command = GcodeCommand.parse(rawLine) ?: return@forEach
                GcodeCommandPolicy.speedFactor(command)?.let { factor ->
                    speedFactor = factor
                    return@forEach
                }
                if (modalState.apply(command)) return@forEach
                when (command.opcode) {
                    "G92" -> {
                        command.value('X')?.let { x = it }
                        command.value('Y')?.let { y = it }
                        command.value('Z')?.let { z = it }
                        command.value('E')?.let { e = it }
                    }
                    "G0", "G1" -> {
                        val startX = x
                        val startY = y
                        val nextX = modalState.position(x, command.value('X'))
                        val nextY = modalState.position(y, command.value('Y'))
                        val nextZ = modalState.position(z, command.value('Z'))
                        val nextE = modalState.extrusion(e, command.value('E'))
                        val deltaE = nextE - e
                        command.value('F')?.let { feedRateMmPerMinute = it }

                        x = nextX
                        y = nextY
                        z = nextZ
                        e = nextE

                        if (currentLayerNumber == null || deltaE <= 0.0) return@forEach
                        if (startX == nextX && startY == nextY) return@forEach

                        val speed = max(feedRateMmPerMinute / 60.0 * speedFactor, 0.0).toFloat()
                        if (dialect == GcodeDialect.CURA) currentLayerZ = nextZ.toFloat()
                        minX = minOf(minX, startX.toFloat(), nextX.toFloat())
                        minY = minOf(minY, startY.toFloat(), nextY.toFloat())
                        maxX = maxOf(maxX, startX.toFloat(), nextX.toFloat())
                        maxY = maxOf(maxY, startY.toFloat(), nextY.toFloat())
                        if (speed > 0f) {
                            minSpeed = minOf(minSpeed, speed)
                            maxSpeed = maxOf(maxSpeed, speed)
                        }

                        val currentSourceIndex = sourceSegmentIndex++
                        // The counters name the source paths, never the folded runs: they are what
                        // the footer, the timeline and the event placement report.
                        when (feature) {
                            GcodeLayerPreview.Feature.SUPPORT -> currentSupportCount++
                            GcodeLayerPreview.Feature.SUPPORT_INTERFACE -> currentSupportInterfaceCount++
                            else -> Unit
                        }

                        val moveDx = nextX - startX
                        val moveDy = nextY - startY
                        val moveLength = sqrt(moveDx * moveDx + moveDy * moveDy)
                        val dirX = if (moveLength > 0.0) moveDx / moveLength else 0.0
                        val dirY = if (moveLength > 0.0) moveDy / moveLength else 0.0
                        // A move that does not genuinely continue the run may not be folded into
                        // it: folding across a corner cuts the corner, and a run that changes
                        // feature would be drawn in the wrong colour.
                        val continues = merging &&
                            feature == mergingFeature &&
                            moveLength > 0.0 &&
                            dirX * mergingDirX + dirY * mergingDirY >= GcodeNozzlePathParser.FOLD_COLLINEAR_DOT &&
                            abs((nextX - mergingStartX) * -mergingDirY + (nextY - mergingStartY) * mergingDirX) <=
                            GcodeNozzlePathParser.FOLD_DRIFT_MM
                        val requested = currentSourceIndex in reservedIndices ||
                            shouldRetainSegment(currentSourceIndex, source.totalSegmentCount, maxSegments)
                        // The budget is the memory guard the old sampler used to drop paths with.
                        // It may only make a run longer: a run closes at the move that genuinely
                        // continues it once it has stood for its share of the paths.
                        val closeHere = !overBudget || (requested && mergingMoves + 1 >= spanTarget)

                        if (continues && !closeHere) {
                            // Fold this move into the run: it continues it, so the one straight
                            // move drawn for the run still covers it.
                            mergingMoves++
                            mergingSourceIndex = currentSourceIndex
                            mergingSpeed = speed
                            mergingEndX = nextX.toFloat()
                            mergingEndY = nextY.toFloat()
                            return@forEach
                        }
                        if (merging && !continues) {
                            // The run ends at the move before this one, so the turn the printer
                            // made is drawn rather than a chord across it.
                            flushRun()
                        }
                        if (closeHere) {
                            currentSegments.add(
                                if (merging) mergingStartX else startX.toFloat(),
                                if (merging) mergingStartY else startY.toFloat(),
                                nextX.toFloat(),
                                nextY.toFloat(),
                                speed,
                                feature.code.toFloat(),
                            )
                            storedRuns++
                            merging = false
                        } else {
                            // This move starts the next run.
                            merging = true
                            mergingFeature = feature
                            mergingStartX = startX.toFloat()
                            mergingStartY = startY.toFloat()
                            mergingEndX = nextX.toFloat()
                            mergingEndY = nextY.toFloat()
                            mergingDirX = dirX
                            mergingDirY = dirY
                            mergingSpeed = speed
                            mergingMoves = 0
                            mergingSourceIndex = currentSourceIndex
                        }
                    }
                }
            }
        }
        finishLayer()

        require(layers.isNotEmpty() && storedRuns > 0) { "No printable layer paths were found in the G-code" }
        val layersWithHeights = layers.mapIndexed { index, layer ->
            val previousZ = if (index == 0) 0f else layers[index - 1].z
            layer.copy(height = (layer.z - previousZ).coerceAtLeast(0f))
        }
        val positiveLayerHeights = layersWithHeights.map(GcodeLayerPreview.Layer::height).filter { it > 0f }
        val minimumLayerHeight = positiveLayerHeights.minOrNull() ?: 0f
        val maximumLayerHeight = positiveLayerHeights.maxOrNull() ?: minimumLayerHeight
        if (!minSpeed.isFinite()) minSpeed = 0f
        if (!maxSpeed.isFinite()) maxSpeed = minSpeed
        if (maxSpeed < minSpeed) maxSpeed = minSpeed

        return GcodeLayerPreview(
            layers = layersWithHeights,
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY,
            minSpeedMmPerSecond = minSpeed,
            maxSpeedMmPerSecond = maxSpeed,
            minLayerHeightMm = minimumLayerHeight,
            maxLayerHeightMm = maximumLayerHeight,
            totalSegmentCount = source.totalSegmentCount,
            storedSegmentCount = storedRuns,
            folded = storedRuns < source.totalSegmentCount,
        )
    }

    private fun scanSource(file: File, dialect: GcodeDialect): SourceScan {
        var currentLayerNumber: Int? = null
        var feature = GcodeLayerPreview.Feature.OTHER
        var prusaLayerCount = 0
        val modalState = GcodeModalState()
        var x = 0.0
        var y = 0.0
        var e = 0.0
        var count = 0
        val layerCounts = linkedMapOf<Int, Int>()
        val firstFeatureIndices = IntArray(GcodeLayerPreview.Feature.entries.size) { -1 }

        file.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trimStart()
                if (dialect.isPrusaFamily) {
                    if (dialect.opensLayer(line)) {
                        prusaLayerCount++
                        currentLayerNumber = prusaLayerCount
                        feature = GcodeLayerPreview.Feature.OTHER
                        return@forEach
                    }
                    if (dialect.carriesLayerZ(line)) {
                        return@forEach
                    }
                } else if (line.startsWith(";LAYER:")) {
                    currentLayerNumber = line.substringAfter(':').trim().toIntOrNull()
                    feature = GcodeLayerPreview.Feature.OTHER
                    return@forEach
                }
                if (line.startsWith(";TYPE:") || line.startsWith("; FEATURE:")) {
                    feature = featureFromType(line.substringAfter(':').trim())
                    return@forEach
                }

                val command = GcodeCommand.parse(rawLine) ?: return@forEach
                if (modalState.apply(command)) return@forEach
                when (command.opcode) {
                    "G92" -> {
                        command.value('X')?.let { x = it }
                        command.value('Y')?.let { y = it }
                        command.value('E')?.let { e = it }
                    }
                    "G0", "G1" -> {
                        val startX = x
                        val startY = y
                        val nextX = modalState.position(x, command.value('X'))
                        val nextY = modalState.position(y, command.value('Y'))
                        val nextE = modalState.extrusion(e, command.value('E'))
                        val deltaE = nextE - e

                        x = nextX
                        y = nextY
                        e = nextE

                        val layerNumber = currentLayerNumber
                        if (layerNumber != null && deltaE > 0.0 && (startX != nextX || startY != nextY)) {
                            if (firstFeatureIndices[feature.ordinal] < 0) {
                                firstFeatureIndices[feature.ordinal] = count
                            }
                            layerCounts[layerNumber] = (layerCounts[layerNumber] ?: 0) + 1
                            count++
                        }
                    }
                }
            }
        }
        return SourceScan(count, layerCounts, firstFeatureIndices)
    }

    private fun reserveRareFeatureSamples(source: SourceScan, maxSegments: Int): Set<Int> {
        val selected = linkedSetOf<Int>()
        for (feature in RARE_FEATURE_PRIORITY) {
            if (selected.size >= maxSegments) break
            val sourceIndex = source.firstFeatureIndices[feature.ordinal]
            if (sourceIndex >= 0) selected += sourceIndex
        }
        return selected
    }

    private fun shouldRetainSegment(
        sourceIndex: Int,
        sourceCount: Int,
        retainedLimit: Int,
    ): Boolean {
        if (retainedLimit <= 0 || sourceCount <= 0) return false
        if (sourceCount <= retainedLimit) return true
        val before = sourceIndex.toLong() * retainedLimit / sourceCount
        val after = (sourceIndex.toLong() + 1L) * retainedLimit / sourceCount
        return after > before
    }

    private fun featureFromType(raw: String): GcodeLayerPreview.Feature {
        val value = raw.uppercase()
        return when {
            value.contains("ARC-OVERHANG") || value.contains("ARC_OVERHANG") -> GcodeLayerPreview.Feature.ARC_OVERHANG
            value.contains("WAVE-OVERHANG") || value.contains("WAVE_OVERHANG") -> GcodeLayerPreview.Feature.WAVE_OVERHANG
            value.contains("SUPPORT") && value.contains("INTERFACE") -> GcodeLayerPreview.Feature.SUPPORT_INTERFACE
            value.contains("SUPPORT-INTERFACE") || value.contains("SUPPORT_INTERFACE") -> GcodeLayerPreview.Feature.SUPPORT_INTERFACE
            value.contains("SUPPORT") -> GcodeLayerPreview.Feature.SUPPORT
            value.contains("SKIRT") || value.contains("BRIM") || value.contains("RAFT") -> GcodeLayerPreview.Feature.ADHESION
            value.contains("WALL") || value.contains("SKIN") || value.contains("FILL") ||
                value.contains("INFILL") || value.contains("BRIDGE") ||
                value.contains("PERIMETER") || value.contains("SURFACE") -> GcodeLayerPreview.Feature.MODEL
            else -> GcodeLayerPreview.Feature.OTHER
        }
    }

    private data class SourceScan(
        val totalSegmentCount: Int,
        val layerSegmentCounts: Map<Int, Int>,
        val firstFeatureIndices: IntArray,
    )
}
