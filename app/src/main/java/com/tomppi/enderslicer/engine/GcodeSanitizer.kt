package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.BuildConfig
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

object GcodeSanitizer {
    data class Summary(
        val layerCount: Int,
        val estimatedSeconds: Int?,
        val filamentMillimeters: Double,
        val totalFilamentMillimeters: Double,
        val minX: Double?,
        val minY: Double?,
        val minZ: Double?,
        val maxX: Double?,
        val maxY: Double?,
        val maxZ: Double?,
    )

    class UnsafeGcodeException(message: String) : Exception(message)

    fun validateAndRepair(
        file: File,
        settingsTransport: String = "auto",
        printerEnvelope: PrinterEnvelope? = null,
        dialect: GcodeDialect = GcodeDialect.CURA,
        /**
         * The user's own start/end G-code templates, as written into the engine
         * config. The PrusaSlicer family emits M201-M205 machine limits from its
         * base printer profile - those are dropped below - but a limit the user
         * wrote into a template is a deliberate choice and is kept.
         */
        userGcode: List<String> = emptyList(),
    ): Summary {
        require('\n' !in settingsTransport && '\r' !in settingsTransport) {
            "Settings transport marker contains a line break"
        }
        require(file.isFile && file.length() > 0L) { "Generated G-code is empty" }
        val resolvedSettingsTransport = when {
            !settingsTransport.equals("auto", ignoreCase = true) -> settingsTransport
            File(file.parentFile, "resolved-settings.json").isFile -> "resolved-json"
            else -> "fallback-command"
        }

        // PrusaSlicer and OrcaSlicer do not write ;LAYER_COUNT:; their layer markers are
        // counted upfront so the final-layer detection can distinguish the real end-gcode block.
        var layerCount = if (dialect.isPrusaFamily) {
            file.bufferedReader().useLines { counter ->
                counter.count { line -> dialect.opensLayer(line.trimStart()) }
            }
        } else {
            0
        }
        var currentLayer: Int? = null
        var lastLayerSeen: Int? = null
        var lastElapsed: Double? = null
        val modalState = GcodeModalState()
        var currentE = 0.0
        var modelFilament = 0.0
        var totalFilament = 0.0
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var inModelMesh = false
        var minX: Double? = null
        var minY: Double? = null
        var minZ: Double? = null
        var maxX: Double? = null
        var maxY: Double? = null
        var maxZ: Double? = null
        var explicitNozzleTarget: Double? = null
        var nozzleTargetLine: Int? = null
        var nozzleTargetLayer: Int? = null
        var lineNumber = 0
        var feedRateMmPerMinute = 0.0
        var speedFactor = 1.0
        var rawMotionSeconds = 0.0
        var effectiveMotionSeconds = 0.0

        file.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                lineNumber++
                val line = rawLine.trimStart()
                when {
                    dialect.isPrusaFamily && dialect.opensLayer(line) -> {
                        currentLayer = (currentLayer ?: -1) + 1
                        lastLayerSeen = currentLayer
                    }
                    // The fork family writes the same footer comments, so both engines share these.
                    dialect.isPrusaFamily && line.startsWith("; estimated printing time (normal mode)") -> {
                        parseElapsedClock(line)?.let { lastElapsed = it }
                    }
                    dialect.isPrusaFamily && line.startsWith("; filament used [mm]") -> {
                        line.substringAfter('=').trim().toDoubleOrNull()?.let { filamentMm ->
                            totalFilament = filamentMm
                            modelFilament = filamentMm
                        }
                    }
                    line.startsWith(";LAYER_COUNT:") -> {
                        line.substringAfter(':').trim().toIntOrNull()?.let { layerCount = it }
                    }
                    line.startsWith(";LAYER:") -> {
                        currentLayer = line.substringAfter(':').trim().toIntOrNull()
                        if (currentLayer != null) lastLayerSeen = currentLayer
                    }
                    line.startsWith(";TIME_ELAPSED:") -> {
                        lastElapsed = line.substringAfter(':').trim().toDoubleOrNull() ?: lastElapsed
                    }
                    line.startsWith(";MESH:") -> {
                        val meshName = line.substringAfter(':').trim()
                        inModelMesh = meshName.isNotEmpty() && !meshName.equals("NONMESH", ignoreCase = true)
                    }
                }

                val command = GcodeCommand.parse(rawLine)
                if (command == null) {
                    try {
                        GcodeCommandPolicy.requirePublishedTextSafe(
                            rawLine = rawLine,
                            gcodeFlavor = printerEnvelope?.gcodeFlavor ?: PrinterEnvelope.DEFAULT_GCODE_FLAVOR,
                            lineNumber = lineNumber,
                        )
                    } catch (error: IllegalArgumentException) {
                        throw UnsafeGcodeException(
                            (error.message ?: "Unsupported textual command") +
                                " | offending line: " + rawLine.trim().take(140),
                        )
                    }
                    return@forEach
                }
                val reachedFinalLayer = lastLayerSeen?.let { it >= layerCount - 1 } ?: false
                GcodeCommandPolicy.requirePublishedSafe(
                    command,
                    currentLayer,
                    lineNumber,
                    inEndGcode = layerCount > 0 && reachedFinalLayer,
                )
                GcodeCommandPolicy.speedFactor(command)?.let { factor ->
                    speedFactor = factor
                    return@forEach
                }
                if (modalState.apply(command)) return@forEach
                when (command.opcode) {
                    "G92" -> {
                        command.value('E')?.let { currentE = it }
                        command.value('X')?.let { x = it }
                        command.value('Y')?.let { y = it }
                        command.value('Z')?.let { z = it }
                    }
                    "M104", "M109" -> {
                        val target = command.value('S') ?: command.value('R')
                        if (target != null) {
                            explicitNozzleTarget = target
                            nozzleTargetLine = lineNumber
                            nozzleTargetLayer = currentLayer
                        }
                    }
                    "G0", "G1" -> {
                        val startX = x
                        val startY = y
                        val startZ = z
                        x = modalState.position(x, command.value('X'))
                        y = modalState.position(y, command.value('Y'))
                        z = modalState.position(z, command.value('Z'))
                        command.value('F')?.let { feedRateMmPerMinute = it }
                        val spatialMove = startX != x || startY != y || startZ != z
                        if (spatialMove && feedRateMmPerMinute > 0.0) {
                            val dx = x - startX
                            val dy = y - startY
                            val dz = z - startZ
                            val distance = sqrt(dx * dx + dy * dy + dz * dz)
                            val rawSpeed = feedRateMmPerMinute / 60.0
                            rawMotionSeconds += distance / rawSpeed
                            effectiveMotionSeconds += distance / (rawSpeed * speedFactor)
                        }
                        if (spatialMove) {
                            printerEnvelope?.requireMotionMove(
                                startX = startX,
                                startY = startY,
                                startZ = startZ,
                                endX = x,
                                endY = y,
                                endZ = z,
                                lineNumber = lineNumber,
                                layerNumber = currentLayer,
                            )
                        }
                        var positiveExtrusion = 0.0
                        command.value('E')?.let { requested ->
                            val nextE = modalState.extrusion(currentE, requested)
                            val delta = nextE - currentE
                            if (delta > 0.0) {
                                positiveExtrusion = delta
                                val target = explicitNozzleTarget
                                if (target != null && target in 0.0..<MINIMUM_ACTIVE_NOZZLE_C) {
                                    val extrusionLayer = currentLayer?.let { "layer $it" } ?: "startup"
                                    val targetLocation = buildString {
                                        append("target set")
                                        nozzleTargetLine?.let { append(" at line $it") }
                                        nozzleTargetLayer?.let { append(", layer $it") }
                                    }
                                    throw UnsafeGcodeException(
                                        "Unsafe nozzle target ${formatDecimal(target, 5)} C while extruding at $extrusionLayer " +
                                            "(extrusion line $lineNumber; $targetLocation). " +
                                            "The G-code was not made available for export.",
                                    )
                                }
                                if (spatialMove) {
                                    printerEnvelope?.requireExtrusionMove(
                                        startX = startX,
                                        startY = startY,
                                        startZ = startZ,
                                        endX = x,
                                        endY = y,
                                        endZ = z,
                                        lineNumber = lineNumber,
                                        layerNumber = currentLayer,
                                    )
                                }
                            }
                            currentE = nextE
                        }

                        if (currentLayer != null && positiveExtrusion > 0.0) {
                            totalFilament += positiveExtrusion
                        }
                        if (inModelMesh && currentLayer != null && positiveExtrusion > 0.0) {
                            modelFilament += positiveExtrusion
                            minX = minX?.let { minOf(it, startX, x) } ?: minOf(startX, x)
                            minY = minY?.let { minOf(it, startY, y) } ?: minOf(startY, y)
                            minZ = minZ?.let { minOf(it, startZ, z) } ?: minOf(startZ, z)
                            maxX = maxX?.let { maxOf(it, startX, x) } ?: maxOf(startX, x)
                            maxY = maxY?.let { maxOf(it, startY, y) } ?: maxOf(startY, y)
                            maxZ = maxZ?.let { maxOf(it, startZ, z) } ?: maxOf(startZ, z)
                        }
                    }
                }
            }
        }

        // Matched by their own lines: a template may carry placeholders the engine
        // resolves, and such a line cannot be recognised in the output anyway.
        val userMachineLimits = userGcode
            .asSequence()
            .flatMap { it.lineSequence() }
            .map(String::trim)
            .filter { it.isNotEmpty() && PRUSA_MACHINE_LIMIT.containsMatchIn(it) }
            .toHashSet()

        val adjustedElapsed = lastElapsed?.let { elapsed ->
            max(0.0, elapsed + effectiveMotionSeconds - rawMotionSeconds)
        }
        val estimatedSeconds = adjustedElapsed?.let { ceil(it).toInt() }
        val temporary = File(file.parentFile, "${file.name}.validated")
        temporary.delete()
        temporary.outputStream().buffered().writer(Charsets.UTF_8).buffered().use { writer ->
            var insertedMarkers = false
            file.bufferedReader().useLines { lines ->
                lines.forEach { originalLine ->
                    // The 3.x Cura profile resolution and the OrcaSlicer vendor
                    // profiles alike emit M201-M205 (machine limits) from their base
                    // printer profile regardless of the app config; the target printer
                    // firmware applies its own defaults.
                    if (dialect.isPrusaFamily &&
                        PRUSA_MACHINE_LIMIT.containsMatchIn(originalLine) &&
                        originalLine.trim() !in userMachineLimits
                    ) {
                        return@forEach
                    }
                    val line = when {
                        originalLine.startsWith(";ENDERSLICER_VERSION:") ||
                            originalLine.startsWith(";ENDERSLICER_COORDINATE_TRANSPORT:") ||
                            originalLine.startsWith(";ENDERSLICER_SETTINGS_TRANSPORT:") -> return@forEach
                        dialect == GcodeDialect.CURA && originalLine.startsWith(";TIME:") && estimatedSeconds != null -> ";TIME:$estimatedSeconds"
                        dialect == GcodeDialect.CURA && originalLine.startsWith(";Filament used:") ->
                            ";Filament used: ${formatDecimal(totalFilament / 1000.0, 5)}m"
                        originalLine.startsWith(";MINX:") && minX != null -> ";MINX:${formatDecimal(requireNotNull(minX), 5)}"
                        originalLine.startsWith(";MINY:") && minY != null -> ";MINY:${formatDecimal(requireNotNull(minY), 5)}"
                        originalLine.startsWith(";MINZ:") && minZ != null -> ";MINZ:${formatDecimal(requireNotNull(minZ), 5)}"
                        originalLine.startsWith(";MAXX:") && maxX != null -> ";MAXX:${formatDecimal(requireNotNull(maxX), 5)}"
                        originalLine.startsWith(";MAXY:") && maxY != null -> ";MAXY:${formatDecimal(requireNotNull(maxY), 5)}"
                        originalLine.startsWith(";MAXZ:") && maxZ != null -> ";MAXZ:${formatDecimal(requireNotNull(maxZ), 5)}"
                        else -> originalLine
                    }
                    writer.write(line)
                    writer.write(PRINTER_LINE_ENDING)
                    if (!insertedMarkers) {
                        writer.write(";ENDERSLICER_VERSION:${BuildConfig.VERSION_NAME}")
                        writer.write(PRINTER_LINE_ENDING)
                        writer.write(";ENDERSLICER_COORDINATE_TRANSPORT:original-stl-full-affine-pre-round")
                        writer.write(PRINTER_LINE_ENDING)
                        writer.write(";ENDERSLICER_SETTINGS_TRANSPORT:$resolvedSettingsTransport")
                        writer.write(PRINTER_LINE_ENDING)
                        insertedMarkers = true
                    }
                }
            }
        }
        check(temporary.length() > 0L) { "Validated G-code output is empty" }
        // Atomic replace: never delete the original before the validated file is
        // in place, so a failed rename leaves the source intact.
        try {
            java.nio.file.Files.move(
                temporary.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.io.IOException) {
            check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = true).let { temporary.delete(); true }) {
                "Unable to replace generated G-code with the validated output"
            }
        }
        return Summary(
            layerCount = layerCount,
            estimatedSeconds = estimatedSeconds,
            filamentMillimeters = modelFilament,
            totalFilamentMillimeters = totalFilament,
            minX = minX,
            minY = minY,
            minZ = minZ,
            maxX = maxX,
            maxY = maxY,
            maxZ = maxZ,
        )
    }

    /**
     * Parses "; estimated printing time (normal mode) = 1h 23m 37s" into seconds.
     *
     * The clock fields are G-code text from whichever engine wrote the file, so
     * an absurd digit run must not throw: a NumberFormatException escaping here
     * fails an otherwise successful slice with the one exception type the
     * callers do not document, over a comment that is only an estimate.
     */
    private fun parseElapsedClock(line: String): Double? {
        // The shared parser, because it also reads the day field PrusaSlicer and
        // OrcaSlicer grow from 24 hours up: a "1d 4h" header used to read as no
        // estimate at all and the sanitizer replaced it with its own guess.
        return parseEnginePrintTimeEstimate(line.substringAfter('=').trim())?.toDouble()
    }

    /**
     * One clock field, clamped so a hostile comment cannot overflow.
     *
     * An absent field is zero and a field longer than Long can hold reads as the
     * clamp. Four clamped fields can still add up past Int seconds, so the
     * estimate this feeds saturates instead of wrapping. Shared with the Prusa
     * runner, which parses the same comment for its own summary.
     */
    internal fun clockComponent(raw: String): Long {
        if (raw.isEmpty()) return 0L
        return (raw.toLongOrNull() ?: MAX_CLOCK_COMPONENT).coerceAtMost(MAX_CLOCK_COMPONENT)
    }

    private val PRUSA_MACHINE_LIMIT = Regex("^M20[1-5]\\s")

    private const val MINIMUM_ACTIVE_NOZZLE_C = 150.0
    /** 100,000 h is over eleven years: past any print, and still inside Int seconds. */
    private const val MAX_CLOCK_COMPONENT = 100_000L
    private const val PRINTER_LINE_ENDING = "\r\n"
}
