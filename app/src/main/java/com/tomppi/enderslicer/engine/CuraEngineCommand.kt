package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.ExtraSettingSpec
import com.tomppi.enderslicer.model.ExtraSettingValidation
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.resolveEndGcode
import com.tomppi.enderslicer.model.resolveStartGcode
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile
import com.tomppi.enderslicer.profile.CuraSettingDelta
import com.tomppi.enderslicer.smartinfill.SmartInfillCuraContract
import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.smartinfill.applyTo
import com.tomppi.enderslicer.smartinfill.requireValidBinaryStl
import com.tomppi.enderslicer.supportpaint.SupportPaintModifier
import java.io.File

object CuraEngineCommand {
    fun buildResolved(
        executablePath: String,
        definitionsDirectory: String,
        resolvedSettingsPath: String,
        outputPath: String,
        extraSettings: Map<String, String> = emptyMap(),
        catalog: List<ExtraSettingSpec> = emptyList(),
        threadCount: Int = recommendedThreadCount(),
    ): List<String> {
        require(threadCount in 1..32) { "Invalid CuraEngine thread count: $threadCount" }
        listOf(executablePath, definitionsDirectory, resolvedSettingsPath, outputPath)
            .forEach(::requireSafeArgument)
        return listOf(
            executablePath,
            "slice",
            "-m$threadCount",
            // Straight to a log line per whole percent, which the runner parses.
            "-p",
            "-d",
            definitionsDirectory,
            "-r",
            resolvedSettingsPath,
            "-o",
            outputPath,
        ) + extraArguments(extraSettings, catalog)
    }

    /**
     * The machine's end script as CuraEngine writes it into the G-code: the caller's
     * custom end G-code when it is enabled, marked so the event processor can find
     * where the script starts.
     *
     * The runner hands the post-processor the same value this returns, because the
     * M220/M221 restores have to land before that script and searching for the
     * wrong one puts them at the ";End of Gcode" comment instead.
     */
    internal fun machineEndGcodeFor(settings: SlicerSettings, endGcode: String): String =
        NonPlanarPreparation.markMachineEndGcode(settings.resolveEndGcode(endGcode))

