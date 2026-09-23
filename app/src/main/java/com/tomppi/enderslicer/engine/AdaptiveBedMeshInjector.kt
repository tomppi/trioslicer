package com.tomppi.enderslicer.engine

import java.io.File

/**
 * Adaptive mesh leveling (AML) for the mriscoc Ender 3 V2 / S1 Professional
 * Firmware (AML2.0 builds, 2026 and later).
 *
 * On these builds AML is not a comment protocol - there is no `; First layer
 * print ...` or `; AML ...` parsing. The firmware exposes the mesh region
 * through the custom C-code:
 *
 *   C29 L<left> R<right> F<front> B<back> N<grid points>  (mesh inset + grid)
 *   G29 P1                                                (probe only that area)
 *   M420 S1                                               (activate leveling)
 *
 * `C29` stores the probe region in the runtime mesh settings (`meshSet`),
 * recomputes the grid spacing, invalidates the mesh and disables leveling.
 * With PROUI_EX the UBL grid positions are computed at runtime from these
 * values, so a following stock `G29 P1` probes just the model footprint.
 * `C29 A`, `C29 P1` and the bare command do nothing but print the settings.
 *
 * This injector is the free, built-in equivalent of the paid AML slicer
 * scripts: after the engine slice is final, it scans the first printable
 * layer for the real extrusion footprint, clamps it to the build volume plus
 * the configured margin and emits `C29 L.. R.. F.. B.. N9` together with the
 * probe/activation so the leveling sequence is always:
 *
 *   C29 (set region) -> G29 P1 (probe region) -> M420 S1 (activate leveling)
 */
internal object AdaptiveBedMeshInjector {
    /** Idempotency marker written with every injection. */
    const val MARKER = ";ENDERSLICER_AML"

    /** Firmware minimum density per axis (GRID_MIN). */
    const val GRID_MIN = 3

    /** Firmware maximum density per axis (GRID_LIMIT). */
    const val GRID_MAX = 9

    /** Default points per axis the user wants before fitting to the region. */
    const val DEFAULT_GRID_POINTS = 6

    /** Region edges larger than this are rejected; matches the C29 policy range. */
    private const val MAX_REGION_MM = 1000.0

    /** Comment suffix used by activation lines emitted by this injector. */
    const val ACTIVATION_COMMENT = " ; activate leveling"

    /**
     * Every layer-opening comment the three engines write: Cura numbers its
     * layers, and the PrusaSlicer family marks them - OrcaSlicer with the Bambu
     * envelope's "; CHANGE_LAYER" for a Bambu Lab vendor and the PrusaSlicer one
     * otherwise. Missing the Bambu spelling meant a Bambu-envelope slice never
     * found a first layer, so the AML block was silently never injected.
     */
    private val LAYER_MARKERS: List<String> = listOf(";LAYER:") +
        GcodeDialect.PRUSA.layerChangeMarkers +
        GcodeDialect.ORCA.layerChangeMarkers

