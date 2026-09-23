package com.tomppi.enderslicer.conical

import com.tomppi.enderslicer.engine.GcodeCommand
import com.tomppi.enderslicer.engine.GcodeCommandPolicy
import com.tomppi.enderslicer.engine.GcodeModalState
import com.tomppi.enderslicer.engine.MAX_EMITTED_MOVES
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.engine.formatGcode
import com.tomppi.enderslicer.engine.publishAtomic
import com.tomppi.enderslicer.engine.quantizeGcode
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Android-native port of EasyConical's `backtransform_data_radial` and
 * `translate_data`. It reads CuraEngine output, restores every G0/G1 move from
 * the warped cone coordinates to the original geometry around the warp centre,
 * sub-dividing long segments, compensating extrusion for the true 3D path
 * length, then translates X/Y and lifts the print to the configured first-layer
 * height. Travel moves are clipped to 1 mm above the highest extruded point.
 *
 * The port requires absolute positioning (G90), which CuraEngine always emits,
 * and supports both absolute (M82) and relative (M83) extrusion.
 */
internal object ConicalGcodeTransformer {
    private const val EPSILON = 1e-8
    private const val MAX_SEGMENT_LENGTH_MM = 0.5
    private const val TRAVEL_CLEARANCE_MM = 1.0

