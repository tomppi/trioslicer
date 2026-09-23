package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.engine.ArcOverhangEngineSettings
import com.tomppi.enderslicer.engine.MasonryWallsEngineSettings
import com.tomppi.enderslicer.engine.BrickWallEngineSettings
import com.tomppi.enderslicer.engine.MachineCuraKeys
import com.tomppi.enderslicer.engine.WaveOverhangEngineSettings
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.resolveEndGcode
import com.tomppi.enderslicer.model.resolveStartGcode
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.smartinfill.SmartInfillCuraContract
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.smartinfill.applyTo

internal object CuraSliceSettingsResolver {
    data class Result(
        val globalValues: Map<String, String>,
        val extruderValues: Map<String, String>,
        val modelValues: Map<String, String>,
        val expressionCount: Int,
        val passes: Int,
        val smartInfillModelValues: Map<Int, Map<String, String>> = emptyMap(),
    )

    fun resolve(
        profile: CuraEngineProfile,
        printer: PrinterDefinition,
        settings: SlicerSettings,
        startGcode: String,
        endGcode: String,
    ): Result {
        require(profile.usesProjectDefinitions) {
            "A complete Cura definition stack is required for dependency resolution"
        }

        val smartInfillPackage = SmartInfillRuntime.current()
        val effectiveSettings = smartInfillPackage?.applyTo(settings) ?: settings
        val effectivePrinter = printer.withSettings(effectiveSettings)
        val effectiveStartGcode = effectiveSettings.resolveStartGcode(startGcode)
        val effectiveEndGcode = effectiveSettings.resolveEndGcode(endGcode)
        val explicitDelta = CuraSettingDelta.explicitValues(effectiveSettings)
        val canonical = CuraSettingScopeResolver.canonicalize(profile, explicitDelta)

        val globalOverrides = linkedMapOf<String, String>().apply {
            putAll(canonical.global)
            MachineCuraKeys.values(effectivePrinter, effectiveStartGcode, effectiveEndGcode).forEach { (key, value) ->
                put(key, value)
            }
        }

        val extruderOverrides = linkedMapOf<String, String>().apply {
            putAll(canonical.extruder)
            put("extruder_nr", "0")
            put("machine_nozzle_size", effectivePrinter.nozzleSizeMm.toString())
            put("material_diameter", effectivePrinter.filamentDiameterMm.toString())
            if (smartInfillPackage != null) {
                SmartInfillCuraContract.smartInfillWidthKeys.forEach { key ->
                    put(key, smartInfillPackage.lineWidthMm.toString())
                }
            }
        }

        // Imported projects embed flattened definitions that predate the settings
        // the pinned engine reads unconditionally (wall_x_inset, support base
        // family; Settings::get exits the process on a missing key). Seed Cura's
        // own defaults; putIfAbsent keeps definition values and explicit edits.
        val rawResolved = resolveDefinitions(profile, globalOverrides, extruderOverrides).let { base ->
            base.copy(
                extruderValues = LinkedHashMap(base.extruderValues).apply {
                    ENGINE_DRIFT_DEFAULTS.forEach { (key, value) -> putIfAbsent(key, value) }
                },
            )
        }

        val parityExtruder = linkedMapOf<String, String>().apply {
            putAll(rawResolved.extruderValues)
            val coolMinimum = get("cool_min_temperature")?.toDoubleOrNull()
            if (coolMinimum != null && coolMinimum <= 0.0) {
                put("cool_min_temperature", requireNotNull(get("material_print_temperature")))
            }
            putAll(ArcOverhangEngineSettings.values(effectiveSettings))
            putAll(WaveOverhangEngineSettings.values(effectiveSettings))
            putAll(BrickWallEngineSettings.values(effectiveSettings))
            putAll(MasonryWallsEngineSettings.values(effectiveSettings))
            // CuraEngine drives the cooling fan from the Regular/Maximum Fan
            // Speed child keys (cool_fan_speed_min / cool_fan_speed_max) and
            // never reads the bare cool_fan_speed parent. The definitions
            // derive the children from the parent with a bare-reference
            // formula this resolver intentionally does not evaluate, so an
            // imported stack that carries only the parent would fall back to
            // the children's 100% definition default. Cascade the resolved
            // parent unless the stack set a child explicitly.
            val fanParent = rawResolved.extruderValues["cool_fan_speed"]
                ?: rawResolved.globalValues["cool_fan_speed"]
            if (fanParent?.toDoubleOrNull() != null) {
                for (child in listOf("cool_fan_speed_min", "cool_fan_speed_max")) {
                    if (child !in canonical.global && child !in canonical.extruder) {
                        put(child, fanParent)
                    }
                }
            }
        }

        CuraSettingDelta.requireResolvedMatch(
            settings = effectiveSettings,
            globalValues = rawResolved.globalValues,
            extruderValues = parityExtruder,
            modelValues = rawResolved.modelValues,
        )

        if (smartInfillPackage != null) {
            SmartInfillCuraContract.smartInfillWidthKeys.forEach { key ->
                val resolvedWidth = parityExtruder[key]
                    ?: rawResolved.globalValues[key]
                    ?: error("Resolved Cura setting is missing: $key")
                val value = resolvedWidth.toDoubleOrNull()
                    ?: error("Resolved Cura setting is not numeric: $key=$resolvedWidth")
                require(kotlin.math.abs(value - smartInfillPackage.lineWidthMm) <= 1e-7) {
                    "Resolved Cura width diverges from filaSim analysis: $key=$value, expected ${smartInfillPackage.lineWidthMm}"
                }
            }
        }

        val resolvedExtruder = linkedMapOf<String, String>().apply {
            putAll(parityExtruder)
        }
        validateResolvedSettings(rawResolved.globalValues, resolvedExtruder)

        val smartInfillModelValues = smartInfillPackage
            ?.modifiers
            ?.map { it.densityPercent }
            ?.distinct()
            ?.sorted()
            ?.associateWith { densityPercent ->
                val expectedPattern = SmartInfillCuraContract.modifierPattern(
                    smartInfillPackage,
                    densityPercent,
                )
                val modifierOverrides = LinkedHashMap(extruderOverrides).apply {
                    put("infill_sparse_density", densityPercent.toString())
                    put("infill_pattern", expectedPattern)
                }
                val modifierResolved = resolveDefinitions(profile, globalOverrides, modifierOverrides)
                val actualDensity = modifierResolved.extruderValues["infill_sparse_density"]
                    ?: modifierResolved.modelValues["infill_sparse_density"]
                    ?: error("Resolved Smart Infill modifier density is missing")
                require(actualDensity.toDoubleOrNull() == densityPercent.toDouble()) {
                    "Resolved Smart Infill density diverged: requested $densityPercent, resolved $actualDensity"
                }
                val actualPattern = modifierResolved.extruderValues["infill_pattern"]
                    ?: modifierResolved.modelValues["infill_pattern"]
                    ?: error("Resolved Smart Infill modifier pattern is missing")
                require(actualPattern == expectedPattern) {
                    "Resolved Smart Infill pattern diverged: requested $expectedPattern, resolved $actualPattern"
                }
                SmartInfillCuraContract.neutralizeModifierShell(modifierResolved.modelValues)
            }
            .orEmpty()

        return Result(
            globalValues = rawResolved.globalValues,
            extruderValues = resolvedExtruder,
            modelValues = rawResolved.modelValues,
            expressionCount = rawResolved.expressionCount,
            passes = rawResolved.passes,
            smartInfillModelValues = smartInfillModelValues,
        )
    }