    /**
     * Injects the AML block into [file]. Returns true when the block was
     * written, false when the file already carries the marker, has no
     * printable first layer, or the region collapses after clamping.
     */
    fun inject(
        file: File,
        envelope: PrinterEnvelope,
        marginMm: Double,
        maxPointsPerAxis: Int = DEFAULT_GRID_POINTS,
    ): Boolean {
        require(file.isFile && file.length() > 0L) { "Sliced G-code is unavailable" }
        require(marginMm.isFinite() && marginMm in 0.0..1000.0) { "AML margin is invalid" }
        require(maxPointsPerAxis in GRID_MIN..GRID_MAX) {
            "AML grid density must be $GRID_MIN..$GRID_MAX points per axis"
        }

        var sawMarker = false
        var probeIndex = -1
        var activationIndex = -1
        var hasActivationAfterProbe = false
        var lastHomeIndex = -1
        var layerStartIndex = -1
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var layerCount = 0
        var inFirstLayer = false

        // Pass 1: find the first-layer footprint and the anchoring line.
        var index = 0
        file.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val currentIndex = index
                index++
                val trimmed = line.trimStart()
                if (trimmed == MARKER) sawMarker = true

                val isLayerMarker = LAYER_MARKERS.any(trimmed::startsWith)
                if (isLayerMarker) {
                    layerCount++
                    if (layerCount == 1) {
                        layerStartIndex = currentIndex
                        inFirstLayer = true
                    } else if (inFirstLayer) {
                        inFirstLayer = false
                    }
                }

                if (inFirstLayer) {
                    val command = GcodeCommand.parse(line) ?: continue
                    if (command.opcode == "G0" || command.opcode == "G1") {
                        command.value('X')?.let { x ->
                            minX = minOf(minX, x)
                            maxX = maxOf(maxX, x)
                        }
                        command.value('Y')?.let { y ->
                            minY = minOf(minY, y)
                            maxY = maxOf(maxY, y)
                        }
                    }
                } else if (layerStartIndex < 0) {
                    val command = GcodeCommand.parse(line) ?: continue
                    when {
                        command.opcode == "G28" -> lastHomeIndex = currentIndex
                        isProbePhase1(command) && probeIndex < 0 -> probeIndex = currentIndex
                        command.opcode == "M420" && command.value('S') == 1.0
                            || command.opcode == "G29" && isActivation(command) -> {
                            if (probeIndex >= 0) hasActivationAfterProbe = true else activationIndex = currentIndex
                        }
                    }
                }
            }
        }

        if (sawMarker) return false
        if (!minX.isFinite() || !maxX.isFinite() || !minY.isFinite() || !maxY.isFinite()) return false

        // The mesh inset lives in the printer's bed coordinates (0..bed size).
        // Convert center-origin slices to absolute bed coordinates first.
        if (envelope.originAtCenter) {
            val shiftX = envelope.widthMm / 2.0
            val shiftY = envelope.depthMm / 2.0
            minX += shiftX; maxX += shiftX
            minY += shiftY; maxY += shiftY
        }
        val regionMinX = maxOf(0.0, minX - marginMm)
        val regionMaxX = minOf(envelope.widthMm, maxX + marginMm)
        val regionMinY = maxOf(0.0, minY - marginMm)
        val regionMaxY = minOf(envelope.depthMm, maxY + marginMm)
        if (regionMaxX < regionMinX || regionMaxY < regionMinY) return false
        if (regionMaxX - regionMinX > MAX_REGION_MM || regionMaxY - regionMinY > MAX_REGION_MM) return false

        // The firmware stores the mesh inset in millimetres as integers, so the
        // injected region is rounded to whole millimetres.
        val left = Math.round(regionMinX).toInt()
        val right = Math.round(regionMaxX).toInt()
        val front = Math.round(regionMinY).toInt()
        val back = Math.round(regionMaxY).toInt()
        if (right <= left || back <= front) return false

        val (pointsX, pointsY) = fitGrid(regionMaxX - regionMinX, regionMaxY - regionMinY, maxPointsPerAxis)
        val area = "C29 L$left R$right F$front B$back X$pointsX Y$pointsY ; AML mesh area"

        val blockBefore = buildString {
            appendLine(MARKER)
            appendLine(area)
            if (probeIndex < 0) {
                appendLine("G29 P1 ; probe only the model area")
                if (activationIndex < 0) appendLine("M420 S1$ACTIVATION_COMMENT")
            }
        }
        val anchorIndex = when {
            probeIndex >= 0 -> probeIndex
            activationIndex >= 0 -> activationIndex
            lastHomeIndex >= 0 -> lastHomeIndex + 1
            else -> layerStartIndex
        }
        if (anchorIndex < 0) return false
        // When the file already probes, leveling must still be activated after
        // the probe (C29 disabled it) unless the start script activates later.
        val needsPostProbeActivation = probeIndex >= 0 && !hasActivationAfterProbe

        // Pass 2: stream the file. The injected block is written at the anchor
        // BEFORE any C29 stripping so a stripped C29 line can never swallow it.
        val temporary = File(file.absoluteFile.parentFile, "${file.name}.aml.tmp")
        temporary.delete()
        try {
            file.bufferedReader().use { reader ->
                temporary.bufferedWriter().use { writer ->
                    var current = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        val command = if (current < layerStartIndex) GcodeCommand.parse(line) else null

                        if (current == anchorIndex) writer.write(blockBefore)
                        when {
                            current < layerStartIndex && command?.opcode == "C29" -> {
                                // The injector owns the AML mesh area in the start
                                // block; exactly one C29 may run, and it is ours.
                            }
                            current == probeIndex && needsPostProbeActivation -> {
                                writer.write(line)
                                writer.newLine()
                                writer.write("M420 S1$ACTIVATION_COMMENT")
                            }
                            else -> {
                                writer.write(line)
                                writer.newLine()
                            }
                        }
                        current++
                    }
                }
            }
            try {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.io.IOException) {
                if (!temporary.renameTo(file)) error("Unable to publish the AML G-code")
            }
        } finally {
            temporary.delete()
        }
        return true
    }

    /** True only for UBL phase-1 probing: `G29 P1` (also "G29P1", "G29 P1 C"). */
    private fun isProbePhase1(command: GcodeCommand.Parsed): Boolean {
        if (command.opcode != "G29") return false
        // P==1 distinguishes phase 1 from P2 (manual), P10+ etc.
        return command.value('P') == 1.0
    }

    /**
     * Fits the user-chosen per-axis density to the region: the longer axis gets
     * [maxPointsPerAxis], the shorter one is scaled down by its aspect ratio
     * (so spacing stays roughly equal on both axes), clamped to the firmware
     * limits (3..9).
     */
    private fun fitGrid(widthMm: Double, heightMm: Double, maxPointsPerAxis: Int): Pair<Int, Int> {
        val long = maxPointsPerAxis
        val ratio = minOf(widthMm, heightMm) / maxOf(widthMm, heightMm)
        val short = (Math.round(ratio * (long - 1)) + 1).toInt().coerceIn(GRID_MIN, GRID_MAX)
        return if (widthMm >= heightMm) long to short else short to long
    }

    /** True for `G29 A` (activate UBL) in any compact/unspaced spelling. */
    private fun isActivation(command: GcodeCommand.Parsed): Boolean =
        command.rawArguments.replace(" ", "").contains('A')
}