    fun transform(
        file: File,
        centerX: Double,
        centerY: Double,
        settings: ConicalSettings,
        printerEnvelope: PrinterEnvelope,
    ): ConicalPipeline.GcodeDiagnostics {
        require(file.isFile && file.length() > 0L) { "Conical G-code is missing" }
        val temporary = File(file.parentFile, "${file.name}.conical.tmp")
        temporary.delete()

        val safe = settings.validated()
        val cosine = cos(safe.coneAngleRadians)
        val tangent = tan(safe.coneAngleRadians)
        val sign = safe.coneType.sign

        val modal = GcodeModalState()
        var xOld = 0.0
        var yOld = 0.0
        var zLayer = 0.0
        var zMax = 0.0
        var sawExtrusion = false
        var inSupportSection = false
        var planarE = 0.0
        var curvedE = 0.0
        var idealCurvedE = 0.0
        var afterMachineEnd = false
        var inPrintableLayers = false
        var metadataWritten = false
        var sourceMoves = 0
        var emittedMoves = 0
        var subdividedMoves = 0
        var extrusionMoves = 0
        var travelMoves = 0

        val backtransformed = ArrayList<String>()

        fun appendRaw(line: String) = backtransformed.add(line)

        fun writeMetadata() {
            if (metadataWritten) return
            metadataWritten = true
            backtransformed.add(";ENDERSLICER_CONICAL:EasyConical-Android-v${ConicalSettingsStore.BACKEND_VERSION}")
            backtransformed.add(";ENDERSLICER_CONICAL_ANGLE:${format(safe.coneAngleDegrees)}")
            backtransformed.add(";ENDERSLICER_CONICAL_TYPE:${safe.coneType.name.lowercase()}")
        }

        fun processLine(rawLine: String) {
            val trimmed = rawLine.trimStart()
            if (afterMachineEnd) {
                appendRaw(rawLine)
                return
            }
            if (trimmed == ConicalRuntime.MACHINE_END_SENTINEL) {
                afterMachineEnd = true
                inPrintableLayers = false
                appendRaw(rawLine)
                if (abs(curvedE - planarE) > EPSILON) {
                    appendRaw("G92 E${format(planarE)} ; restore Cura E coordinate before machine end G-code")
                }
                curvedE = planarE
                idealCurvedE = planarE
                return
            }
            if (!metadataWritten && (trimmed.startsWith(";Generated with Cura") || trimmed.startsWith(";FLAVOR:"))) {
                appendRaw(rawLine)
                writeMetadata()
                return
            }
            if (trimmed.startsWith(";LAYER:")) {
                inPrintableLayers = true
            }
            if (trimmed.startsWith(";TYPE:")) {
                val pathType = trimmed.substringAfter(':').trim()
                inSupportSection = pathType.startsWith("SUPPORT")
            }
            if (trimmed.startsWith(";End of Gcode", ignoreCase = true) || trimmed.startsWith(";END_OF_PRINT")) {
                inPrintableLayers = false
            }

            val command = GcodeCommand.parse(rawLine)
            if (command == null) {
                appendRaw(rawLine)
                return
            }
            GcodeCommandPolicy.requireConicalSupported(command, inPrintableLayers)
            if (modal.apply(command)) {
                appendRaw(rawLine)
                return
            }

            when (command.opcode) {
                "G92" -> {
                    require(!inPrintableLayers || (!command.has('X') && !command.has('Y') && !command.has('Z'))) {
                        "Conical slicing does not support G92 coordinate resets inside printable layers"
                    }
                    command.value('X')?.let { xOld = it }
                    command.value('Y')?.let { yOld = it }
                    command.value('Z')?.let { zLayer = it }
                    command.value('E')?.let {
                        planarE = it
                        curvedE = it
                        idealCurvedE = it
                    }
                    appendRaw(rawLine)
                }
                "G0", "G1" -> {
                    val hasX = command.has('X')
                    val hasY = command.has('Y')
                    val hasZ = command.has('Z')
                    val hasE = command.has('E')
                    val nextPlanarE = modal.extrusion(planarE, command.value('E'))
                    val deltaE = nextPlanarE - planarE

                    if (hasZ) zLayer = modal.position(zLayer, command.value('Z'))
                    val xNew = modal.position(xOld, command.value('X'))
                    val yNew = modal.position(yOld, command.value('Y'))

                    // Start/end G-code is machine setup, not print geometry: pass it
                    // through verbatim (prime lines sit far from the model centre).
                    if (!inPrintableLayers) {
                        appendRaw(rawLine)
                        planarE = nextPlanarE
                        if (hasE) {
                            curvedE = nextPlanarE
                            idealCurvedE = nextPlanarE
                        }
                        xOld = xNew
                        yOld = yNew
                        return
                    }

                    require(modal.absolutePosition) {
                        "Conical slicing requires absolute positioning (G90); re-slice without relative XYZ mode"
                    }
                    val spatial = hasX || hasY || hasZ

                    if (!spatial) {
                        if (hasE) {
                            idealCurvedE += deltaE
                            val emittedE = if (modal.absoluteExtrusion) quantize(idealCurvedE) else deltaE
                            val builder = StringBuilder(command.opcode).append(" E").append(format(emittedE))
                            command.value('F')?.let { builder.append(" F").append(format(it)) }
                            appendRaw(builder.toString())
                            curvedE = if (modal.absoluteExtrusion) emittedE else curvedE + emittedE
                        } else {
                            appendRaw(rawLine)
                        }
                        planarE = nextPlanarE
                        return
                    }

                    sourceMoves++
                    val xOldBt = centerX + (xOld - centerX) * cosine
                    val yOldBt = centerY + (yOld - centerY) * cosine
                    val xNewBt = centerX + (xNew - centerX) * cosine
                    val yNewBt = centerY + (yNew - centerY) * cosine
                    val distTransformed = hypot(xNew - xOld, yNew - yOld)
                    val segmentCount = max(1, (distTransformed / MAX_SEGMENT_LENGTH_MM).toInt() + 1)
                    if (segmentCount > 1) subdividedMoves++
                    check(emittedMoves + segmentCount <= MAX_EMITTED_MOVES) {
                        "Conical path subdivision exceeded $MAX_EMITTED_MOVES moves"
                    }

                    val xVals = DoubleArray(segmentCount + 1) { i ->
                        val t = i.toDouble() / segmentCount
                        xOldBt + (xNewBt - xOldBt) * t
                    }
                    val yVals = DoubleArray(segmentCount + 1) { i ->
                        val t = i.toDouble() / segmentCount
                        yOldBt + (yNewBt - yOldBt) * t
                    }

                    // ConicalSettings.validated() coerces the cone type to
                    // OUTWARD, so only the outward back-transform is reachable.
                    val zVals = DoubleArray(segmentCount + 1)
                    for (i in 0..segmentCount) {
                        val radius = hypot(xVals[i] - centerX, yVals[i] - centerY)
                        zVals[i] = zLayer - sign * radius * tangent
                    }
                    val maxZ = zVals.maxOrNull() ?: zLayer
                    if (hasE) {
                        if (!sawExtrusion || maxZ > zMax) zMax = maxZ
                        sawExtrusion = true
                    } else {
                        val travelLimit = if (sawExtrusion) zMax else 0.0
                        if (maxZ > travelLimit) {
                            for (i in zVals.indices) zVals[i] = min(zVals[i], travelLimit + TRAVEL_CLEARANCE_MM)
                        }
                    }

                    // CuraEngine builds support towers in WARPED space from the
                    // plate up to the warped model surface. The back-transform
                    // maps the tower bottoms to z - r * tan(theta) < 0: a
                    // support layer emitted at the warped plate would print
                    // below the bed and drag the global translate() lift up with
                    // it, floating the whole model. Anchor support moves to the
                    // plate instead: clamp the emitted Z at 0 and skip support
                    // extrusion layers that fall entirely below the plate so
                    // the skipped filament is not re-attributed to a later move.
                    if (inSupportSection) {
                        var allBelowPlate = true
                        for (i in zVals.indices) {
                            if (zVals[i] >= 0.0) {
                                allBelowPlate = false
                            } else {
                                zVals[i] = 0.0
                            }
                        }
                        if (hasE && allBelowPlate) {
                            planarE = nextPlanarE
                            xOld = xNew
                            yOld = yNew
                            return
                        }
                    }

                    val distancesBt = DoubleArray(segmentCount) { i ->
                        val dx = xVals[i + 1] - xVals[i]
                        val dy = yVals[i + 1] - yVals[i]
                        val dz = zVals[i + 1] - zVals[i]
                        sqrt(dx * dx + dy * dy + dz * dz)
                    }
                    val totalDistBt = distancesBt.sum()
                    val totalE = if (deltaE > EPSILON && distTransformed > EPSILON) {
                        deltaE * cosine * totalDistBt / distTransformed
                    } else {
                        0.0
                    }
                    val startIdealCurvedE = idealCurvedE

                    var cumulative = 0.0
                    var emittedCurvedE = curvedE
                    for (j in 0 until segmentCount) {
                        cumulative += distancesBt[j]
                        val fraction = if (totalDistBt > EPSILON) {
                            cumulative / totalDistBt
                        } else {
                            (j + 1).toDouble() / segmentCount
                        }
                        val idealTargetE = startIdealCurvedE + totalE * fraction
                        val emittedE = if (modal.absoluteExtrusion) {
                            quantize(idealTargetE)
                        } else {
                            quantize(idealTargetE - emittedCurvedE)
                        }
                        emittedCurvedE = if (modal.absoluteExtrusion) emittedE else emittedCurvedE + emittedE

                        val builder = StringBuilder(command.opcode)
                        builder.append(" X").append(format(xVals[j + 1]))
                        builder.append(" Y").append(format(yVals[j + 1]))
                        builder.append(" Z").append(format(zVals[j + 1]))
                        if (hasE) builder.append(" E").append(format(emittedE))
                        command.value('F')?.let { builder.append(" F").append(format(it)) }
                        appendRaw(builder.toString())
                        emittedMoves++
                    }

                    if (hasE) {
                        idealCurvedE = startIdealCurvedE + totalE
                        curvedE = emittedCurvedE
                        extrusionMoves += segmentCount
                    } else {
                        travelMoves += segmentCount
                    }
                    planarE = nextPlanarE
                    xOld = xNew
                    yOld = yNew
                }
                else -> appendRaw(rawLine)
            }
        }

        val lines = ArrayList<String>()
        file.forEachLine { lines.add(it) }
        lines.forEach(::processLine)
        writeMetadata()

        require(emittedMoves > 0) { "Conical slicing found no printable G-code moves to back-transform" }

        val translated = translate(backtransformed, safe)
        val diagnostics = validate(translated, printerEnvelope, sourceMoves, emittedMoves, subdividedMoves, extrusionMoves, travelMoves)

        try {
            temporary.bufferedWriter().use { output ->
                translated.forEach { output.appendLine(it) }
            }
            publishAtomic(temporary, file, "conical G-code")
        } finally {
            temporary.delete()
        }
        return diagnostics
    }

