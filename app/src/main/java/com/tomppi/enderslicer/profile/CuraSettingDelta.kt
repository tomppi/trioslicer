package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.smartinfill.applyTo
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Converts EnderSlicer's persisted user edits into Cura input values.
 *
 * The imported Cura project remains the immutable baseline. Only keys listed in
 * overriddenSettingKeys are added to that baseline. Values owned by Cura
 * formulas are deliberately not copied here: changing an upstream input must
 * allow Cura's original dependency chain to calculate them again.
 */
internal object CuraSettingDelta {
    fun explicitValues(settings: SlicerSettings): LinkedHashMap<String, String> =
        values(settings, includeAll = false)

    /** Used only when no Cura profile/project exists and the app is the baseline. */
    fun standaloneValues(settings: SlicerSettings): LinkedHashMap<String, String> =
        values(settings, includeAll = true)

    private fun values(
        inputSettings: SlicerSettings,
        includeAll: Boolean,
    ): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        val settings = SmartInfillRuntime.current()?.applyTo(inputSettings) ?: inputSettings
        fun include(appKey: String): Boolean = includeAll || settings.isOverridden(appKey)
        fun putValue(appKey: String, curaKey: String, value: Any) {
            if (!include(appKey)) return
            put(
                curaKey,
                when (value) {
                    is Boolean -> value.toString().lowercase()
                    else -> value.toString()
                },
            )
        }