    fun build(
        executablePath: String,
        definitionsDirectory: String,
        machineDefinitionPath: String,
        extruderDefinitionPath: String,
        modelPath: String,
        outputPath: String,
        printer: PrinterDefinition,
        settings: SlicerSettings,
        startGcode: String,
        endGcode: String,
        profile: CuraEngineProfile? = null,
        smartInfillModifiers: List<SmartInfillModifier> = emptyList(),
        adaptiveWallModifiers: List<AdaptiveWallModifier> = emptyList(),
        supportPaintModifiers: List<SupportPaintModifier> = emptyList(),
        extraSettings: Map<String, String> = emptyMap(),
        catalog: List<ExtraSettingSpec> = emptyList(),
        threadCount: Int = recommendedThreadCount(),
    ): List<String> {
        require(profile == null) {
            "Imported Cura configurations must be dependency-resolved before command generation"
        }
        require(threadCount in 1..32) { "Invalid CuraEngine thread count: $threadCount" }
        listOf(
            executablePath,
            definitionsDirectory,
            machineDefinitionPath,
            extruderDefinitionPath,
            modelPath,
            outputPath,
        ).forEach(::requireSafeArgument)

        val workspace = File(outputPath).parentFile
            ?: error("CuraEngine output path has no parent workspace")
        val analyzedSource = File(modelPath)
        val activeSmartInfill = SmartInfillRuntime.current()
        activeSmartInfill?.requireMatchesSource(analyzedSource)
        val effectiveSmartInfillModifiers = if (smartInfillModifiers.isNotEmpty()) {
            smartInfillModifiers
        } else {
            activeSmartInfill?.stageModifiers(workspace, analyzedSource).orEmpty()
        }
        effectiveSmartInfillModifiers.forEach { modifier ->
            requireSafeArgument(modifier.file.absolutePath)
            requireValidBinaryStl(modifier.file, Int.MAX_VALUE)
        }
        adaptiveWallModifiers.forEach { modifier ->
            requireSafeArgument(modifier.file.absolutePath)
            requireValidBinaryStl(modifier.file, Int.MAX_VALUE)
        }
        supportPaintModifiers.forEach { modifier ->
            requireSafeArgument(modifier.file.absolutePath)
            requireValidBinaryStl(modifier.file, Int.MAX_VALUE)
        }

        val effectiveSettings = SmartInfillRuntime.current()?.applyTo(settings) ?: settings
        val effectivePrinter = printer.withSettings(effectiveSettings)
        val printerEnvelope = PrinterEnvelope.from(effectivePrinter)
        analyzedSource.takeIf(File::isFile)?.let(printerEnvelope::requireBinaryStlFits)
        // The label decides what the failure names: without one every volume
        // reads as "Model vertex N", which sent a whole debugging session after
        // the wrong file.
        effectiveSmartInfillModifiers.forEach { modifier ->
            printerEnvelope.requireBinaryStlFits(
                modifier.file,
                label = "Smart Infill ${modifier.densityPercent}% modifier",
            )
        }
        adaptiveWallModifiers.forEach { modifier ->
            printerEnvelope.requireBinaryStlFits(
                modifier.file,
                label = "Adaptive-wall modifier " + modifier.file.name,
            )
        }
        supportPaintModifiers.forEach { modifier ->
                printerEnvelope.requireBinaryStlFits(modifier.file, label = "Support-paint modifier " + modifier.file.name)
            }

        NonPlanarPreparation.prepare(
            modelFile = analyzedSource,
            workspace = workspace,
            printerEnvelope = printerEnvelope,
            layerHeightMm = effectiveSettings.layerHeightMm,
            nozzleDiameterMm = effectivePrinter.nozzleSizeMm,
            smartInfillModifiers = effectiveSmartInfillModifiers,
            adaptiveWallModifiers = adaptiveWallModifiers,
            supportPaintModifiers = supportPaintModifiers,
        )

        val effectiveStartGcode = effectiveSettings.resolveStartGcode(startGcode)
        val effectiveEndGcode = machineEndGcodeFor(effectiveSettings, endGcode)
        val engineOffsetX = if (effectivePrinter.originAtCenter) 0.0 else -effectivePrinter.widthMm / 2.0
        val engineOffsetY = if (effectivePrinter.originAtCenter) 0.0 else -effectivePrinter.depthMm / 2.0
        requireSafeArgument(effectiveStartGcode)
        requireSafeArgument(effectiveEndGcode)

        val command = mutableListOf(
            executablePath,
            "slice",
            "-m$threadCount",
            "-p",
            "-d",
            definitionsDirectory,
            "--force-read-parent",
            "-j",
            machineDefinitionPath,
            "--end-force-read",
        )

        fun setting(key: String, value: Any) {
            val normalized = when (value) {
                is Boolean -> value.toString().lowercase()
                else -> value.toString()
            }
            requireSafeArgument(key)
            requireSafeArgument(normalized)
            command += "-s"
            command += "$key=$normalized"
        }

        fun applySmartInfillWidths() {
            val width = activeSmartInfill?.lineWidthMm ?: return
            SmartInfillCuraContract.smartInfillWidthKeys.forEach { key -> setting(key, width) }
        }

        fun applyStandaloneSettings() {
            CuraSettingDelta.standaloneValues(effectiveSettings).forEach { (key, value) -> setting(key, value) }
            ArcOverhangEngineSettings.values(effectiveSettings).forEach { (key, value) -> setting(key, value) }
            WaveOverhangEngineSettings.values(effectiveSettings).forEach { (key, value) -> setting(key, value) }
            BrickWallEngineSettings.values(effectiveSettings).forEach { (key, value) -> setting(key, value) }
            MasonryWallsEngineSettings.values(effectiveSettings).forEach { (key, value) -> setting(key, value) }
            if (effectiveSettings.arcOverhangEnabled || effectiveSettings.waveOverhangEnabled || effectiveSettings.brickWallEnabled  ) {
                // Bridge detection classifies unsupported bottom skins and the
                // layer below, which is exactly what the arc/wave/brick-wall and
                // bead-angle overhang generators build on. The pinned definitions
                // default this to false, and without it the overhang features
                // never trigger.
                setting("bridge_settings_enabled", true)
            }
            applySmartInfillWidths()
        }

        fun applySmartInfillRegion(densityPercent: Double, curaPattern: String) {
            require(densityPercent in 0.0..100.0) { "Invalid Smart Infill density: $densityPercent" }
            val densityArgument: Number = if (densityPercent % 1.0 == 0.0) densityPercent.toInt() else densityPercent
            setting("infill_sparse_density", densityArgument)

            val lineWidth = activeSmartInfill?.lineWidthMm ?: effectiveSettings.lineWidthMm
            val pattern = curaPattern.lowercase()
            // fdmprinter.def.json's own infill_line_distance factors: the
            // engine spaces its lines from infill_line_distance alone and never
            // re-derives it from the density, so a wrong factor is silent.
            // Honeycomb and octagon are the only patterns whose factor depends
            // on the density itself - their lines overlap, so the spacing has
            // to close up as the density rises.
            val patternFactor = when (pattern) {
                "grid" -> 2.0
                "triangles", "trihexagon", "cubic", "cubicsubdiv" -> 3.0
                "tetrahedral", "quarter_cubic" -> 2.0
                "cross", "cross_3d" -> 1.0
                "lightning" -> 1.6
                "honeycomb", "octagon" -> 4.0 / 3.0 - densityPercent / 300.0
                else -> 1.0
            }
            val regionalLineDistance = if (densityPercent <= 0.0) {
                0.0
            } else {
                lineWidth * 100.0 / densityPercent * patternFactor
            }
            val overlapPercent = if (densityPercent < 95.0 && pattern != "concentric") 10.0 else 0.0
            val overlapMm = if (overlapPercent > 0.0) {
                0.5 * (lineWidth + lineWidth) * overlapPercent / 100.0
            } else {
                0.0
            }
            setting("infill_pattern", pattern)
            applySmartInfillWidths()
            setting("infill_line_distance", regionalLineDistance)
            setting("infill_overlap", overlapPercent)
            setting("infill_overlap_mm", overlapMm)
        }

        fun neutralizeSmartInfillModifierShell() {
            SmartInfillCuraContract.modifierShellNeutralValues.forEach { (key, value) -> setting(key, value) }
        }

        fun prepareMeshLoad() {
            setting("center_object", false)
            setting("mesh_rotation_matrix", "[[1,0,0],[0,1,0],[0,0,1]]")
        }

        fun positionLoadedMesh() {
            setting("mesh_position_x", engineOffsetX)
            setting("mesh_position_y", engineOffsetY)
            setting("mesh_position_z", 0)
        }

        MachineCuraKeys.values(effectivePrinter, effectiveStartGcode, effectiveEndGcode).forEach { (key, value) ->
            setting(key, value)
        }
        applyStandaloneSettings()

        command += listOf(
            "-e0",
            "--force-read-parent",
            "-j",
            machineDefinitionPath,
            "-j",
            extruderDefinitionPath,
            "--end-force-read",
        )

        applyStandaloneSettings()
        setting("extruder_nr", 0)
        setting("machine_nozzle_size", effectivePrinter.nozzleSizeMm)
        setting("material_diameter", effectivePrinter.filamentDiameterMm)

        // The user's interface thickness wins here: the standalone transport
        // used to overwrite the value applied above with layerHeight * 4, which
        // made the UI field do nothing on this transport.
        val interfaceHeight = effectiveSettings.supportInterfaceHeightMm
        val density = effectiveSettings.supportInterfaceDensityPercent.coerceIn(0.0, 100.0)
        val lineDistance = if (density <= 0.0) 0.0 else effectiveSettings.lineWidthMm * 100.0 / density * 2.0
        setting("support_interface_extruder_nr", 0)
        setting("support_roof_extruder_nr", 0)
        setting("support_bottom_extruder_nr", 0)
        setting("support_interface_height", interfaceHeight)
        setting("support_roof_height", interfaceHeight)
        setting("support_bottom_height", interfaceHeight)
        setting("support_interface_pattern", "grid")
        // The support generator reads the per-extruder roof/bottom keys, never
        // the support_interface_* parents; mirror the hardcoded grid pattern
        // into them or the engine falls back to their definition defaults
        // ("concentric") and renders a different interface than requested.
        setting("support_roof_pattern", "grid")
        setting("support_bottom_pattern", "grid")
        setting("support_roof_line_width", effectiveSettings.lineWidthMm)
        setting("support_bottom_line_width", effectiveSettings.lineWidthMm)
        setting("support_roof_line_distance", lineDistance)
        setting("support_bottom_line_distance", lineDistance)

        prepareMeshLoad()
        command += listOf("-l", modelPath)
        positionLoadedMesh()
        setting("extruder_nr", 0)
        val basePattern = activeSmartInfill
            ?.let(SmartInfillCuraContract::basePattern)
            ?: effectiveSettings.infillPattern.lowercase()
        applySmartInfillRegion(
            activeSmartInfill?.baseDensityPercent ?: effectiveSettings.infillDensityPercent,
            basePattern,
        )
        setting("infill_mesh", false)
        setting("support_mesh", false)
        setting("anti_overhang_mesh", false)
        setting("cutting_mesh", false)

        effectiveSmartInfillModifiers
            .sortedBy(SmartInfillModifier::densityPercent)
            .forEachIndexed { index, modifier ->
                prepareMeshLoad()
                command += listOf("-l", modifier.file.absolutePath)
                positionLoadedMesh()
                setting("extruder_nr", 0)
                setting("infill_mesh", true)
                setting("infill_mesh_order", index + 1)
                val modifierPattern = activeSmartInfill
                    ?.let { SmartInfillCuraContract.modifierPattern(it, modifier.densityPercent) }
                    ?: effectiveSettings.infillPattern.lowercase()
                applySmartInfillRegion(modifier.densityPercent.toDouble(), modifierPattern)
                neutralizeSmartInfillModifierShell()
                setting("support_mesh", false)
                setting("anti_overhang_mesh", false)
                setting("cutting_mesh", false)
            }

        adaptiveWallModifiers.forEachIndexed { index, modifier ->
            prepareMeshLoad()
            command += listOf("-l", modifier.file.absolutePath)
            positionLoadedMesh()
            setting("extruder_nr", 0)
            setting("infill_mesh", true)
            setting("infill_mesh_order", effectiveSmartInfillModifiers.size + index + 1)
            setting("wall_line_count", modifier.wallLineCount)
            setting("wall_0_material_flow", modifier.wallFlowPercent)
            setting("wall_x_material_flow", modifier.wallFlowPercent)
            applySmartInfillRegion(
                activeSmartInfill?.baseDensityPercent ?: effectiveSettings.infillDensityPercent,
                basePattern,
            )
            setting("support_mesh", false)
            setting("anti_overhang_mesh", false)
            setting("cutting_mesh", false)
        }

        supportPaintModifiers.forEach { modifier ->
            prepareMeshLoad()
            command += listOf("-l", modifier.file.absolutePath)
            positionLoadedMesh()
            setting("extruder_nr", 0)
            // The painted prisms overlap heavily by design; unioning them is the
            // dominant slice cost and unnecessary: each prism is already a closed
            // volume and the support generator projects all support meshes
            // together regardless of union state.
            setting("meshfix_union_all", false)
            setting("support_mesh", !modifier.isBlocker)
            setting("anti_overhang_mesh", modifier.isBlocker)
            setting("infill_mesh", false)
            setting("cutting_mesh", false)
        }

        command += listOf("-o", outputPath)
        // User-added extras are applied strictly after every app-controlled setting
        // so they win over defaults and app values alike (last wins).
        command += extraArguments(extraSettings, catalog)
        return command
    }