    /** Applies X/Y shift and lifts the print so its lowest extruded Z is the first-layer height. */
    private fun translate(lines: List<String>, settings: ConicalSettings): List<String> {
        val zDesired = settings.firstLayerHeightMm
        var zMin = Double.POSITIVE_INFINITY
        var zInitialized = false
        var printable = false
        var afterEnd = false
        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed == ConicalRuntime.MACHINE_END_SENTINEL) {
                afterEnd = true
                continue
            }
            if (afterEnd) continue
            if (trimmed.startsWith(";LAYER:")) {
                printable = true
                continue
            }
            if (trimmed.startsWith(";End of Gcode", ignoreCase = true) || trimmed.startsWith(";END_OF_PRINT")) {
                printable = false
                continue
            }
            if (!printable) continue
            val command = GcodeCommand.parse(line) ?: continue
            if (command.opcode != "G0" && command.opcode != "G1") continue
            val z = command.value('Z') ?: continue
            if (!command.has('E')) continue
            if (!zInitialized || z < zMin) {
                zMin = z
                zInitialized = true
            }
        }
        val zTranslate = if (zInitialized) zDesired - zMin else 0.0

        val result = ArrayList<String>(lines.size)
        printable = false
        afterEnd = false
        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed == ConicalRuntime.MACHINE_END_SENTINEL) {
                afterEnd = true
                result.add(line)
                continue
            }
            if (afterEnd) {
                result.add(line)
                continue
            }
            if (trimmed.startsWith(";LAYER:")) {
                printable = true
                result.add(line)
                continue
            }
            if (trimmed.startsWith(";End of Gcode", ignoreCase = true) || trimmed.startsWith(";END_OF_PRINT")) {
                printable = false
                result.add(line)
                continue
            }
            val command = GcodeCommand.parse(line)
            if (command == null || (command.opcode != "G0" && command.opcode != "G1")) {
                result.add(line)
                continue
            }
            if (!printable) {
                result.add(line)
                continue
            }
            val hasSpatial = command.has('X') || command.has('Y') || command.has('Z')
            if (!hasSpatial) {
                result.add(line)
                continue
            }
            val comment = line.substringAfter(';', "").takeIf { ';' in line }
            val builder = StringBuilder(command.opcode)
            command.value('X')?.let { builder.append(" X").append(format(it + settings.xShiftMm)) }
            command.value('Y')?.let { builder.append(" Y").append(format(it + settings.yShiftMm)) }
            command.value('Z')?.let { builder.append(" Z").append(format(max(it + zTranslate, zDesired))) }
            command.value('E')?.let { builder.append(" E").append(format(it)) }
            command.value('F')?.let { builder.append(" F").append(format(it)) }
            if (comment != null) builder.append(" ;").append(comment)
            result.add(builder.toString())
        }
        return result
    }

    private fun validate(
        lines: List<String>,
        printerEnvelope: PrinterEnvelope,
        sourceMoves: Int,
        emittedMoves: Int,
        subdividedMoves: Int,
        extrusionMoves: Int,
        travelMoves: Int,
    ): ConicalPipeline.GcodeDiagnostics {
        var minimumZ = Double.POSITIVE_INFINITY
        var maximumZ = Double.NEGATIVE_INFINITY
        var lineNumber = 0
        var layerNumber: Int? = null
        var currentX = 0.0
        var currentY = 0.0
        var currentZ = 0.0
        for (line in lines) {
            lineNumber++
            val trimmed = line.trimStart()
            if (trimmed.startsWith(";LAYER:")) {
                layerNumber = trimmed.substringAfter(':').trim().toIntOrNull()
            }
            val command = GcodeCommand.parse(line) ?: continue
            if (command.opcode != "G0" && command.opcode != "G1") continue
            if (!command.has('X') && !command.has('Y') && !command.has('Z')) continue
            command.value('X')?.let { currentX = it }
            command.value('Y')?.let { currentY = it }
            command.value('Z')?.let { currentZ = it }
            printerEnvelope.requireMotionMove(
                startX = currentX,
                startY = currentY,
                startZ = currentZ,
                endX = currentX,
                endY = currentY,
                endZ = currentZ,
                lineNumber = lineNumber,
                layerNumber = layerNumber,
            )
            minimumZ = minOf(minimumZ, currentZ)
            maximumZ = maxOf(maximumZ, currentZ)
        }
        require(minimumZ >= -0.02) { "Conical slicing generated a path below the build plate: ${format(minimumZ)} mm" }
        require(maximumZ <= printerEnvelope.heightMm + 0.02) {
            "Conical slicing generated Z ${format(maximumZ)} mm outside the ${format(printerEnvelope.heightMm)} mm build height"
        }
        return ConicalPipeline.GcodeDiagnostics(
            sourceMoves = sourceMoves,
            emittedMoves = emittedMoves,
            subdividedMoves = subdividedMoves,
            extrusionMoves = extrusionMoves,
            travelMoves = travelMoves,
            minimumZmm = minimumZ,
            maximumZmm = maximumZ,
        )
    }

    private fun quantize(value: Double): Double = quantizeGcode(value)

    private fun format(value: Double): String = formatGcode(value)
}