        putValue(SlicerSettings.Keys.LAYER_HEIGHT, "layer_height", settings.layerHeightMm)
        putValue(SlicerSettings.Keys.INITIAL_LAYER_HEIGHT, "layer_height_0", settings.initialLayerHeightMm)
        putValue(
            SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED,
            "adaptive_layer_height_enabled",
            settings.adaptiveLayerHeightEnabled,
        )
        putValue(
            SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION,
            "adaptive_layer_height_variation",
            settings.adaptiveLayerHeightVariationMm,
        )
        putValue(
            SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP,
            "adaptive_layer_height_variation_step",
            settings.adaptiveLayerHeightVariationStepMm,
        )
        putValue(
            SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD,
            "adaptive_layer_height_threshold",
            settings.adaptiveLayerHeightThreshold,
        )
        putValue(SlicerSettings.Keys.LINE_WIDTH, "line_width", settings.lineWidthMm)
        // CuraEngine reads the per-feature resolved line-width children
        // (wall_line_width_0/x, skin_line_width, infill_line_width,
        // support_line_width, skirt_brim_line_width, wall_transition_length,
        // brim_inside_margin = line_width * 4, raft surface/interface = line
        // width and twice it) instead of the bare line_width parent.
        putValue(SlicerSettings.Keys.LINE_WIDTH, "wall_line_width_0", settings.lineWidthMm)
        putValue(SlicerSettings.Keys.LINE_WIDTH, "wall_line_width_x", settings.lineWidthMm)
        putValue(SlicerSettings.Keys.LINE_WIDTH, "skin_line_width", settings.lineWidthMm)
        putValue(SlicerSettings.Keys.LINE_WIDTH, "infill_line_width", settings.lineWidthMm)
        putValue(SlicerSettings.Keys.LINE_WIDTH, "support_line_width", settings.lineWidthMm)
        putValue(SlicerSettings.Keys.LINE_WIDTH, "support_interface_line_width", settings.lineWidthMm)
        putValue(SlicerSettings.Keys.LINE_WIDTH, "skirt_brim_line_width", settings.lineWidthMm)
        putValue(SlicerSettings.Keys.LINE_WIDTH, "wall_transition_length", settings.lineWidthMm)
        putValue(SlicerSettings.Keys.LINE_WIDTH, "brim_inside_margin", settings.lineWidthMm * 4.0)
        putValue(SlicerSettings.Keys.LINE_WIDTH, "raft_surface_line_width", settings.lineWidthMm)
        putValue(SlicerSettings.Keys.LINE_WIDTH, "raft_interface_line_width", settings.lineWidthMm * 2.0)
        putValue(SlicerSettings.Keys.SLICING_TOLERANCE, "slicing_tolerance", settings.slicingTolerance)
        putValue(SlicerSettings.Keys.WALL_LINE_COUNT, "wall_line_count", settings.wallLineCount)
        putValue(SlicerSettings.Keys.INITIAL_LAYER_INSET_DIRECTION, "initial_layer_inset_direction", settings.initialLayerInsetDirection)
        putValue(SlicerSettings.Keys.WALL_THICKNESS, "wall_thickness", settings.wallThicknessMm)
        putValue(SlicerSettings.Keys.TOP_LAYERS, "top_layers", settings.topLayers)
        // The conformal shells replace the top N planar layers, so the slice
        // must carry at least that many solid top layers as source material.
        NonPlanarRuntime.snapshot()?.let { active ->
            put(
                "top_layers",
                max(settings.topLayers, active.settings.conformalShellLayers).toString(),
            )
        }
        putValue(SlicerSettings.Keys.BOTTOM_LAYERS, "bottom_layers", settings.bottomLayers)
        putValue(SlicerSettings.Keys.TOP_BOTTOM_THICKNESS, "top_bottom_thickness", settings.topBottomThicknessMm)
        putValue(SlicerSettings.Keys.TOP_SKIN_ANGLES, "skin_angles", normalizeSkinAngles(settings.topSkinAngles))
        putValue(SlicerSettings.Keys.TOP_BOTTOM_PATTERN, "top_bottom_pattern", settings.topBottomPattern)
        putValue(SlicerSettings.Keys.TOP_BOTTOM_PATTERN, "top_bottom_pattern_0", settings.topBottomPattern)
        putValue(SlicerSettings.Keys.TOP_BOTTOM_PATTERN, "roofing_pattern", settings.topBottomPattern)
        putValue(SlicerSettings.Keys.TOP_BOTTOM_PATTERN, "flooring_pattern", settings.topBottomPattern)
        putValue(SlicerSettings.Keys.ROOFING_EXPANSION, "roofing_expansion", settings.roofingExpansionMm)
        putValue(SlicerSettings.Keys.TOP_BOTTOM_SKIN_MERGE_DISTANCE, "top_bottom_skin_merge_distance", settings.topBottomSkinMergeDistanceMm)
        putValue(SlicerSettings.Keys.SKIN_SUPPORT_ENABLED, "skin_support", settings.skinSupportEnabled)
        putValue(SlicerSettings.Keys.SKIN_SUPPORT_DENSITY, "skin_support_density", settings.skinSupportDensityPercent)
        putValue(SlicerSettings.Keys.SKIN_SUPPORT_SPEED, "skin_support_speed", settings.skinSupportSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.SKIN_SUPPORT_MATERIAL_FLOW, "skin_support_material_flow", settings.skinSupportMaterialFlowPercent)
        putValue(SlicerSettings.Keys.SKIN_SUPPORT_FAN_SPEED, "skin_support_fan_speed", settings.skinSupportFanSpeedPercent)
        putValue(SlicerSettings.Keys.INITIAL_BOTTOM_LAYERS, "initial_bottom_layers", settings.initialBottomLayers)
        putValue(SlicerSettings.Keys.HOLE_HORIZONTAL_EXPANSION, "hole_xy_offset", settings.holeHorizontalExpansionMm)
        putValue(
            SlicerSettings.Keys.INITIAL_LAYER_HORIZONTAL_EXPANSION,
            "xy_offset_layer_0",
            settings.initialLayerHorizontalExpansionMm,
        )
        putValue(SlicerSettings.Keys.Z_SEAM_TYPE, "z_seam_type", settings.zSeamType)
        putValue(SlicerSettings.Keys.Z_SEAM_X, "z_seam_x", settings.zSeamXmm)
        putValue(SlicerSettings.Keys.Z_SEAM_Y, "z_seam_y", settings.zSeamYmm)
        putValue(SlicerSettings.Keys.Z_SEAM_RELATIVE, "z_seam_relative", settings.zSeamRelative)
        putValue(SlicerSettings.Keys.Z_SEAM_CORNER, "z_seam_corner", settings.zSeamCorner)
        putValue(SlicerSettings.Keys.INFILL_DENSITY, "infill_sparse_density", settings.infillDensityPercent)
        putValue(SlicerSettings.Keys.INFILL_PATTERN, "infill_pattern", settings.infillPattern)
        putValue(SlicerSettings.Keys.ZIG_ZAG_CONNECT_INFILL, "zig_zaggify_infill", settings.zigZagConnectInfill)
        putValue(SlicerSettings.Keys.INFILL_START_END_PREFERENCE, "infill_start_end_preference", settings.infillStartEndPreference)
        putValue(SlicerSettings.Keys.INFILL_MOVE_INWARDS_LENGTH, "infill_move_inwards_length", settings.infillMoveInwardsLengthMm)
        putValue(SlicerSettings.Keys.INFILL_MOVE_INWARDS_LENGTH, "infill_start_move_inwards_length", settings.infillMoveInwardsLengthMm)
        putValue(SlicerSettings.Keys.INFILL_MOVE_INWARDS_LENGTH, "infill_end_move_inwards_length", settings.infillMoveInwardsLengthMm)
        putValue(SlicerSettings.Keys.MINIMUM_INFILL_LINE_LENGTH, "minimum_infill_line_length", settings.minimumInfillLineLengthMm)
        putValue(SlicerSettings.Keys.BRIDGE_INTERLACE_LINES, "bridge_interlace_lines", settings.bridgeInterlaceLines)