    /**
     * Extra settings as `-s key=value` arguments, sorted for a stable log.
     *
     * CuraEngine applies `-s` values in argv order, so appending them after the
     * settings source (-r resolved JSON, or every -s the standalone transport
     * emits) makes the user's value the last-wins override on both transports.
     * Values are validated here: a blank or malformed entry otherwise reaches the
     * engine as an opaque argument and fails the whole slice with a generic
     * engine error that never names the offending key. [catalog] supplies the
     * engine's value types (numeric keys) when the caller has them.
     */
    private fun extraArguments(
        extraSettings: Map<String, String>,
        catalog: List<ExtraSettingSpec>,
    ): List<String> {
        val specs = catalog.associateBy(ExtraSettingSpec::key)
        return extraSettings.toSortedMap().flatMap { (key, value) ->
            require(ExtraSettingValidation.isValidKey(key)) { "Extra setting key \"$key\" is invalid" }
            ExtraSettingValidation.requireValid(key, value, specs[key])
            listOf("-s", "$key=$value")
        }
    }

    private fun recommendedThreadCount(): Int = CpuTopology.detect().recommendedThreadCount

    internal fun recommendedThreadCount(
        availableProcessors: Int,
        hardwareProcessors: Int,
    ): Int = CpuTopology.recommendedThreadCount(availableProcessors, hardwareProcessors)

    private fun requireSafeArgument(value: String) {
        require('\u0000' !in value) { "CuraEngine argument contains a NUL character" }
    }
}