    private fun resolveDefinitions(
        profile: CuraEngineProfile,
        globalOverrides: Map<String, String>,
        extruderOverrides: Map<String, String>,
    ): CuraDefinitionResolver.Result = CuraDefinitionResolver.resolve(
        definitionFiles = profile.definitionFiles,
        machineDefinitionFileName = requireNotNull(profile.machineDefinitionFileName),
        extruderDefinitionFileName = requireNotNull(profile.extruderDefinitionFileName),
        globalOverrides = globalOverrides,
        extruderOverrides = extruderOverrides,
    )

    private fun validateResolvedSettings(
        global: Map<String, String>,
        extruder: Map<String, String>,
    ) {
        val unresolved = (global.entries + extruder.entries)
            .filter { (_, value) -> value.trim().startsWith("=") }
            .map { it.key }
        require(unresolved.isEmpty()) {
            "Unresolved Cura formulas remain: ${unresolved.take(10).joinToString()}"
        }

        fun number(values: Map<String, String>, key: String): Double {
            val raw = values[key] ?: error("Resolved Cura setting is missing: $key")
            val value = raw.toDoubleOrNull() ?: error("Resolved Cura setting is not numeric: $key=$raw")
            require(value.isFinite()) { "Resolved Cura setting is not finite: $key=$raw" }
            return value
        }

        fun range(values: Map<String, String>, key: String, minimum: Double, maximum: Double) {
            val value = number(values, key)
            require(value in minimum..maximum) {
                "Resolved Cura setting is outside its safe range: $key=$value, expected $minimum..$maximum"
            }
        }

        fun option(values: Map<String, String>, key: String, allowed: Set<String>) {
            val value = values[key] ?: error("Resolved Cura setting is missing: $key")
            require(value in allowed) {
                "Resolved Cura setting is invalid: $key=$value, expected one of ${allowed.joinToString()}"
            }
        }

        range(global, "machine_width", 1.0, 2000.0)
        range(global, "machine_depth", 1.0, 2000.0)
        range(global, "machine_height", 1.0, 2000.0)
        fun anyNumber(key: String): Double = number(if (key in global) global else extruder, key)
        fun anyRange(key: String, minimum: Double, maximum: Double) {
            val value = anyNumber(key)
            require(value in minimum..maximum) {
                "Resolved Cura setting is outside its safe range: $key=$value, expected $minimum..$maximum"
            }
        }
        fun optionalAnyRange(key: String, minimum: Double, maximum: Double) {
            if (key !in global && key !in extruder) return
            anyRange(key, minimum, maximum)
        }

        range(global, "layer_height", 0.01, 5.0)
        anyRange("adaptive_layer_height_variation", 0.0, 5.0)
        anyRange("adaptive_layer_height_variation_step", 0.001, 5.0)
        anyRange("adaptive_layer_height_threshold", 0.0, 1.0)
        val adaptiveEnabled = (global["adaptive_layer_height_enabled"]
            ?: extruder["adaptive_layer_height_enabled"])?.toBooleanStrictOrNull() == true
        if (adaptiveEnabled) {
            val nominal = number(global, "layer_height")
            val variation = anyNumber("adaptive_layer_height_variation")
            val step = anyNumber("adaptive_layer_height_variation_step")
            require(variation < nominal) {
                "Adaptive layer variation must be smaller than the nominal layer height"
            }
            require(step <= variation.coerceAtLeast(0.001)) {
                "Adaptive layer variation step must not exceed the total variation"
            }
        }
        option(extruder, "slicing_tolerance", setOf("middle", "exclusive", "inclusive"))
        range(extruder, "machine_nozzle_size", 0.05, 5.0)
        range(extruder, "material_diameter", 0.5, 5.0)
        range(extruder, "line_width", 0.01, 5.0)
        range(extruder, "wall_line_count", 0.0, 1000.0)
        range(extruder, "wall_thickness", 0.0, 100.0)
        range(extruder, "top_layers", 0.0, 1000000.0)
        range(extruder, "bottom_layers", 0.0, 1000000.0)
        range(extruder, "top_bottom_thickness", 0.0, 2000.0)
        range(extruder, "initial_bottom_layers", 0.0, 1000000.0)
        range(extruder, "hole_xy_offset", -10.0, 10.0)
        range(extruder, "xy_offset_layer_0", -10.0, 10.0)
        option(extruder, "z_seam_type", setOf("back", "shortest", "random", "sharpest_corner"))
        option(
            extruder,
            "z_seam_corner",
            setOf(
                "z_seam_corner_none",
                "z_seam_corner_inner",
                "z_seam_corner_outer",
                "z_seam_corner_any",
                "z_seam_corner_weighted",
            ),
        )
        range(extruder, "z_seam_x", -2000.0, 2000.0)
        range(extruder, "z_seam_y", -2000.0, 2000.0)
        range(extruder, "infill_sparse_density", 0.0, 100.0)
        range(extruder, "material_print_temperature", 150.0, 500.0)
        range(extruder, "material_print_temperature_layer_0", 150.0, 500.0)
        range(extruder, "cool_min_temperature", 150.0, 500.0)
        range(global, "material_bed_temperature", 0.0, 200.0)
        anyRange("build_volume_temperature", -273.15, 285.0)
        anyRange("material_standby_temperature", -273.15, 500.0)
        optionalAnyRange("material_density", 0.0, 100.0)
        anyRange("material_adhesion_tendency", 0.0, 10.0)
        anyRange("material_surface_energy", 0.0, 100.0)
        anyRange("extruders_enabled_count", 1.0, 16.0)
        range(extruder, "material_flow", 1.0, 300.0)
        range(extruder, "cool_fan_speed", 0.0, 100.0)
        range(extruder, "cool_fan_speed_0", 0.0, 100.0)
        range(extruder, "cool_fan_full_layer", 0.0, 1000000.0)
        listOf(
            "speed_print",
            "speed_wall",
            "speed_wall_0",
            "speed_wall_x",
            "speed_infill",
            "speed_topbottom",
            "speed_travel",
            "speed_layer_0",
            "speed_support",
            "speed_support_interface",
            "speed_ironing",
        ).forEach { key -> range(extruder, key, 0.1, 1000.0) }
        range(extruder, "support_infill_rate", 0.0, 100.0)
        range(extruder, "support_interface_density", 0.0, 100.0)
        range(extruder, "support_interface_height", 0.0, 100.0)
        range(extruder, "support_z_distance", 0.0, 20.0)
        range(extruder, "support_xy_distance", 0.0, 20.0)
        range(extruder, "retraction_amount", 0.0, 100.0)
        range(extruder, "retraction_speed", 0.0, 1000.0)
        range(extruder, "retraction_min_travel", 0.0, 1000.0)
        range(extruder, "travel_avoid_distance", 0.0, 100.0)
        range(extruder, "retraction_hop", 0.0, 100.0)
        range(extruder, "coasting_volume", 0.0, 1000.0)
        range(extruder, "coasting_min_volume", 0.0, 100000.0)
        range(extruder, "coasting_speed", 0.0001, 1000.0)
        if (extruder["coasting_enable"]?.toBooleanStrictOrNull() == true) {
            require(number(extruder, "coasting_min_volume") >= number(extruder, "coasting_volume")) {
                "Minimum volume before coasting must be at least the coasting volume"
            }
        }
        range(extruder, "skirt_line_count", 0.0, 1000.0)
        range(extruder, "brim_width", 0.0, 100.0)
        range(extruder, "raft_margin", 0.0, 100.0)
        range(extruder, "ironing_flow", 0.0, 100.0)
    }

    private val ENGINE_DRIFT_DEFAULTS = linkedMapOf(
        "wall_x_inset" to "0",
        "support_base_inside_width" to "0",
        "support_base_outside_width" to "0",
        "support_outer_brim_enable" to "false",
        "support_inside_base_curve_magnitude" to "4",
        "support_inside_base_height" to "0",
        "support_outside_base_curve_magnitude" to "4",
        "support_outside_base_height" to "0",
    )
}