        putValue(SlicerSettings.Keys.PRINT_SPEED, "speed_print", settings.printSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.WALL_SPEED, "speed_wall", settings.wallSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.OUTER_WALL_SPEED, "speed_wall_0", settings.outerWallSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.INNER_WALL_SPEED, "speed_wall_x", settings.innerWallSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.INFILL_SPEED, "speed_infill", settings.infillSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.TOP_BOTTOM_SPEED, "speed_topbottom", settings.topBottomSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.TRAVEL_SPEED, "speed_travel", settings.travelSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.INITIAL_LAYER_SPEED, "speed_layer_0", settings.initialLayerSpeedMmPerSecond)
        // CuraEngine never evaluates definition formulas in the standalone CLI
        // transport; it only reads the resolved child keys. Emit the derived
        // keys the engine actually consumes, mirroring the definition formulas,
        // or the engine silently uses the child `default_value`s.
        putValue(SlicerSettings.Keys.OUTER_WALL_SPEED, "speed_wall_0_roofing", settings.outerWallSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.OUTER_WALL_SPEED, "speed_wall_0_flooring", settings.outerWallSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.INNER_WALL_SPEED, "speed_wall_x_roofing", settings.innerWallSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.INNER_WALL_SPEED, "speed_wall_x_flooring", settings.innerWallSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.TOP_BOTTOM_SPEED, "speed_roofing", settings.topBottomSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.TOP_BOTTOM_SPEED, "speed_flooring", settings.topBottomSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.INITIAL_LAYER_SPEED, "speed_print_layer_0", settings.initialLayerSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.INITIAL_LAYER_SPEED, "skirt_brim_speed", settings.initialLayerSpeedMmPerSecond)
        // definition: speed_travel_layer_0 = speed_layer_0 * speed_travel / speed_print
        putValue(
            SlicerSettings.Keys.INITIAL_LAYER_SPEED,
            "speed_travel_layer_0",
            settings.initialLayerSpeedMmPerSecond * settings.travelSpeedMmPerSecond / settings.printSpeedMmPerSecond,
        )

