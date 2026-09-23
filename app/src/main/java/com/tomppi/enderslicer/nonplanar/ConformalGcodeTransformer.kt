package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.GcodeCommandPolicy
import com.tomppi.enderslicer.engine.GcodeModalState
import com.tomppi.enderslicer.engine.MAX_EMITTED_MOVES
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.engine.checkCancellation
import com.tomppi.enderslicer.engine.formatGcode
import com.tomppi.enderslicer.engine.publishAtomic
import com.tomppi.enderslicer.engine.quantizeGcode
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Projects the planar slice onto the conformal surface regions (Ahlers'
 * method at the G-code level):
 *
 * 1. Every extrusion move whose planar height lies within [shellLayers]
 *    layer heights of the surface is removed from its planar layer (the
 *    stair step) and reassigned to the conformal shell
 *    k = floor((surfaceZ - planarZ) / layerHeight). Shell k rides
 *    surfaceZ - k * layerHeight, so the nozzle follows the true surface
 *    from the thinnest part (diving below the layer plane) to the thickest.
 * 2. Shell moves are printed at the end of the shell's "home layer" - the
 *    topmost planar layer at or below surfaceMax - k * layerHeight - with
 *    retraction, rise, travel and descent around each disconnected piece.
 * 3. Deeper planar material (k >= shellLayers) stays planar as interior
 *    support, and the first layer is always kept planar for bed adhesion.
 *
 * Extrusion: the reference scales the 3D path length by the cosine of the
 * surface angle (thesis eq. 4.13's m), i.e. it extrudes for the HORIZONTAL
 * length of each conformal move so the z-shifted shells keep the same
 * E/mm as their planar source lines. Keeping the planar source move's E
 * value provides exactly that horizontal-length extrusion, so no further
 * compensation is needed. (The literal flow*L3D*cos(arctan(dz/L3D)) form
 * would NOT simplify to the horizontal length unless the arctan's
 * denominator is the horizontal length itself - the intended reading.)
 */
internal object ConformalGcodeTransformer {
    private const val EPSILON = 1e-8
    private const val MAX_SPLIT_DEPTH = 24
    private const val MIN_SEGMENT_MM = 0.05
    // Adjacent skin lines sit up to a couple of millimetres apart: bridging
    // them directly (like ordinary zigzag top-skin infill) keeps the shell a
    // continuous pass and avoids a retract/rise/travel/descend per line.
    private const val CONNECT_TOLERANCE_MM = 2.5
    // A direct (travel-free) connection between shell runs is only emitted
    // when the jump stays shallow enough to be a plausible surface move.
    private const val CONNECT_SLOPE_LIMIT = 0.5
    // Tiny joins (overlapping-facet flips in the mesh) may carry a small z
    // step: join them anyway instead of inserting an in-place rise/drop dance.
    private const val JOIN_MAX_RISE_MM = 0.3
    private const val TRAVEL_CLEARANCE_MM = 0.3
    private const val NEIGHBOR_CELL_MM = 3.0
    private const val CONFORMAL_BAND_SAMPLES = 8
    private const val CHORD_TOLERANCE_MM = 0.1
    private const val SLIVER_MAX_RISE_MM = 0.3
    // Shells stay this far from the exact region boundary so the nozzle cone
    // cannot scrape a steep wall rising at the region edge. For the measured
    // 75-degree nozzle taper (15 degrees from vertical), 0.75 mm clears walls
    // up to ~2.8 mm above the shell path (0.75 / tan(15 degrees)); with the
    // default 60-degree taper it clears ~1.3 mm.
    private const val BOUNDARY_BACKOFF_MM = 0.75

    data class Diagnostics(
        val regionCount: Int,
        val sourceExtrusionMoves: Int,
        val stairMovesRemoved: Int,
        val skinMovesEmitted: Int,
        val emittedMoves: Int,
        val minimumZmm: Double,
        val maximumZmm: Double,
        val maximumDiveMm: Double,
        val maximumObservedSlopeDegrees: Double,
        val maximumObservedZSpeedMmPerSecond: Double,
    )

    private class LayerState(
        val number: Int,
        var baseZ: Double,
        var lastX: Double,
        var lastY: Double,
        var lastZ: Double,
        val skins: ArrayList<Piece> = ArrayList(),
    )

    private class Piece(
        val regionIndex: Int,
        val shell: Int,
        // The planar source move this piece was split from; pieces of one move
        // form a contiguous run along the original toolpath.
        val moveId: Int,
        val x1: Double,
        val y1: Double,
        val z1: Double,
        val x2: Double,
        val y2: Double,
        val z2: Double,
        val deltaE: Double,
        val feed: Double,
        val order: Int,
    )

    private class Classification(
        val regionIndex: Int,
        val shell: Int,
        val surfaceZ: Double,
    )

    private class Segment(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
        val deltaE: Double,
        val depth: Int,
    )

    fun transform(
        file: File,
        surface: ConformalSurface,
        layerHeightMm: Double,
        maximumZSpeedMmPerSecond: Double,
        conformalShellLayers: Int,
        printerEnvelope: PrinterEnvelope,
    ): Diagnostics {
        require(file.isFile && file.length() > 0L) { "Conformal G-code is missing" }
        require(surface.regions.isNotEmpty()) { "Conformal surface regions are missing" }
        require(layerHeightMm.isFinite() && layerHeightMm in 0.04..1.2) { "Invalid conformal layer height" }
        val shellLayers = conformalShellLayers.coerceIn(1, 8)
        val temporary = File(file.parentFile, file.name + ".conformal.tmp")
        temporary.delete()

        // ---- walk 1: parse, classify and collect shell pieces ----
        val modal = GcodeModalState()
        var planarX = 0.0
        var planarY = 0.0
        var planarZ = 0.0
        var planarE = 0.0
        var logicalFeed = 0.0
        var inPrintableLayers = false
        var afterMachineEnd = false
        var currentLayer: Int? = null
        var firstLayerNumber: Int? = null
        var lineNumber = 0
        var sourceMoves = 0
        var sourceMoveId = 0
        var pieceOrder = 0
        var fileUsesFirmwareRetract = false
        // The source stream's own E-retraction pattern (negative E deltas),
        // mirrored around our travel hops when the dialect does not use
        // firmware retraction - hopping unretracted causes oozing strings.
        var sourceRetractDelta = 0.0
        var sourceRetractFeed = 0.0
        // How much of each source move's E moved to the shells; walk 2
        // subtracts it so straddling moves never double-print.
        val collectedDeltaEByMove = HashMap<Int, Double>()
        // The collected sub-spans themselves (x1,y1,x2,y2 per piece): walk 2
        // emits only the complement of a straddling move so the nozzle never
        // re-traverses the collected interior at the planar layer.
        val collectedSpansByMove = HashMap<Int, MutableList<DoubleArray>>()
        val layerStates = LinkedHashMap<Int, LayerState>()

        fun layerState(number: Int, z: Double): LayerState = layerStates.getOrPut(number) {
            LayerState(number, z, planarX, planarY, z)
        }

        fun surfaceAt(x: Double, y: Double): Pair<Int, Double>? {
            var best: Pair<Int, Double>? = null
            for (index in surface.regions.indices) {
                val z = surface.regions[index].surfaceZ(x, y) ?: continue
                if (best == null || z > best.second) best = index to z
            }
            return best
        }

        fun classify(x: Double, y: Double, planarHeight: Double): Classification? {
            val hit = surfaceAt(x, y) ?: return null
            val layer = currentLayer ?: return null
            if (firstLayerNumber != null && layer == firstLayerNumber) return null
            // Back off from every region's exact boundary, not just the
            // matched one: adjacent regions face each other across narrow
            // steep strips, and a shell on one side can hug the other side's
            // cliff face. The rim stays planar in those strips.
            for (region in surface.regions) {
                if (x < region.minX - BOUNDARY_BACKOFF_MM || x > region.maxX + BOUNDARY_BACKOFF_MM ||
                    y < region.minY - BOUNDARY_BACKOFF_MM || y > region.maxY + BOUNDARY_BACKOFF_MM
                ) {
                    continue
                }
                if (region.distanceToBoundary(x, y) < BOUNDARY_BACKOFF_MM) return null
            }
            val band = floor((hit.second - planarHeight) / layerHeightMm + 1e-9).toInt()
            if (band < 0 || band >= shellLayers) return null
            return Classification(hit.first, band, hit.second)
        }

        fun collectPieces(
            startX: Double,
            startY: Double,
            endX: Double,
            endY: Double,
            planarHeight: Double,
            deltaE: Double,
            feed: Double,
            state: LayerState,
            moveId: Int,
        ) {
            // Depth-first (LIFO) processing keeps the emitted pieces in
            // spatial order: every split re-queues its two children at the
            // head so the left half - and all its own sub-splits - finishes
            // before the right half starts.
            val queue = ArrayDeque<Segment>()
            queue.addFirst(Segment(startX, startY, endX, endY, deltaE, 0))
            while (queue.isNotEmpty()) {
                val segment = queue.removeFirst()
                val c1 = classify(segment.x1, segment.y1, planarHeight)
                val c2 = classify(segment.x2, segment.y2, planarHeight)
                val same = c1?.regionIndex == c2?.regionIndex && c1?.shell == c2?.shell
                if (same) {
                    val classification = c1 ?: c2
                    if (classification != null) {
                        val z1 = (surfaceAt(segment.x1, segment.y1)?.second ?: classification.surfaceZ) -
                            classification.shell * layerHeightMm
                        val z2 = (surfaceAt(segment.x2, segment.y2)?.second ?: classification.surfaceZ) -
                            classification.shell * layerHeightMm
                        // The reference implementation splits every extrusion
                        // line at every facet edge so the path follows the
                        // surface geometry exactly. Numerically that means the
                        // straight chord must stay on the surface: sample it
                        // and split wherever the band changes or the surface
                        // deviates from the chord - otherwise a convex bump
                        // (like a dome) would be cut straight through.
                        var splitFraction = -1.0
                        var worstDeviation = 0.0
                        var worstDeviationFraction = -1.0
                        val degenerate = hypot(segment.x2 - segment.x1, segment.y2 - segment.y1) < 1e-6
                        if (!degenerate) {
                            for (sampleIndex in 1 until CONFORMAL_BAND_SAMPLES) {
                                val t = sampleIndex.toDouble() / CONFORMAL_BAND_SAMPLES
                                val px = segment.x1 + (segment.x2 - segment.x1) * t
                                val py = segment.y1 + (segment.y2 - segment.y1) * t
                                val sample = classify(px, py, planarHeight)
                                if (sample?.regionIndex != classification.regionIndex ||
                                    sample?.shell != classification.shell
                                ) {
                                    splitFraction = t
                                    break
                                }
                                val surfaceZ = surfaceAt(px, py)?.second ?: continue
                                // The chord rides surface - shell*layerHeight;
                                // deviation is how far the actual surface depth
                                // leaves that chord.
                                val chordZ = z1 + (z2 - z1) * t
                                val deviation = abs((surfaceZ - classification.shell * layerHeightMm) - chordZ)
                                if (deviation > worstDeviation) {
                                    worstDeviation = deviation
                                    worstDeviationFraction = t
                                }
                            }
                        }
                        if (splitFraction < 0.0 && worstDeviation > CHORD_TOLERANCE_MM) {
                            splitFraction = worstDeviationFraction
                        }
                        if (splitFraction > 0.0) {
                            val midX = segment.x1 + (segment.x2 - segment.x1) * splitFraction
                            val midY = segment.y1 + (segment.y2 - segment.y1) * splitFraction
                            queue.addFirst(
                                Segment(
                                    midX, midY, segment.x2, segment.y2,
                                    segment.deltaE * (1.0 - splitFraction), segment.depth + 1,
                                ),
                            )
                            queue.addFirst(
                                Segment(
                                    segment.x1, segment.y1, midX, midY,
                                    segment.deltaE * splitFraction, segment.depth + 1,
                                ),
                            )
                            continue
                        }
                        state.skins += Piece(
                            regionIndex = classification.regionIndex,
                            shell = classification.shell,
                            moveId = moveId,
                            x1 = segment.x1, y1 = segment.y1, z1 = z1,
                            x2 = segment.x2, y2 = segment.y2, z2 = z2,
                            deltaE = segment.deltaE,
                            feed = feed,
                            order = pieceOrder++,
                        )
                        collectedDeltaEByMove.merge(moveId, segment.deltaE) { a, b -> a + b }
                        collectedSpansByMove.getOrPut(moveId) { ArrayList(4) }
                            .add(doubleArrayOf(segment.x1, segment.y1, segment.x2, segment.y2))
                    } else {
                        // Both endpoints outside the region: a long move may
                        // still cross it in the middle (an infill line through
                        // the dome footprint). Bisect so the inside segments
                        // are collected instead of silently dropped.
                        val length = hypot(segment.x2 - segment.x1, segment.y2 - segment.y1)
                        if (length >= MIN_SEGMENT_MM && segment.depth < MAX_SPLIT_DEPTH) {
                            val midX = (segment.x1 + segment.x2) * 0.5
                            val midY = (segment.y1 + segment.y2) * 0.5
                            queue.addFirst(Segment(midX, midY, segment.x2, segment.y2, segment.deltaE * 0.5, segment.depth + 1))
                            queue.addFirst(Segment(segment.x1, segment.y1, midX, midY, segment.deltaE * 0.5, segment.depth + 1))
                        }
                    }
                    continue
                }
                // A boundary point classifies as inside (on-edge), so pure
                // bisection would never settle: once the piece is shorter than
                // the machine's resolution, classify its midpoint and stop.
                val length = hypot(segment.x2 - segment.x1, segment.y2 - segment.y1)
                if (segment.depth >= MAX_SPLIT_DEPTH || length < MIN_SEGMENT_MM) {
                    val midX = (segment.x1 + segment.x2) * 0.5
                    val midY = (segment.y1 + segment.y2) * 0.5
                    val classification = classify(midX, midY, planarHeight) ?: continue
                    // The sliver is shorter than the machine's resolution:
                    // ride the surface at both endpoints (falling back to the
                    // midpoint height only where an endpoint lies outside the
                    // region) so the neighbouring pieces join smoothly. Where
                    // the two endpoints land on different shells or surfaces
                    // (an overlapping-facet flip), drop the sliver - a near
                    // vertical jump would be worse than its lost 0.05 mm.
                    val endpointA = classify(segment.x1, segment.y1, planarHeight)
                    val endpointB = classify(segment.x2, segment.y2, planarHeight)
                    if (endpointA?.shell != endpointB?.shell ||
                        endpointA?.regionIndex != endpointB?.regionIndex
                    ) {
                        continue
                    }
                    val z1 = (surfaceAt(segment.x1, segment.y1)?.second ?: classification.surfaceZ) -
                        classification.shell * layerHeightMm
                    val z2 = (surfaceAt(segment.x2, segment.y2)?.second ?: classification.surfaceZ) -
                        classification.shell * layerHeightMm
                    if (abs(z2 - z1) > SLIVER_MAX_RISE_MM) continue
                    state.skins += Piece(
                        regionIndex = classification.regionIndex,
                        shell = classification.shell,
                        moveId = moveId,
                        x1 = segment.x1, y1 = segment.y1, z1 = z1,
                        x2 = segment.x2, y2 = segment.y2, z2 = z2,
                        deltaE = segment.deltaE,
                        feed = feed,
                        order = pieceOrder++,
                    )
                    collectedDeltaEByMove.merge(moveId, segment.deltaE) { a, b -> a + b }
                    collectedSpansByMove.getOrPut(moveId) { ArrayList(4) }
                        .add(doubleArrayOf(segment.x1, segment.y1, segment.x2, segment.y2))
                    continue
                }
                val midX = (segment.x1 + segment.x2) * 0.5
                val midY = (segment.y1 + segment.y2) * 0.5
                queue.addFirst(Segment(midX, midY, segment.x2, segment.y2, segment.deltaE * 0.5, segment.depth + 1))
                queue.addFirst(Segment(segment.x1, segment.y1, midX, midY, segment.deltaE * 0.5, segment.depth + 1))
            }
        }

        try {
            file.bufferedReader().useLines { lines ->
                for (rawLine in lines) {
                    lineNumber++
                    checkCancellation(lineNumber, "Conformal processing")
                    val trimmed = rawLine.trimStart()
                    if (afterMachineEnd) continue
                    if (trimmed == NonPlanarRuntime.MACHINE_END_SENTINEL) {
                        afterMachineEnd = true
                        inPrintableLayers = false
                        continue
                    }
                    if (trimmed.startsWith("G10") || trimmed.startsWith("G11")) {
                        fileUsesFirmwareRetract = true
                        continue
                    }
                    if (trimmed.startsWith(";LAYER:")) {
                        val number = trimmed.substringAfter(':').trim().toIntOrNull()
                        if (number != null) {
                            currentLayer = number
                            if (firstLayerNumber == null) firstLayerNumber = number
                            inPrintableLayers = true
                        }
                        continue
                    }
                    if (trimmed.startsWith(";End of Gcode", ignoreCase = true) || trimmed.startsWith(";END_OF_PRINT")) {
                        inPrintableLayers = false
                        continue
                    }
                    val command = GcodeCommand.parse(rawLine) ?: continue
                    if (modal.apply(command)) continue
                    when (command.opcode) {
                        "G92" -> {
                            command.value('X')?.let { planarX = it }
                            command.value('Y')?.let { planarY = it }
                            command.value('Z')?.let { planarZ = it }
                            command.value('E')?.let { planarE = it }
                        }
                        "G0", "G1" -> {
                            val nextX = modal.position(planarX, command.value('X'))
                            val nextY = modal.position(planarY, command.value('Y'))
                            val nextZ = modal.position(planarZ, command.value('Z'))
                            val nextE = modal.extrusion(planarE, command.value('E'))
                            command.value('F')?.let { logicalFeed = it }
                            val deltaE = nextE - planarE
                            if (command.has('E') && deltaE < -EPSILON) {
                                sourceRetractDelta = max(sourceRetractDelta, abs(deltaE))
                                if (command.has('F')) sourceRetractFeed = logicalFeed
                            }
                            val spatial = abs(nextX - planarX) > EPSILON ||
                                abs(nextY - planarY) > EPSILON || abs(nextZ - planarZ) > EPSILON
                            if (spatial && inPrintableLayers && currentLayer != null) {
                                val state = layerState(currentLayer!!, nextZ)
                                // The layer plane is established by its Z-changing
                                // move (the layer hop), not by the first spatial
                                // move, which can be a same-plane XY travel.
                                if (abs(nextZ - planarZ) > EPSILON) state.baseZ = nextZ
                                state.lastX = nextX
                                state.lastY = nextY
                                state.lastZ = nextZ
                            }
                            if (!spatial || !inPrintableLayers || !command.has('E') || abs(deltaE) <= EPSILON) {
                                planarX = nextX
                                planarY = nextY
                                planarZ = nextZ
                                planarE = nextE
                                continue
                            }
                            sourceMoves++
                            sourceMoveId++
                            val state = layerStates[currentLayer] ?: layerState(currentLayer!!, nextZ)
                            collectPieces(
                                startX = planarX,
                                startY = planarY,
                                endX = nextX,
                                endY = nextY,
                                // The extrusion lands at the move's own height:
                                // the layer-transition move carries the new layer Z.
                                planarHeight = nextZ,
                                deltaE = deltaE,
                                feed = logicalFeed,
                                state = state,
                                moveId = sourceMoveId,
                            )
                            planarX = nextX
                            planarY = nextY
                            planarZ = nextZ
                            planarE = nextE
                        }
                        else -> Unit
                    }
                }
            }
        } catch (closed: java.nio.channels.ClosedByInterruptException) {
            throw InterruptedException("Conformal processing was cancelled")
        }

        val totalSkins = layerStates.values.sumOf { it.skins.size }
        require(totalSkins > 0) {
            "Conformal surface mode found no toolpath on the printable surface regions; " +
                "raise the maximum path slope or the maximum lift so a surface region matches the model"
        }

        // Assign each shell to its home layer: the topmost layer at or below
        // surfaceMax - shell * layerHeight, then move every piece there.
        // -1 means "no valid home": a surface too shallow for this shell.
        // Such pieces are dropped instead of flushing on the first layer,
        // which would violate the planar-first-layer invariant.
        val homeByRegionAndShell = Array(surface.regions.size) { regionIndex ->
            val maxZ = surface.regions[regionIndex].maxZ
            IntArray(shellLayers) { shell ->
                val target = maxZ - shell * layerHeightMm
                val candidates = layerStates.values.filter { it.baseZ <= target + EPSILON }
                candidates.maxByOrNull { it.baseZ }?.number ?: -1
            }
        }
        for (state in layerStates.values) {
            val pieces = state.skins.toList()
            state.skins.clear()
            for (piece in pieces) {
                val homeNumber = homeByRegionAndShell[piece.regionIndex][piece.shell]
                if (homeNumber < 0) continue // no valid home layer: drop the piece
                layerStates[homeNumber]?.skins?.add(piece) ?: state.skins.add(piece)
            }
        }

        // ---- walk 2: emit the rebuilt stream ----
        var emittedX = 0.0
        var emittedY = 0.0
        var emittedZ = 0.0
        // The source stream's own coordinates (advancing on every original
        // spatial/E move, removed or not) drive per-move classification and
        // deltas; the emitted position may lag behind when stair moves are
        // skipped without a travel line.
        var sourceX = 0.0
        var sourceY = 0.0
        var sourceZ = 0.0
        var sourceE = 0.0
        var runningE = 0.0
        // Mirrors walk 1's sourceMoveId so each emitted move can subtract the
        // E that its collected shell pieces already carry.
        var emissionMoveId = 0
        var emittedFeed = Double.NaN
        var emittedMoves = 0
        // Set when a skipped stair moved in XY: the emitted position lags the
        // source, and the gap is bridged lazily before the next kept move
        // instead of emitting one G0 per skipped move (which flooded the
        // output with thousands of travel hops).
        var skippedGap = false
        var stairMovesRemoved = 0
        var skinMovesEmitted = 0
        var minimumZ = Double.POSITIVE_INFINITY
        var maximumZ = Double.NEGATIVE_INFINITY
        var maximumSlope = 0.0
        var maximumZSpeed = 0.0
        var maximumDive = 0.0
        var metadataWritten = false
        val emissionModal = GcodeModalState()
        var emissionLayer: Int? = null
        var emissionAfterMachineEnd = false
        var emissionInPrintable = false

        fun writeMetadata(output: Appendable) {
            if (metadataWritten) return
            metadataWritten = true
            output.appendLine(";ENDERSLICER_NON_PLANAR:ConformalSurface-Android-v1")
            output.appendLine(";ENDERSLICER_CONFORMAL_REGIONS:" + surface.regions.size)
            output.appendLine(";ENDERSLICER_CONFORMAL_SHELLS:" + shellLayers)
        }

        fun emitCommand(output: Appendable, line: String) {
            GcodeCommand.parse(line)?.let { emissionModal.apply(it) }
            output.appendLine(line)
        }

        fun emitMove(
            output: Appendable,
            targetX: Double,
            targetY: Double,
            targetZ: Double,
            deltaE: Double?,
            feed: Double?,
            opcode: String = "G1",
        ) {
            val dx = targetX - emittedX
            val dy = targetY - emittedY
            val dz = targetZ - emittedZ
            if (deltaE == null && abs(dx) < 1e-6 && abs(dy) < 1e-6 && abs(dz) < 1e-6) return
            check(emittedMoves + 1 <= MAX_EMITTED_MOVES) { "Conformal output exceeded " + MAX_EMITTED_MOVES + " moves" }
            val builder = StringBuilder(opcode)
            if (emissionModal.absolutePosition) {
                builder.append(" X").append(format(quantize(targetX)))
                builder.append(" Y").append(format(quantize(targetY)))
                builder.append(" Z").append(format(quantize(targetZ)))
            } else {
                builder.append(" X").append(format(quantize(dx)))
                builder.append(" Y").append(format(quantize(dy)))
                builder.append(" Z").append(format(quantize(dz)))
            }
            if (deltaE != null) {
                runningE += deltaE
                builder.append(" E").append(
                    format(quantize(if (emissionModal.absoluteExtrusion) runningE else deltaE)),
                )
            }
            if (feed != null && feed > 0.0 && (!emittedFeed.isFinite() || abs(feed - emittedFeed) > 0.01)) {
                builder.append(" F").append(format(feed))
                emittedFeed = feed
            }
            emitCommand(output, builder.toString())
            emittedX = targetX
            emittedY = targetY
            emittedZ = targetZ
            emittedMoves++
            minimumZ = minOf(minimumZ, targetZ)
            maximumZ = maxOf(maximumZ, targetZ)
        }

        fun emitComplement(
            output: Appendable,
            ax: Double,
            ay: Double,
            bx: Double,
            by: Double,
            z: Double,
            remainingE: Double,
            spans: List<DoubleArray>,
            feed: Double?,
            opcode: String,
        ) {
            val dirX = bx - ax
            val dirY = by - ay
            val totalLength = hypot(dirX, dirY)
            if (totalLength <= EPSILON) {
                emitMove(output, bx, by, z, remainingE, feed, opcode)
                return
            }
            val intervals = ArrayList<Pair<Double, Double>>(spans.size)
            for (span in spans) {
                var t1 = ((span[0] - ax) * dirX + (span[1] - ay) * dirY) / (totalLength * totalLength)
                var t2 = ((span[2] - ax) * dirX + (span[3] - ay) * dirY) / (totalLength * totalLength)
                if (t1 > t2) {
                    val swap = t1
                    t1 = t2
                    t2 = swap
                }
                t1 = t1.coerceIn(0.0, 1.0)
                t2 = t2.coerceIn(0.0, 1.0)
                if (t2 - t1 > EPSILON) intervals += t1 to t2
            }
            intervals.sortBy { it.first }
            val merged = ArrayList<Pair<Double, Double>>(intervals.size)
            for (interval in intervals) {
                if (merged.isEmpty() || interval.first > merged.last().second + EPSILON) {
                    merged += interval
                } else {
                    val last = merged.removeAt(merged.size - 1)
                    merged += last.first to maxOf(last.second, interval.second)
                }
            }
            val complement = ArrayList<Pair<Double, Double>>(merged.size + 1)
            var cursor = 0.0
            for (interval in merged) {
                if (interval.first - cursor > EPSILON) complement += cursor to interval.first
                cursor = maxOf(cursor, interval.second)
            }
            if (1.0 - cursor > EPSILON) complement += cursor to 1.0
            var remainingLength = 0.0
            for (interval in complement) remainingLength += (interval.second - interval.first) * totalLength
            for (interval in complement) {
                // Travel to the interval start (a no-op when the nozzle is
                // already there): the collected interior is crossed without
                // extruding because its plastic rides the conformal shell.
                emitMove(
                    output,
                    ax + dirX * interval.first,
                    ay + dirY * interval.first,
                    z,
                    null,
                    feed,
                    "G0",
                )
                val length = (interval.second - interval.first) * totalLength
                val eShare = if (remainingLength > EPSILON) remainingE * length / remainingLength else 0.0
                emitMove(
                    output,
                    ax + dirX * interval.second,
                    ay + dirY * interval.second,
                    z,
                    if (eShare > EPSILON) eShare else null,
                    feed,
                    opcode,
                )
            }
        }

        fun emitSkinPiece(output: Appendable, piece: Piece, state: LayerState) {
            val horizontal = hypot(piece.x2 - piece.x1, piece.y2 - piece.y1)
            val dz = abs(piece.z2 - piece.z1)
            val slope = if (horizontal > EPSILON) dz / horizontal else 0.0
            val slopeDegrees = Math.toDegrees(kotlin.math.atan(slope))
            if (slopeDegrees > maximumSlope) maximumSlope = slopeDegrees
            val requestedSpeed = (piece.feed / 60.0).coerceAtLeast(0.0)
            val length = sqrt(
                (piece.x2 - piece.x1) * (piece.x2 - piece.x1) +
                    (piece.y2 - piece.y1) * (piece.y2 - piece.y1) + dz * dz,
            )
            val zSpeed = if (length > EPSILON) requestedSpeed * dz / length else 0.0
            val safeSpeed = if (zSpeed > maximumZSpeedMmPerSecond && zSpeed > EPSILON) {
                requestedSpeed * maximumZSpeedMmPerSecond / zSpeed
            } else {
                requestedSpeed
            }
            maximumZSpeed = max(maximumZSpeed, min(zSpeed, maximumZSpeedMmPerSecond))
            val safeFeed = safeSpeed * 60.0
            printerEnvelope.requireMotionMove(
                startX = piece.x1,
                startY = piece.y1,
                startZ = piece.z1,
                endX = piece.x2,
                endY = piece.y2,
                endZ = piece.z2,
                lineNumber = piece.order,
                layerNumber = state.number,
            )
            emitMove(output, piece.x2, piece.y2, piece.z2, piece.deltaE, safeFeed, "G1")
            skinMovesEmitted++
            maximumDive = max(maximumDive, max(0.0, state.baseZ - min(piece.z1, piece.z2)))
        }

        fun flushSkins(output: Appendable, layerNumber: Int) {
            val state = layerStates[layerNumber] ?: return
            if (state.skins.isEmpty()) return
            // Pieces split from one planar move form a contiguous run along the
            // original toolpath. Keep those runs intact (their vertices carry
            // the surface shape), chain the RUNS nearest-neighbor, and only
            // retract/rise/travel/descend between runs - short hops clear just
            // the local surface, long hops clear the whole flush.
            // Shells inherit each source move's own feed and E. The reference
            // prints inner shells with the solid-infill role and the top shell
            // with the top-skin role; with Cura's default flows both roles
            // share the same E/mm, so reusing the source values only means
            // inner shells run at (slower, safer) top-skin speed.
            // Deepest shell first (k = N-1 down to 0): the topmost shell rides
            // the surface, and a later pass would have to dive underneath the
            // freshly printed top shell - the nozzle cone then grazes those
            // lines (the exact 0.1-0.3 mm wall/crossing contacts the sweep
            // reports). Laying the deepest pass first also puts every shell
            // on top of the remaining band-deep stair instead of over a void.
            val byShell = state.skins.groupBy { it.shell }.toSortedMap(reverseOrder())
            var maxPieceZ = state.baseZ

            // The travel height must clear the model's own surface along the
            // straight hop, not just the piece endpoints: a hop across a dome
            // crest would otherwise clip it. Sample the surface and take the
            // highest point plus clearance.
            fun hopTravelZ(fromX: Double, fromY: Double, fromZ: Double, toZ: Double): Double {
                var highest = maxOf(state.baseZ, fromZ, toZ, emittedZ)
                val samples = 8
                for (sampleIndex in 0..samples) {
                    val t = sampleIndex.toDouble() / samples
                    val px = emittedX + (fromX - emittedX) * t
                    val py = emittedY + (fromY - emittedY) * t
                    val surfaceZ = surfaceAt(px, py)?.second ?: continue
                    if (surfaceZ > highest) highest = surfaceZ
                }
                return max(highest, maxPieceZ) + TRAVEL_CLEARANCE_MM
            }

            fun emitERetract(output: Appendable, retract: Boolean) {
                if (sourceRetractDelta <= EPSILON || fileUsesFirmwareRetract) return
                val builder = StringBuilder("G1")
                if (emissionModal.absoluteExtrusion) {
                    runningE += if (retract) -sourceRetractDelta else sourceRetractDelta
                    builder.append(" E").append(format(quantize(runningE)))
                } else {
                    builder.append(" E").append(format(quantize(if (retract) -sourceRetractDelta else sourceRetractDelta)))
                }
                if (sourceRetractFeed > 0.0) builder.append(" F").append(format(sourceRetractFeed))
                emitCommand(output, builder.toString())
            }

            fun travelDance(fromX: Double, fromY: Double, fromZ: Double) {
                val travelZ = hopTravelZ(fromX, fromY, fromZ, fromZ)
                if (fileUsesFirmwareRetract) {
                    emitCommand(output, "G10")
                } else {
                    emitERetract(output, retract = true)
                }
                emitMove(output, emittedX, emittedY, travelZ, null, null, "G0")
                emitMove(output, fromX, fromY, travelZ, null, null, "G0")
                emitMove(output, fromX, fromY, fromZ, null, null, "G0")
                if (fileUsesFirmwareRetract) {
                    emitCommand(output, "G11")
                } else {
                    emitERetract(output, retract = false)
                }
            }

            for ((shell, pieces) in byShell) {
                checkCancellation(shell, "Conformal processing")
                val runs = ArrayList<ArrayList<Piece>>()
                var current = ArrayList<Piece>()
                for (piece in pieces.sortedBy { it.order }) {
                    if (current.isNotEmpty() && piece.moveId != current.last().moveId) {
                        runs += current
                        current = ArrayList()
                    }
                    current += piece
                }
                if (current.isNotEmpty()) runs += current
                var previous: Piece? = null
                for (run in chainRuns(runs, state.lastX, state.lastY)) {
                    val first = run.first()
                    val horizontal = previous?.let { hypot(first.x1 - it.x2, first.y1 - it.y2) }
                        ?: Double.POSITIVE_INFINITY
                    val vertical = previous?.let { abs(first.z1 - it.z2) } ?: 0.0
                    val direct = previous != null && horizontal <= CONNECT_TOLERANCE_MM &&
                        (vertical <= JOIN_MAX_RISE_MM || horizontal < 1e-4 ||
                            vertical / horizontal < CONNECT_SLOPE_LIMIT)
                    if (!direct) travelDance(first.x1, first.y1, first.z1)
                    for (piece in run) {
                        checkCancellation(piece.order, "Conformal processing")
                        maxPieceZ = max(maxPieceZ, max(piece.z1, piece.z2))
                        // Pieces of one source move usually connect, but the
                        // segments that went to other shells leave gaps: bridge
                        // only close, shallow joins - otherwise hop over the
                        // surface instead of cutting through it.
                        if (previous != null && piece !== run.first()) {
                            val gap = hypot(piece.x1 - previous!!.x2, piece.y1 - previous!!.y2)
                            val rise = abs(piece.z1 - previous!!.z2)
                            val joined = gap <= CONNECT_TOLERANCE_MM &&
                                (rise <= JOIN_MAX_RISE_MM || gap < 1e-4 ||
                                    rise / gap < CONNECT_SLOPE_LIMIT)
                            if (!joined) travelDance(piece.x1, piece.y1, piece.z1)
                        }
                        emitSkinPiece(output, piece, state)
                        previous = piece
                    }
                }
            }
            state.skins.clear()
            // In absolute mode the next original move carries its own X/Y/Z,
            // so no return hop is needed. Relative mode must land back on the
            // source position for the original deltas to stay valid.
            if (!emissionModal.absolutePosition) {
                // Surface-sampled, retracted return hop: a plain
                // maxPieceZ+0.3 lift could clip a dome crest on the way back.
                travelDance(state.lastX, state.lastY, state.baseZ)
            }
        }

        try {
            file.bufferedReader().use { input ->
                temporary.bufferedWriter().use { output ->
                    fun processLine(rawLine: String) {
                        val trimmed = rawLine.trimStart()
                        if (emissionAfterMachineEnd) {
                            require(trimmed != NonPlanarRuntime.MACHINE_END_SENTINEL) {
                                "Conformal machine-end sentinel appears more than once"
                            }
                            output.appendLine(rawLine)
                            return
                        }
                        if (trimmed == NonPlanarRuntime.MACHINE_END_SENTINEL) {
                            emissionLayer?.let { flushSkins(output, it) }
                            emissionAfterMachineEnd = true
                            emissionInPrintable = false
                            output.appendLine(rawLine)
                            return
                        }
                        if (!metadataWritten && (trimmed.startsWith(";Generated with Cura") || trimmed.startsWith(";FLAVOR:"))) {
                            output.appendLine(rawLine)
                            writeMetadata(output)
                            return
                        }
                        if (trimmed.startsWith(";LAYER:")) {
                            emissionLayer?.let { flushSkins(output, it) }
                            val number = trimmed.substringAfter(':').trim().toIntOrNull()
                            if (number != null) emissionLayer = number
                            emissionInPrintable = true
                        }
                        if (trimmed.startsWith(";End of Gcode", ignoreCase = true) || trimmed.startsWith(";END_OF_PRINT")) {
                            emissionLayer?.let { flushSkins(output, it) }
                            emissionInPrintable = false
                        }
                        val command = GcodeCommand.parse(rawLine)
                        if (command == null) {
                            output.appendLine(rawLine)
                            return
                        }
                        GcodeCommandPolicy.requireNonPlanarSupported(command, emissionInPrintable)
                        if (emissionModal.apply(command)) {
                            output.appendLine(rawLine)
                            return
                        }
                        when (command.opcode) {
                            "G92" -> {
                                command.value('X')?.let { emittedX = it; sourceX = it }
                                command.value('Y')?.let { emittedY = it; sourceY = it }
                                command.value('Z')?.let { emittedZ = it; sourceZ = it }
                                command.value('E')?.let {
                                    sourceE = it
                                    runningE = it
                                }
                                skippedGap = false
                                output.appendLine(rawLine)
                            }
                            "G0", "G1" -> {
                                val nextX = emissionModal.position(sourceX, command.value('X'))
                                val nextY = emissionModal.position(sourceY, command.value('Y'))
                                val nextZ = emissionModal.position(sourceZ, command.value('Z'))
                                val nextSourceE = emissionModal.extrusion(sourceE, command.value('E'))
                                val deltaE = nextSourceE - sourceE
                                val spatial = abs(nextX - sourceX) > EPSILON ||
                                    abs(nextY - sourceY) > EPSILON || abs(nextZ - sourceZ) > EPSILON
                                val extruding = command.has('E')
                                if (extruding) sourceE = nextSourceE
                                if (spatial) {
                                    sourceX = nextX
                                    sourceY = nextY
                                    sourceZ = nextZ
                                }
                                if (!spatial) {
                                    if (extruding) {
                                        runningE += deltaE
                                        val builder = StringBuilder(command.opcode)
                                        if (emissionModal.absoluteExtrusion) {
                                            builder.append(" E").append(format(quantize(runningE)))
                                        } else {
                                            builder.append(" E").append(format(quantize(deltaE)))
                                        }
                                        command.value('F')?.let { builder.append(" F").append(format(it)) }
                                        rawLine.substringAfter(';', "").takeIf { ';' in rawLine }
                                            ?.let { builder.append(" ;").append(it) }
                                        output.appendLine(builder.toString())
                                    } else {
                                        output.appendLine(rawLine)
                                    }
                                    emittedX = nextX
                                    emittedY = nextY
                                    emittedZ = nextZ
                                    return
                                }
                                // Classify the move by its midpoint so a move straddling
                                // the region boundary is removed only when most of its path
                                // is skin material - endpoint-only checks used to lose or
                                // double-print the outside half of straddling moves.
                                val isStair = extruding && emissionInPrintable && emissionLayer != null &&
                                    firstLayerNumber != null && emissionLayer != firstLayerNumber &&
                                    isSkinSource(
                                        (sourceX + nextX) * 0.5,
                                        (sourceY + nextY) * 0.5,
                                        nextZ,
                                        surface,
                                        layerHeightMm,
                                        shellLayers,
                                    )
                                if (extruding && emissionInPrintable && abs(deltaE) > EPSILON) {
                                    emissionMoveId++
                                }
                                val collected = collectedDeltaEByMove[emissionMoveId] ?: 0.0
                                val collectedSpans = collectedSpansByMove[emissionMoveId]
                                if (isStair) {
                                    // The stair step is skipped entirely: its plastic is
                                    // printed on the conformal shell instead, and because
                                    // the source polyline is continuous the next kept move
                                    // already starts exactly where the nozzle stands, so
                                    // no travel line is needed at all. The modal Z still
                                    // advances so later Z-less moves keep their original
                                    // layer height. Relative-mode streams need the XY
                                    // delta preserved, so emit a plain travel there.
                                    stairMovesRemoved++
                                    val remaining = deltaE - collected
                                    if (remaining > EPSILON) {
                                        if (collectedSpans.isNullOrEmpty()) {
                                            // Exterior remnant without recorded spans:
                                            // emit the rest as one line.
                                            emitMove(
                                                output, nextX, nextY, nextZ, remaining,
                                                command.value('F'), command.opcode,
                                            )
                                        } else {
                                            // A straddling stair: emit only the exterior
                                            // sub-segments and travel across the collected
                                            // interior (already printed as conformal shells).
                                            emitComplement(
                                                output,
                                                sourceX, sourceY, nextX, nextY, nextZ,
                                                remaining,
                                                collectedSpans,
                                                command.value('F'),
                                                command.opcode,
                                            )
                                        }
                                    } else if (emissionModal.absolutePosition) {
                                        emittedZ = nextZ
                                        // Remember the XY gap instead of travelling per
                                        // skipped move (see skippedGap above). Z-only
                                        // stairs keep the fully travel-free path.
                                        if (abs(nextX - emittedX) > EPSILON || abs(nextY - emittedY) > EPSILON) {
                                            skippedGap = true
                                        }
                                    } else {
                                        emitMove(output, nextX, nextY, nextZ, null, command.value('F'), "G0")
                                    }
                                    return
                                }
                                if (skippedGap) {
                                    skippedGap = false
                                    // Bridge to the kept move's start once, instead of
                                    // hopping after every skipped stair.
                                    emitMove(output, sourceX, sourceY, nextZ, null, command.value('F'), "G0")
                                }
                                if (extruding && collected > EPSILON && collectedSpans != null) {
                                    // A kept move with a collected interior: print only
                                    // the exterior sub-segments so the interior is never
                                    // re-traversed at the planar layer.
                                    emitComplement(
                                        output,
                                        sourceX, sourceY, nextX, nextY, nextZ,
                                        deltaE - collected,
                                        collectedSpans,
                                        command.value('F'),
                                        command.opcode,
                                    )
                                    return
                                }
                                emitMove(
                                    output, nextX, nextY, nextZ,
                                    if (extruding) deltaE - collected else null,
                                    command.value('F'),
                                    command.opcode,
                                )
                            }
                            else -> output.appendLine(rawLine)
                        }
                    }

                    val lines = ArrayList<String>()
                    input.forEachLine { lines.add(it) }
                    for (rawLine in lines) {
                        checkCancellation(emittedMoves, "Conformal processing")
                        processLine(rawLine)
                    }
                    emissionLayer?.let { flushSkins(output, it) }
                    writeMetadata(output)
                }
            }

            require(emittedMoves > 0) { "Conformal processing found no printable G-code moves" }
            require(minimumZ >= -0.02) { "Conformal G-code descends below the build plate: " + format(minimumZ) + " mm" }
            require(maximumZ <= printerEnvelope.heightMm + 0.02) {
                "Conformal G-code rises to Z " + format(maximumZ) + " mm outside the " +
                    format(printerEnvelope.heightMm) + " mm build height"
            }
            publishAtomic(temporary, file, "conformal G-code")
            return Diagnostics(
                regionCount = surface.regions.size,
                sourceExtrusionMoves = sourceMoves,
                stairMovesRemoved = stairMovesRemoved,
                skinMovesEmitted = skinMovesEmitted,
                emittedMoves = emittedMoves,
                minimumZmm = minimumZ,
                maximumZmm = maximumZ,
                maximumDiveMm = maximumDive,
                maximumObservedSlopeDegrees = maximumSlope,
                maximumObservedZSpeedMmPerSecond = maximumZSpeed,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun isSkinSource(
        x: Double,
        y: Double,
        z: Double,
        surface: ConformalSurface,
        layerHeightMm: Double,
        shellLayers: Int,
    ): Boolean {
        for (region in surface.regions) {
            val surfaceZ = region.surfaceZ(x, y) ?: continue
            // Same all-region boundary back-off as walk 1 so the two passes
            // always agree on which moves belong to the conformal shells.
            for (other in surface.regions) {
                if (x < other.minX - BOUNDARY_BACKOFF_MM || x > other.maxX + BOUNDARY_BACKOFF_MM ||
                    y < other.minY - BOUNDARY_BACKOFF_MM || y > other.maxY + BOUNDARY_BACKOFF_MM
                ) {
                    continue
                }
                if (other.distanceToBoundary(x, y) < BOUNDARY_BACKOFF_MM) return false
            }
            val band = floor((surfaceZ - z) / layerHeightMm + 1e-9).toInt()
            if (band in 0 until shellLayers) return true
        }
        return false
    }

    /**
     * Orders the shell's runs into a space-filling chain: starting from the
     * run nearest to (startX, startY), repeatedly continue with the unused run
     * whose start point lies closest to the current run's end point. A coarse
     * grid keeps each step cheap; runs stay intact so the original toolpath
     * structure (and its surface-following vertices) is preserved.
     */
    private fun chainRuns(
        runs: List<List<Piece>>,
        startX: Double,
        startY: Double,
    ): List<List<Piece>> {
        if (runs.size <= 1) return runs
        val cell = NEIGHBOR_CELL_MM
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (run in runs) {
            val first = run.first()
            minX = min(minX, first.x1); maxX = max(maxX, first.x1)
            minY = min(minY, first.y1); maxY = max(maxY, first.y1)
        }
        val columns = max(1, ((maxX - minX) / cell + 1).toInt())
        val rows = max(1, ((maxY - minY) / cell + 1).toInt())
        val buckets = HashMap<Int, ArrayList<Int>>(runs.size)
        fun cellOf(x: Double, y: Double): Int {
            val gx = ((x - minX) / cell).toInt().coerceIn(0, columns - 1)
            val gy = ((y - minY) / cell).toInt().coerceIn(0, rows - 1)
            return gy * columns + gx
        }
        for (index in runs.indices) {
            val first = runs[index].first()
            buckets.getOrPut(cellOf(first.x1, first.y1)) { ArrayList(4) }.add(index)
        }
        val used = BooleanArray(runs.size)
        val result = ArrayList<List<Piece>>(runs.size)
        var currentIndex = 0
        var currentDistance = hypot(runs[0].first().x1 - startX, runs[0].first().y1 - startY)
        for (index in 1 until runs.size) {
            val first = runs[index].first()
            val distance = hypot(first.x1 - startX, first.y1 - startY)
            if (distance < currentDistance) {
                currentDistance = distance
                currentIndex = index
            }
        }
        used[currentIndex] = true
        var current = runs[currentIndex]
        result += current
        var remaining = runs.size - 1
        while (remaining > 0) {
            val end = current.last()
            val cx = ((end.x2 - minX) / cell).toInt().coerceIn(0, columns - 1)
            val cy = ((end.y2 - minY) / cell).toInt().coerceIn(0, rows - 1)
            var bestIndex = -1
            var bestDistance = Double.MAX_VALUE
            val maxRadius = max(columns, rows) + 1
            for (radius in 0..maxRadius) {
                // Any point in ring r lies at least (r - 1) cells from the
                // endpoint's own cell, so once the best candidate is closer
                // than that bound no farther ring can beat it. The first
                // non-empty ring alone is NOT enough: when the endpoint sits
                // near a cell border a neighbouring ring can hold a nearer
                // run than the current ring does.
                if (bestDistance != Double.MAX_VALUE && radius >= 2 &&
                    (radius - 1) * cell >= bestDistance
                ) break
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (max(abs(dx), abs(dy)) != radius) continue
                        val gx = cx + dx
                        val gy = cy + dy
                        if (gx !in 0 until columns || gy !in 0 until rows) continue
                        val bucket = buckets[gy * columns + gx] ?: continue
                        for (index in bucket) {
                            if (used[index]) continue
                            val first = runs[index].first()
                            val distance = hypot(first.x1 - end.x2, first.y1 - end.y2)
                            if (distance < bestDistance) {
                                bestDistance = distance
                                bestIndex = index
                            }
                        }
                    }
                }
            }
            if (bestIndex < 0) bestIndex = used.indexOfFirst { !it }
            used[bestIndex] = true
            current = runs[bestIndex]
            result += current
            remaining--
        }
        return result
    }

    private fun quantize(value: Double): Double = quantizeGcode(value)

    private fun format(value: Double): String = formatGcode(value)
}