        // Only the user-facing root temperatures are inputs. Cura formulas remain
        // responsible for initial/final/cooling derivative temperatures.
        putValue(SlicerSettings.Keys.NOZZLE_TEMPERATURE, "material_print_temperature", settings.nozzleTemperatureC)
        putValue(
            SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE,
            "material_print_temperature_layer_0",
            settings.initialNozzleTemperatureC,
        )
        putValue(SlicerSettings.Keys.BED_TEMPERATURE, "material_bed_temperature", settings.bedTemperatureC)
        putValue(
            SlicerSettings.Keys.BUILD_VOLUME_TEMPERATURE,
            "build_volume_temperature",
            settings.buildVolumeTemperatureC,
        )
        putValue(
            SlicerSettings.Keys.MATERIAL_STANDBY_TEMPERATURE,
            "material_standby_temperature",
            settings.materialStandbyTemperatureC,
        )
        putValue(SlicerSettings.Keys.MATERIAL_DENSITY, "material_density", settings.materialDensityGPerCm3)
        putValue(
            SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY,
            "material_adhesion_tendency",
            settings.materialAdhesionTendency,
        )
        putValue(
            SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY,
            "material_surface_energy",
            settings.materialSurfaceEnergyPercent,
        )
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "material_flow", settings.materialFlowPercent)
        // CuraEngine reads the resolved child keys, never the bare parents
        // (definition dependencies): Material Flow / Retraction Speed / Fan
        // Speed are parent keys whose derived children (wall_0_material_flow,
        // retraction_retract_speed, cool_fan_speed_min...) are what the engine
        // actually consumes in its G-code path building. The definitions derive
        // the children with bare-reference formulas the engine never evaluates
        // in the standalone CLI transport, so mirror the definitions here or
        // the engine silently uses the child default values.
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "wall_0_material_flow", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "wall_x_material_flow", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "wall_0_material_flow_roofing", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "wall_0_material_flow_flooring", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "wall_x_material_flow_roofing", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "wall_x_material_flow_flooring", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "skin_material_flow", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "roofing_material_flow", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "flooring_material_flow", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "infill_material_flow", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "support_material_flow", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "support_interface_material_flow", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "support_roof_material_flow", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "support_bottom_material_flow", settings.materialFlowPercent)
        putValue(SlicerSettings.Keys.MATERIAL_FLOW, "skirt_brim_material_flow", settings.materialFlowPercent)
        // CuraEngine drives the cooling fan from the Regular/Maximum Fan Speed
        // child keys (cool_fan_speed_min / cool_fan_speed_max), never from the
        // bare cool_fan_speed key. The bundled definitions derive both children
        // from the parent with a bare-reference formula that neither
        // CuraEngine nor the app resolver evaluates, so emit all three keys
        // with the user's value or the engine silently uses the 100% default.
        putValue(SlicerSettings.Keys.FAN_SPEED, "cool_fan_speed", settings.fanSpeedPercent)
        putValue(SlicerSettings.Keys.FAN_SPEED, "cool_fan_speed_min", settings.fanSpeedPercent)
        putValue(SlicerSettings.Keys.FAN_SPEED, "cool_fan_speed_max", settings.fanSpeedPercent)
        putValue(SlicerSettings.Keys.INITIAL_FAN_SPEED, "cool_fan_speed_0", settings.initialFanSpeedPercent)
        putValue(SlicerSettings.Keys.FAN_FULL_AT_LAYER, "cool_fan_full_layer", settings.fanFullAtLayer)

        putValue(SlicerSettings.Keys.SUPPORTS_ENABLED, "support_enable", settings.supportsEnabled)
        putValue(SlicerSettings.Keys.SUPPORT_PLACEMENT, "support_type", settings.supportPlacement)
        putValue(SlicerSettings.Keys.SUPPORT_STRUCTURE, "support_structure", settings.supportStructure)
        putValue(SlicerSettings.Keys.SUPPORT_ANGLE, "support_angle", settings.supportAngleDegrees)
        if (include(SlicerSettings.Keys.SUPPORT_DENSITY)) {
            put(
                "support_infill_rate",
                if (includeAll && settings.supportsEnabled && settings.supportStructure.equals("tree", ignoreCase = true)) {
                    "0.0"
                } else {
                    settings.supportDensityPercent.toString()
                },
            )
        }
        putValue(SlicerSettings.Keys.SUPPORT_PATTERN, "support_pattern", settings.supportPattern)
        if (include(SlicerSettings.Keys.SUPPORT_DENSITY)) {
            // definition: 0 if support_infill_rate == 0 else
            // (support_line_width * 100) / support_infill_rate
            // * (2 if grid else 3 if triangles else 1). Mirrors the same
            // effective rate as the support_infill_rate emission above so a
            // tree-support package (rate forced to 0) stays at 0 distance.
            val rate = if (
                includeAll && settings.supportsEnabled &&
                settings.supportStructure.equals("tree", ignoreCase = true)
            ) {
                0.0
            } else {
                settings.supportDensityPercent
            }
            val patternFactor = when (settings.supportPattern.lowercase()) {
                "grid" -> 2.0
                "triangles" -> 3.0
                else -> 1.0
            }
            val distance = if (rate <= 0.0) 0.0 else settings.lineWidthMm * 100.0 / rate * patternFactor
            put("support_line_distance", distance.toString())
            put("support_initial_layer_line_distance", distance.toString())
        }
        putValue(SlicerSettings.Keys.SUPPORT_INFILL_MULTIPLIER, "support_infill_multiplier", settings.supportInfillMultiplier)
        putValue(SlicerSettings.Keys.SUPPORT_BRIM_MINIMUM_HOLE_AREA, "support_brim_minimum_hole_area", settings.supportBrimMinimumHoleAreaMm2)
        putValue(
            SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED,
            "support_interface_enable",
            settings.supportInterfaceEnabled,
        )
        // The support generator only reads the per-extruder roof/bottom keys
        // (support_roof_enable, support_bottom_enable, support_roof_height,
        // support_bottom_height), never the support_interface_* parents.
        putValue(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED, "support_roof_enable", settings.supportInterfaceEnabled)
        putValue(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED, "support_bottom_enable", settings.supportInterfaceEnabled)
        putValue(
            SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY,
            "support_interface_density",
            settings.supportInterfaceDensityPercent,
        )
        putValue(SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY, "support_roof_density", settings.supportInterfaceDensityPercent)
        putValue(SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY, "support_bottom_density", settings.supportInterfaceDensityPercent)
        putValue(
            SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT,
            "support_interface_height",
            settings.supportInterfaceHeightMm,
        )
        putValue(SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT, "support_roof_height", settings.supportInterfaceHeightMm)
        putValue(SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT, "support_bottom_height", settings.supportInterfaceHeightMm)
        putValue(SlicerSettings.Keys.SUPPORT_Z_DISTANCE, "support_top_distance", settings.supportZDistanceMm)
        putValue(
            SlicerSettings.Keys.SUPPORT_Z_DISTANCE,
            "support_bottom_distance",
            if (settings.supportPlacement == "everywhere") settings.supportZDistanceMm else 0.0,
        )
        putValue(SlicerSettings.Keys.SUPPORT_Z_DISTANCE, "support_z_distance", settings.supportZDistanceMm)
        putValue(SlicerSettings.Keys.SUPPORT_XY_DISTANCE, "support_xy_distance", settings.supportXyDistanceMm)
        putValue(SlicerSettings.Keys.SUPPORT_SPEED, "speed_support", settings.supportSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.SUPPORT_SPEED, "speed_support_infill", settings.supportSpeedMmPerSecond)
        putValue(
            SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED,
            "speed_support_interface",
            settings.supportInterfaceSpeedMmPerSecond,
        )
        putValue(SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED, "speed_support_roof", settings.supportInterfaceSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED, "speed_support_bottom", settings.supportInterfaceSpeedMmPerSecond)

        if (include(SlicerSettings.Keys.RETRACTION_DISTANCE)) {
            put("retraction_enable", (settings.retractionDistanceMm > 0.0).toString())
            put("retraction_amount", settings.retractionDistanceMm.toString())
        }
        putValue(SlicerSettings.Keys.RETRACTION_SPEED, "retraction_speed", settings.retractionSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.RETRACTION_SPEED, "retraction_retract_speed", settings.retractionSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.RETRACTION_SPEED, "retraction_prime_speed", settings.retractionSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.RETRACTION_SPEED, "wipe_retraction_speed", settings.retractionSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.RETRACTION_SPEED, "wipe_retraction_retract_speed", settings.retractionSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.RETRACTION_SPEED, "wipe_retraction_prime_speed", settings.retractionSpeedMmPerSecond)
        putValue(
            SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL,
            "retraction_min_travel",
            settings.retractionMinimumTravelMm,
        )
        putValue(
            SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE,
            "retract_at_layer_change",
            settings.retractAtLayerChange,
        )
        putValue(SlicerSettings.Keys.COMBING_MODE, "retraction_combing", settings.combingMode)
        putValue(SlicerSettings.Keys.TRAVEL_RETRACT_BEFORE_OUTER_WALL, "travel_retract_before_outer_wall", settings.travelRetractBeforeOuterWall)
        putValue(SlicerSettings.Keys.AVOID_PRINTED_PARTS, "travel_avoid_other_parts", settings.avoidPrintedParts)
        putValue(SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE, "travel_avoid_distance", settings.travelAvoidDistanceMm)
        putValue(SlicerSettings.Keys.Z_HOP, "retraction_hop_enabled", settings.zHopEnabled)
        putValue(SlicerSettings.Keys.Z_HOP_HEIGHT, "retraction_hop", settings.zHopHeightMm)
        putValue(SlicerSettings.Keys.FIRMWARE_RETRACTION, "machine_firmware_retract", settings.firmwareRetraction)
        putValue(SlicerSettings.Keys.COASTING_ENABLED, "coasting_enable", settings.coastingEnabled)
        putValue(SlicerSettings.Keys.COASTING_VOLUME, "coasting_volume", settings.coastingVolumeMm3)
        putValue(
            SlicerSettings.Keys.COASTING_MINIMUM_VOLUME,
            "coasting_min_volume",
            settings.coastingMinimumVolumeMm3,
        )
        putValue(SlicerSettings.Keys.COASTING_SPEED, "coasting_speed", settings.coastingSpeedPercent)

        putValue(SlicerSettings.Keys.ADHESION_TYPE, "adhesion_type", settings.adhesionType)
        putValue(SlicerSettings.Keys.SKIRT_LINE_COUNT, "skirt_line_count", settings.skirtLineCount)
        putValue(SlicerSettings.Keys.BRIM_WIDTH, "brim_width", settings.brimWidthMm)
        // The adhesion generator reads brim_line_count and skirt_brim_speed /
        // skirt_brim_line_width; the definition derives brim_line_count from
        // brim_width and the line widths, which the engine never evaluates.
        putValue(
            SlicerSettings.Keys.BRIM_WIDTH,
            "brim_line_count",
            ceil(settings.brimWidthMm / settings.lineWidthMm).toLong(),
        )
        putValue(SlicerSettings.Keys.RAFT_MARGIN, "raft_margin", settings.raftMarginMm)
        putValue(SlicerSettings.Keys.RAFT_MARGIN, "raft_base_margin", settings.raftMarginMm)
        putValue(SlicerSettings.Keys.RAFT_MARGIN, "raft_interface_margin", settings.raftMarginMm)
        putValue(SlicerSettings.Keys.RAFT_MARGIN, "raft_surface_margin", settings.raftMarginMm)
        putValue(SlicerSettings.Keys.IRONING_ENABLED, "ironing_enabled", settings.ironingEnabled)
        putValue(
            SlicerSettings.Keys.IRONING_ONLY_HIGHEST_LAYER,
            "ironing_only_highest_layer",
            settings.ironingOnlyHighestLayer,
        )
        putValue(SlicerSettings.Keys.IRONING_FLOW, "ironing_flow", settings.ironingFlowPercent)
        putValue(SlicerSettings.Keys.IRONING_SPEED, "speed_ironing", settings.ironingSpeedMmPerSecond)
        putValue(SlicerSettings.Keys.MACHINE_TIME_ESTIMATION_FACTOR, "machine_time_estimation_factor", settings.machineTimeEstimationFactorPercent)
    }

    fun requireResolvedMatch(
        settings: SlicerSettings,
        globalValues: Map<String, String>,
        extruderValues: Map<String, String>,
        modelValues: Map<String, String> = emptyMap(),
    ) {
        val mismatches = explicitValues(settings).mapNotNull { (key, expected) ->
            // Per-mesh overrides from imported projects resolve into
            // modelValues; without the fallback a forced key (e.g. the
            // non-planar top_layers clamp) would report as missing.
            val actual = extruderValues[key] ?: globalValues[key] ?: modelValues[key]
            when {
                actual == null -> "$key is missing; edit=$expected"
                equivalent(expected, actual) -> null
                else -> "$key edit=$expected resolved=$actual"
            }
        }
        require(mismatches.isEmpty()) {
            "Explicit Cura edits diverged from the resolved slice snapshot: ${mismatches.take(12).joinToString()}"
        }
    }

    private fun normalizeSkinAngles(value: String): String {
        val angles = value.split(",").mapNotNull { token -> token.trim().toDoubleOrNull()?.takeIf { d -> d.isFinite() } }
        if (angles.isEmpty()) return "[45,135]"
        return "[" + angles.joinToString(",") + "]"
    }

    private fun equivalent(expected: String, actual: String): Boolean {
        val left = expected.trim()
        val right = actual.trim()
        if (left.equals(right, ignoreCase = true)) return true
        val leftNumber = left.toDoubleOrNull()
        val rightNumber = right.toDoubleOrNull()
        if (leftNumber != null && rightNumber != null) {
            val scale = maxOf(1.0, abs(leftNumber), abs(rightNumber))
            return abs(leftNumber - rightNumber) <= 1e-7 * scale
        }
        return false
    }
}
