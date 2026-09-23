package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONObject

object SlicerSettingsJson {
    private const val KEY_MATERIAL_BRAND = "materialBrand"
    private const val KEY_MATERIAL_TYPE = "materialType"
    private const val KEY_MATERIAL_GUID = "materialGuid"
    private const val KEY_ENABLED_EXTRUDER_COUNT = "enabledExtruderCount"

    val allKeys: Set<String> = setOf(
        SlicerSettings.Keys.PRINTER_NAME,
        SlicerSettings.Keys.MACHINE_WIDTH,
        SlicerSettings.Keys.MACHINE_DEPTH,
        SlicerSettings.Keys.MACHINE_HEIGHT,
        SlicerSettings.Keys.BUILD_PLATE_SHAPE,
        SlicerSettings.Keys.ORIGIN_AT_CENTER,
        SlicerSettings.Keys.HEATED_BED,
        SlicerSettings.Keys.HEATED_BUILD_VOLUME,
        SlicerSettings.Keys.GCODE_FLAVOR,
        SlicerSettings.Keys.NOZZLE_SIZE,
        SlicerSettings.Keys.FILAMENT_DIAMETER,
        SlicerSettings.Keys.PRINTHEAD_X_MIN,
        SlicerSettings.Keys.PRINTHEAD_Y_MIN,
        SlicerSettings.Keys.PRINTHEAD_X_MAX,
        SlicerSettings.Keys.PRINTHEAD_Y_MAX,
        SlicerSettings.Keys.GANTRY_HEIGHT,
        SlicerSettings.Keys.CUSTOM_START_GCODE_ENABLED,
        SlicerSettings.Keys.CUSTOM_START_GCODE,
        SlicerSettings.Keys.CUSTOM_END_GCODE_ENABLED,
        SlicerSettings.Keys.CUSTOM_END_GCODE,
        SlicerSettings.Keys.LAYER_HEIGHT,
        SlicerSettings.Keys.INITIAL_LAYER_HEIGHT,
        SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED,
        SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION,
        SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP,
        SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD,
        SlicerSettings.Keys.ADAPTIVE_MESH_LEVELING_ENABLED,
        SlicerSettings.Keys.AML_MARGIN_MM,
        SlicerSettings.Keys.AML_GRID_POINTS,
        SlicerSettings.Keys.LINE_WIDTH,
        SlicerSettings.Keys.SLICING_TOLERANCE,
        SlicerSettings.Keys.WALL_LINE_COUNT,
        SlicerSettings.Keys.WALL_THICKNESS,
        SlicerSettings.Keys.TOP_LAYERS,
        SlicerSettings.Keys.BOTTOM_LAYERS,
        SlicerSettings.Keys.TOP_BOTTOM_THICKNESS,
        SlicerSettings.Keys.TOP_SKIN_ANGLES,
        SlicerSettings.Keys.TOP_BOTTOM_PATTERN,
        SlicerSettings.Keys.INITIAL_BOTTOM_LAYERS,
        SlicerSettings.Keys.HOLE_HORIZONTAL_EXPANSION,
        SlicerSettings.Keys.INITIAL_LAYER_HORIZONTAL_EXPANSION,
        SlicerSettings.Keys.Z_SEAM_TYPE,
        SlicerSettings.Keys.Z_SEAM_X,
        SlicerSettings.Keys.Z_SEAM_Y,
        SlicerSettings.Keys.Z_SEAM_RELATIVE,
        SlicerSettings.Keys.Z_SEAM_CORNER,
        SlicerSettings.Keys.INFILL_DENSITY,
        SlicerSettings.Keys.INFILL_PATTERN,
        SlicerSettings.Keys.ZIG_ZAG_CONNECT_INFILL,
        SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_ENABLED,
        SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_FLOW,
        SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_BEND_RADIUS,
        SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_EXTRA_WALLS,
        SlicerSettings.Keys.PRINT_SPEED,
        SlicerSettings.Keys.WALL_SPEED,
        SlicerSettings.Keys.OUTER_WALL_SPEED,
        SlicerSettings.Keys.INNER_WALL_SPEED,
        SlicerSettings.Keys.INFILL_SPEED,
        SlicerSettings.Keys.TOP_BOTTOM_SPEED,
        SlicerSettings.Keys.TRAVEL_SPEED,
        SlicerSettings.Keys.INITIAL_LAYER_SPEED,
        SlicerSettings.Keys.NOZZLE_TEMPERATURE,
        SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE,
        SlicerSettings.Keys.BED_TEMPERATURE,
        SlicerSettings.Keys.BUILD_VOLUME_TEMPERATURE,
        SlicerSettings.Keys.MATERIAL_STANDBY_TEMPERATURE,
        SlicerSettings.Keys.MATERIAL_DENSITY,
        SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY,
        SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY,
        KEY_MATERIAL_BRAND,
        KEY_MATERIAL_TYPE,
        KEY_MATERIAL_GUID,
        KEY_ENABLED_EXTRUDER_COUNT,
        SlicerSettings.Keys.MATERIAL_FLOW,
        SlicerSettings.Keys.FAN_SPEED,
        SlicerSettings.Keys.INITIAL_FAN_SPEED,
        SlicerSettings.Keys.FAN_FULL_AT_LAYER,
        SlicerSettings.Keys.SUPPORTS_ENABLED,
        SlicerSettings.Keys.SUPPORT_PLACEMENT,
        SlicerSettings.Keys.SUPPORT_STRUCTURE,
        SlicerSettings.Keys.SUPPORT_ANGLE,
        SlicerSettings.Keys.SUPPORT_DENSITY,
        SlicerSettings.Keys.SUPPORT_PATTERN,
        SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED,
        SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY,
        SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT,
        SlicerSettings.Keys.SUPPORT_Z_DISTANCE,
        SlicerSettings.Keys.SUPPORT_XY_DISTANCE,
        SlicerSettings.Keys.SUPPORT_SPEED,
        SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED,
        SlicerSettings.Keys.RETRACTION_DISTANCE,
        SlicerSettings.Keys.RETRACTION_SPEED,
        SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL,
        SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE,
        SlicerSettings.Keys.COMBING_MODE,
        SlicerSettings.Keys.AVOID_PRINTED_PARTS,
        SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE,
        SlicerSettings.Keys.Z_HOP,
        SlicerSettings.Keys.Z_HOP_HEIGHT,
        SlicerSettings.Keys.FIRMWARE_RETRACTION,
        SlicerSettings.Keys.COASTING_ENABLED,
        SlicerSettings.Keys.COASTING_VOLUME,
        SlicerSettings.Keys.COASTING_MINIMUM_VOLUME,
        SlicerSettings.Keys.COASTING_SPEED,
        SlicerSettings.Keys.ADHESION_TYPE,
        SlicerSettings.Keys.SKIRT_LINE_COUNT,
        SlicerSettings.Keys.BRIM_WIDTH,
        SlicerSettings.Keys.RAFT_MARGIN,
        SlicerSettings.Keys.ARC_OVERHANG_ENABLED,
        SlicerSettings.Keys.ARC_OVERHANG_SPEED,
        SlicerSettings.Keys.ARC_OVERHANG_FLOW,
        SlicerSettings.Keys.ARC_OVERHANG_LINE_SPACING,
        SlicerSettings.Keys.ARC_OVERHANG_MIN_RADIUS,
        SlicerSettings.Keys.ARC_OVERHANG_MAX_RADIUS,
        SlicerSettings.Keys.ARC_OVERHANG_MAX_AREA,
        SlicerSettings.Keys.ARC_OVERHANG_RESOLUTION,
        SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED,
        SlicerSettings.Keys.WAVE_OVERHANG_ENABLED,
        SlicerSettings.Keys.WAVE_OVERHANG_PATTERN,
        SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING,
        SlicerSettings.Keys.WAVE_OVERHANG_FLOW,
        SlicerSettings.Keys.WAVE_OVERHANG_SPEED,
        SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED,
        SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP,
        SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH,
        SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS,
        SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS,
        SlicerSettings.Keys.BRICK_WALL_ENABLED,
        SlicerSettings.Keys.BRICK_WALL_SPEED,
        SlicerSettings.Keys.BRICK_WALL_FLOW,
        SlicerSettings.Keys.BRICK_WALL_FAN_SPEED,
        SlicerSettings.Keys.BRICK_WALL_MAX_ITERATIONS,
        SlicerSettings.Keys.BRICK_WALL_BRICK_LENGTH,
        SlicerSettings.Keys.MASONRY_WALLS_ENABLED,
        SlicerSettings.Keys.SMART_OVERHANG_STRATEGY,
        SlicerSettings.Keys.IRONING_ENABLED,
        SlicerSettings.Keys.IRONING_ONLY_HIGHEST_LAYER,
        SlicerSettings.Keys.IRONING_FLOW,
        SlicerSettings.Keys.IRONING_SPEED,
        SlicerSettings.Keys.INITIAL_LAYER_INSET_DIRECTION,
        SlicerSettings.Keys.TRAVEL_RETRACT_BEFORE_OUTER_WALL,
        SlicerSettings.Keys.INFILL_START_END_PREFERENCE,
        SlicerSettings.Keys.INFILL_MOVE_INWARDS_LENGTH,
        SlicerSettings.Keys.ROOFING_EXPANSION,
        SlicerSettings.Keys.TOP_BOTTOM_SKIN_MERGE_DISTANCE,
        SlicerSettings.Keys.SKIN_SUPPORT_ENABLED,
        SlicerSettings.Keys.SKIN_SUPPORT_DENSITY,
        SlicerSettings.Keys.SKIN_SUPPORT_SPEED,
        SlicerSettings.Keys.SKIN_SUPPORT_MATERIAL_FLOW,
        SlicerSettings.Keys.SKIN_SUPPORT_FAN_SPEED,
        SlicerSettings.Keys.BRIDGE_INTERLACE_LINES,
        SlicerSettings.Keys.SUPPORT_INFILL_MULTIPLIER,
        SlicerSettings.Keys.SUPPORT_BRIM_MINIMUM_HOLE_AREA,
        SlicerSettings.Keys.MACHINE_TIME_ESTIMATION_FACTOR,
        SlicerSettings.Keys.MINIMUM_INFILL_LINE_LENGTH,
    )

    fun serialize(settings: SlicerSettings): JSONObject = JSONObject()
        .put(SlicerSettings.Keys.PRINTER_NAME, settings.printerName)
        .put(SlicerSettings.Keys.MACHINE_WIDTH, settings.machineWidthMm)
        .put(SlicerSettings.Keys.MACHINE_DEPTH, settings.machineDepthMm)
        .put(SlicerSettings.Keys.MACHINE_HEIGHT, settings.machineHeightMm)
        .put(SlicerSettings.Keys.BUILD_PLATE_SHAPE, settings.buildPlateShape)
        .put(SlicerSettings.Keys.ORIGIN_AT_CENTER, settings.originAtCenter)
        .put(SlicerSettings.Keys.HEATED_BED, settings.heatedBed)
        .put(SlicerSettings.Keys.HEATED_BUILD_VOLUME, settings.heatedBuildVolume)
        .put(SlicerSettings.Keys.GCODE_FLAVOR, settings.gcodeFlavor)
        .put(SlicerSettings.Keys.NOZZLE_SIZE, settings.nozzleSizeMm)
        .put(SlicerSettings.Keys.FILAMENT_DIAMETER, settings.filamentDiameterMm)
        .put(SlicerSettings.Keys.PRINTHEAD_X_MIN, settings.printheadXMinMm)
        .put(SlicerSettings.Keys.PRINTHEAD_Y_MIN, settings.printheadYMinMm)
        .put(SlicerSettings.Keys.PRINTHEAD_X_MAX, settings.printheadXMaxMm)
        .put(SlicerSettings.Keys.PRINTHEAD_Y_MAX, settings.printheadYMaxMm)
        .put(SlicerSettings.Keys.GANTRY_HEIGHT, settings.gantryHeightMm)
        .put(SlicerSettings.Keys.CUSTOM_START_GCODE_ENABLED, settings.customStartGcodeEnabled)
        .put(SlicerSettings.Keys.CUSTOM_START_GCODE, settings.customStartGcode)
        .put(SlicerSettings.Keys.CUSTOM_END_GCODE_ENABLED, settings.customEndGcodeEnabled)
        .put(SlicerSettings.Keys.CUSTOM_END_GCODE, settings.customEndGcode)
        .put(SlicerSettings.Keys.LAYER_HEIGHT, settings.layerHeightMm)
        .put(SlicerSettings.Keys.INITIAL_LAYER_HEIGHT, settings.initialLayerHeightMm)
        .put(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED, settings.adaptiveLayerHeightEnabled)
        .put(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION, settings.adaptiveLayerHeightVariationMm)
        .put(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP, settings.adaptiveLayerHeightVariationStepMm)
        .put(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD, settings.adaptiveLayerHeightThreshold)
        .put(SlicerSettings.Keys.ADAPTIVE_MESH_LEVELING_ENABLED, settings.adaptiveMeshLevelingEnabled)
        .put(SlicerSettings.Keys.AML_MARGIN_MM, settings.amlMarginMm)
        .put(SlicerSettings.Keys.AML_GRID_POINTS, settings.amlGridPoints)
        .put(SlicerSettings.Keys.LINE_WIDTH, settings.lineWidthMm)
        .put(SlicerSettings.Keys.SLICING_TOLERANCE, settings.slicingTolerance)
        .put(SlicerSettings.Keys.WALL_LINE_COUNT, settings.wallLineCount)
        .put(SlicerSettings.Keys.WALL_THICKNESS, settings.wallThicknessMm)
        .put(SlicerSettings.Keys.TOP_LAYERS, settings.topLayers)
        .put(SlicerSettings.Keys.BOTTOM_LAYERS, settings.bottomLayers)
        .put(SlicerSettings.Keys.TOP_BOTTOM_THICKNESS, settings.topBottomThicknessMm)
        .put(SlicerSettings.Keys.TOP_SKIN_ANGLES, settings.topSkinAngles)
        .put(SlicerSettings.Keys.TOP_BOTTOM_PATTERN, settings.topBottomPattern)
        .put(SlicerSettings.Keys.INITIAL_BOTTOM_LAYERS, settings.initialBottomLayers)
        .put(SlicerSettings.Keys.HOLE_HORIZONTAL_EXPANSION, settings.holeHorizontalExpansionMm)
        .put(SlicerSettings.Keys.INITIAL_LAYER_HORIZONTAL_EXPANSION, settings.initialLayerHorizontalExpansionMm)
        .put(SlicerSettings.Keys.Z_SEAM_TYPE, settings.zSeamType)
        .put(SlicerSettings.Keys.Z_SEAM_X, settings.zSeamXmm)
        .put(SlicerSettings.Keys.Z_SEAM_Y, settings.zSeamYmm)
        .put(SlicerSettings.Keys.Z_SEAM_RELATIVE, settings.zSeamRelative)
        .put(SlicerSettings.Keys.Z_SEAM_CORNER, settings.zSeamCorner)
        .put(SlicerSettings.Keys.INFILL_DENSITY, settings.infillDensityPercent)
        .put(SlicerSettings.Keys.INFILL_PATTERN, settings.infillPattern)
        .put(SlicerSettings.Keys.ZIG_ZAG_CONNECT_INFILL, settings.zigZagConnectInfill)
        .put(SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_ENABLED, settings.thicknessAdaptiveWallsEnabled)
        .put(SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_FLOW, settings.thicknessAdaptiveWallsFlowPercent)
        .put(SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_BEND_RADIUS, settings.thicknessAdaptiveWallsBendRadiusMm)
        .put(SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_EXTRA_WALLS, settings.thicknessAdaptiveWallsExtraWalls)
        .put(SlicerSettings.Keys.PRINT_SPEED, settings.printSpeedMmPerSecond)
        .put(SlicerSettings.Keys.WALL_SPEED, settings.wallSpeedMmPerSecond)
        .put(SlicerSettings.Keys.OUTER_WALL_SPEED, settings.outerWallSpeedMmPerSecond)
        .put(SlicerSettings.Keys.INNER_WALL_SPEED, settings.innerWallSpeedMmPerSecond)
        .put(SlicerSettings.Keys.INFILL_SPEED, settings.infillSpeedMmPerSecond)
        .put(SlicerSettings.Keys.TOP_BOTTOM_SPEED, settings.topBottomSpeedMmPerSecond)
        .put(SlicerSettings.Keys.TRAVEL_SPEED, settings.travelSpeedMmPerSecond)
        .put(SlicerSettings.Keys.INITIAL_LAYER_SPEED, settings.initialLayerSpeedMmPerSecond)
        .put(SlicerSettings.Keys.NOZZLE_TEMPERATURE, settings.nozzleTemperatureC)
        .put(SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE, settings.initialNozzleTemperatureC)
        .put(SlicerSettings.Keys.BED_TEMPERATURE, settings.bedTemperatureC)
        .put(SlicerSettings.Keys.BUILD_VOLUME_TEMPERATURE, settings.buildVolumeTemperatureC)
        .put(SlicerSettings.Keys.MATERIAL_STANDBY_TEMPERATURE, settings.materialStandbyTemperatureC)
        .put(SlicerSettings.Keys.MATERIAL_DENSITY, settings.materialDensityGPerCm3)
        .put(SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY, settings.materialAdhesionTendency)
        .put(SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY, settings.materialSurfaceEnergyPercent)
        .put(KEY_MATERIAL_BRAND, settings.materialBrand)
        .put(KEY_MATERIAL_TYPE, settings.materialType)
        .put(KEY_MATERIAL_GUID, settings.materialGuid)
        .put(KEY_ENABLED_EXTRUDER_COUNT, settings.enabledExtruderCount)
        .put(SlicerSettings.Keys.MATERIAL_FLOW, settings.materialFlowPercent)
        .put(SlicerSettings.Keys.FAN_SPEED, settings.fanSpeedPercent)
        .put(SlicerSettings.Keys.INITIAL_FAN_SPEED, settings.initialFanSpeedPercent)
        .put(SlicerSettings.Keys.FAN_FULL_AT_LAYER, settings.fanFullAtLayer)
        .put(SlicerSettings.Keys.SUPPORTS_ENABLED, settings.supportsEnabled)
        .put(SlicerSettings.Keys.SUPPORT_PLACEMENT, settings.supportPlacement)
        .put(SlicerSettings.Keys.SUPPORT_STRUCTURE, settings.supportStructure)
        .put(SlicerSettings.Keys.SUPPORT_ANGLE, settings.supportAngleDegrees)
        .put(SlicerSettings.Keys.SUPPORT_DENSITY, settings.supportDensityPercent)
        .put(SlicerSettings.Keys.SUPPORT_PATTERN, settings.supportPattern)
        .put(SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED, settings.supportInterfaceEnabled)
        .put(SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY, settings.supportInterfaceDensityPercent)
        .put(SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT, settings.supportInterfaceHeightMm)
        .put(SlicerSettings.Keys.SUPPORT_Z_DISTANCE, settings.supportZDistanceMm)
        .put(SlicerSettings.Keys.SUPPORT_XY_DISTANCE, settings.supportXyDistanceMm)
        .put(SlicerSettings.Keys.SUPPORT_SPEED, settings.supportSpeedMmPerSecond)
        .put(SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED, settings.supportInterfaceSpeedMmPerSecond)
        .put(SlicerSettings.Keys.RETRACTION_DISTANCE, settings.retractionDistanceMm)
        .put(SlicerSettings.Keys.RETRACTION_SPEED, settings.retractionSpeedMmPerSecond)
        .put(SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL, settings.retractionMinimumTravelMm)
        .put(SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE, settings.retractAtLayerChange)
        .put(SlicerSettings.Keys.COMBING_MODE, settings.combingMode)
        .put(SlicerSettings.Keys.AVOID_PRINTED_PARTS, settings.avoidPrintedParts)
        .put(SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE, settings.travelAvoidDistanceMm)
        .put(SlicerSettings.Keys.Z_HOP, settings.zHopEnabled)
        .put(SlicerSettings.Keys.Z_HOP_HEIGHT, settings.zHopHeightMm)
        .put(SlicerSettings.Keys.FIRMWARE_RETRACTION, settings.firmwareRetraction)
        .put(SlicerSettings.Keys.COASTING_ENABLED, settings.coastingEnabled)
        .put(SlicerSettings.Keys.COASTING_VOLUME, settings.coastingVolumeMm3)
        .put(SlicerSettings.Keys.COASTING_MINIMUM_VOLUME, settings.coastingMinimumVolumeMm3)
        .put(SlicerSettings.Keys.COASTING_SPEED, settings.coastingSpeedPercent)
        .put(SlicerSettings.Keys.ADHESION_TYPE, settings.adhesionType)
        .put(SlicerSettings.Keys.SKIRT_LINE_COUNT, settings.skirtLineCount)
        .put(SlicerSettings.Keys.BRIM_WIDTH, settings.brimWidthMm)
        .put(SlicerSettings.Keys.RAFT_MARGIN, settings.raftMarginMm)
        .put(SlicerSettings.Keys.ARC_OVERHANG_ENABLED, settings.arcOverhangEnabled)
        .put(SlicerSettings.Keys.ARC_OVERHANG_SPEED, settings.arcOverhangSpeedMmPerSecond)
        .put(SlicerSettings.Keys.ARC_OVERHANG_FLOW, settings.arcOverhangFlowPercent)
        .put(SlicerSettings.Keys.ARC_OVERHANG_LINE_SPACING, settings.arcOverhangLineSpacingPercent)
        .put(SlicerSettings.Keys.ARC_OVERHANG_MIN_RADIUS, settings.arcOverhangMinRadiusMm)
        .put(SlicerSettings.Keys.ARC_OVERHANG_MAX_RADIUS, settings.arcOverhangMaxRadiusMm)
        .put(SlicerSettings.Keys.ARC_OVERHANG_MAX_AREA, settings.arcOverhangMaxAreaMm2)
        .put(SlicerSettings.Keys.ARC_OVERHANG_RESOLUTION, settings.arcOverhangResolutionMm)
        .put(SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED, settings.arcOverhangFanSpeedPercent)
        .put(SlicerSettings.Keys.WAVE_OVERHANG_ENABLED, settings.waveOverhangEnabled)
        .put(SlicerSettings.Keys.WAVE_OVERHANG_PATTERN, settings.waveOverhangPattern)
        .put(SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING, settings.waveOverhangLineSpacingMm)
        .put(SlicerSettings.Keys.WAVE_OVERHANG_FLOW, settings.waveOverhangFlowMm3PerMm)
        .put(SlicerSettings.Keys.WAVE_OVERHANG_SPEED, settings.waveOverhangSpeedMmPerSecond)
        .put(SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED, settings.waveOverhangFanSpeedPercent)
        .put(SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP, settings.waveOverhangPerimeterOverlapMm)
        .put(SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH, settings.waveOverhangMinimumWidthMm)
        .put(SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS, settings.waveOverhangMaxIterations)
        .put(SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS, settings.waveOverhangReverseOddLayers)
        .put(SlicerSettings.Keys.BRICK_WALL_ENABLED, settings.brickWallEnabled)
        .put(SlicerSettings.Keys.BRICK_WALL_SPEED, settings.brickWallSpeedMmPerSecond)
        .put(SlicerSettings.Keys.BRICK_WALL_FLOW, settings.brickWallFlowPercent)
        .put(SlicerSettings.Keys.BRICK_WALL_FAN_SPEED, settings.brickWallFanSpeedPercent)
        .put(SlicerSettings.Keys.BRICK_WALL_MAX_ITERATIONS, settings.brickWallMaxIterations)
        .put(SlicerSettings.Keys.MASONRY_WALLS_ENABLED, settings.masonryWallsEnabled)
        .put(SlicerSettings.Keys.BRICK_WALL_BRICK_LENGTH, settings.brickWallBrickLengthMm)
        .put(SlicerSettings.Keys.SMART_OVERHANG_STRATEGY, settings.smartOverhangStrategy)
        .put(SlicerSettings.Keys.IRONING_ENABLED, settings.ironingEnabled)
        .put(SlicerSettings.Keys.IRONING_ONLY_HIGHEST_LAYER, settings.ironingOnlyHighestLayer)
        .put(SlicerSettings.Keys.IRONING_FLOW, settings.ironingFlowPercent)
        .put(SlicerSettings.Keys.IRONING_SPEED, settings.ironingSpeedMmPerSecond)
        .put(SlicerSettings.Keys.INITIAL_LAYER_INSET_DIRECTION, settings.initialLayerInsetDirection)
        .put(SlicerSettings.Keys.TRAVEL_RETRACT_BEFORE_OUTER_WALL, settings.travelRetractBeforeOuterWall)
        .put(SlicerSettings.Keys.INFILL_START_END_PREFERENCE, settings.infillStartEndPreference)
        .put(SlicerSettings.Keys.INFILL_MOVE_INWARDS_LENGTH, settings.infillMoveInwardsLengthMm)
        .put(SlicerSettings.Keys.ROOFING_EXPANSION, settings.roofingExpansionMm)
        .put(SlicerSettings.Keys.TOP_BOTTOM_SKIN_MERGE_DISTANCE, settings.topBottomSkinMergeDistanceMm)
        .put(SlicerSettings.Keys.SKIN_SUPPORT_ENABLED, settings.skinSupportEnabled)
        .put(SlicerSettings.Keys.SKIN_SUPPORT_DENSITY, settings.skinSupportDensityPercent)
        .put(SlicerSettings.Keys.SKIN_SUPPORT_SPEED, settings.skinSupportSpeedMmPerSecond)
        .put(SlicerSettings.Keys.SKIN_SUPPORT_MATERIAL_FLOW, settings.skinSupportMaterialFlowPercent)
        .put(SlicerSettings.Keys.SKIN_SUPPORT_FAN_SPEED, settings.skinSupportFanSpeedPercent)
        .put(SlicerSettings.Keys.BRIDGE_INTERLACE_LINES, settings.bridgeInterlaceLines)
        .put(SlicerSettings.Keys.SUPPORT_INFILL_MULTIPLIER, settings.supportInfillMultiplier)
        .put(SlicerSettings.Keys.SUPPORT_BRIM_MINIMUM_HOLE_AREA, settings.supportBrimMinimumHoleAreaMm2)
        .put(SlicerSettings.Keys.MACHINE_TIME_ESTIMATION_FACTOR, settings.machineTimeEstimationFactorPercent)
        .put(SlicerSettings.Keys.MINIMUM_INFILL_LINE_LENGTH, settings.minimumInfillLineLengthMm)

    fun apply(base: SlicerSettings, values: JSONObject, keys: Set<String>): SlicerSettings {
        var restored = base.copy(overriddenSettingKeys = emptySet())
        keys.forEach { key ->
            restored = when (key) {
                SlicerSettings.Keys.PRINTER_NAME -> restored.copy(printerName = values.optString(key, restored.printerName))
                SlicerSettings.Keys.MACHINE_WIDTH -> restored.copy(machineWidthMm = values.optDouble(key, restored.machineWidthMm))
                SlicerSettings.Keys.MACHINE_DEPTH -> restored.copy(machineDepthMm = values.optDouble(key, restored.machineDepthMm))
                SlicerSettings.Keys.MACHINE_HEIGHT -> restored.copy(machineHeightMm = values.optDouble(key, restored.machineHeightMm))
                SlicerSettings.Keys.BUILD_PLATE_SHAPE -> restored.copy(buildPlateShape = values.optString(key, restored.buildPlateShape))
                SlicerSettings.Keys.ORIGIN_AT_CENTER -> restored.copy(originAtCenter = values.optBoolean(key, restored.originAtCenter))
                SlicerSettings.Keys.HEATED_BED -> restored.copy(heatedBed = values.optBoolean(key, restored.heatedBed))
                SlicerSettings.Keys.HEATED_BUILD_VOLUME -> restored.copy(heatedBuildVolume = values.optBoolean(key, restored.heatedBuildVolume))
                SlicerSettings.Keys.GCODE_FLAVOR -> restored.copy(gcodeFlavor = values.optString(key, restored.gcodeFlavor))
                SlicerSettings.Keys.NOZZLE_SIZE -> restored.copy(nozzleSizeMm = values.optDouble(key, restored.nozzleSizeMm))
                SlicerSettings.Keys.FILAMENT_DIAMETER -> restored.copy(filamentDiameterMm = values.optDouble(key, restored.filamentDiameterMm))
                SlicerSettings.Keys.PRINTHEAD_X_MIN -> restored.copy(printheadXMinMm = values.optDouble(key, restored.printheadXMinMm))
                SlicerSettings.Keys.PRINTHEAD_Y_MIN -> restored.copy(printheadYMinMm = values.optDouble(key, restored.printheadYMinMm))
                SlicerSettings.Keys.PRINTHEAD_X_MAX -> restored.copy(printheadXMaxMm = values.optDouble(key, restored.printheadXMaxMm))
                SlicerSettings.Keys.PRINTHEAD_Y_MAX -> restored.copy(printheadYMaxMm = values.optDouble(key, restored.printheadYMaxMm))
                SlicerSettings.Keys.GANTRY_HEIGHT -> restored.copy(gantryHeightMm = values.optDouble(key, restored.gantryHeightMm))
                SlicerSettings.Keys.CUSTOM_START_GCODE_ENABLED -> restored.copy(customStartGcodeEnabled = values.optBoolean(key, restored.customStartGcodeEnabled))
                SlicerSettings.Keys.CUSTOM_START_GCODE -> restored.copy(customStartGcode = values.optString(key, restored.customStartGcode))
                SlicerSettings.Keys.CUSTOM_END_GCODE_ENABLED -> restored.copy(customEndGcodeEnabled = values.optBoolean(key, restored.customEndGcodeEnabled))
                SlicerSettings.Keys.CUSTOM_END_GCODE -> restored.copy(customEndGcode = values.optString(key, restored.customEndGcode))
                SlicerSettings.Keys.LAYER_HEIGHT -> restored.copy(layerHeightMm = values.optDouble(key, restored.layerHeightMm))
                SlicerSettings.Keys.INITIAL_LAYER_HEIGHT -> restored.copy(initialLayerHeightMm = values.optDouble(key, restored.initialLayerHeightMm))
                SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED -> restored.copy(
                    adaptiveLayerHeightEnabled = values.optBoolean(key, restored.adaptiveLayerHeightEnabled),
                )
                SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION -> restored.copy(
                    adaptiveLayerHeightVariationMm = values.optDouble(key, restored.adaptiveLayerHeightVariationMm),
                )
                SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP -> restored.copy(
                    adaptiveLayerHeightVariationStepMm = values.optDouble(key, restored.adaptiveLayerHeightVariationStepMm),
                )
                SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD -> restored.copy(
                    adaptiveLayerHeightThreshold = values.optDouble(key, restored.adaptiveLayerHeightThreshold),
                )
                SlicerSettings.Keys.ADAPTIVE_MESH_LEVELING_ENABLED -> restored.copy(
                    adaptiveMeshLevelingEnabled = values.optBoolean(key, restored.adaptiveMeshLevelingEnabled),
                )
                SlicerSettings.Keys.AML_MARGIN_MM -> restored.copy(
                    amlMarginMm = values.optDouble(key, restored.amlMarginMm).coerceIn(0.0, 100.0),
                )
                SlicerSettings.Keys.AML_GRID_POINTS -> restored.copy(
                    amlGridPoints = values.optInt(key, restored.amlGridPoints).coerceIn(3, 9),
                )
                SlicerSettings.Keys.LINE_WIDTH -> restored.copy(lineWidthMm = values.optDouble(key, restored.lineWidthMm))
                SlicerSettings.Keys.SLICING_TOLERANCE -> restored.copy(slicingTolerance = values.optString(key, restored.slicingTolerance))
                SlicerSettings.Keys.WALL_LINE_COUNT -> restored.copy(wallLineCount = values.optInt(key, restored.wallLineCount))
                SlicerSettings.Keys.WALL_THICKNESS -> restored.copy(wallThicknessMm = values.optDouble(key, restored.wallThicknessMm))
                SlicerSettings.Keys.TOP_LAYERS -> restored.copy(topLayers = values.optInt(key, restored.topLayers))
                SlicerSettings.Keys.BOTTOM_LAYERS -> restored.copy(bottomLayers = values.optInt(key, restored.bottomLayers))
                SlicerSettings.Keys.TOP_BOTTOM_THICKNESS -> restored.copy(topBottomThicknessMm = values.optDouble(key, restored.topBottomThicknessMm))
                SlicerSettings.Keys.TOP_SKIN_ANGLES -> restored.copy(topSkinAngles = values.optString(key, restored.topSkinAngles))
                SlicerSettings.Keys.TOP_BOTTOM_PATTERN -> restored.copy(topBottomPattern = values.optString(key, restored.topBottomPattern))
                SlicerSettings.Keys.INITIAL_BOTTOM_LAYERS -> restored.copy(initialBottomLayers = values.optInt(key, restored.initialBottomLayers))
                SlicerSettings.Keys.HOLE_HORIZONTAL_EXPANSION -> restored.copy(holeHorizontalExpansionMm = values.optDouble(key, restored.holeHorizontalExpansionMm))
                SlicerSettings.Keys.INITIAL_LAYER_HORIZONTAL_EXPANSION -> restored.copy(
                    initialLayerHorizontalExpansionMm = values.optDouble(key, restored.initialLayerHorizontalExpansionMm),
                )
                SlicerSettings.Keys.Z_SEAM_TYPE -> restored.copy(zSeamType = values.optString(key, restored.zSeamType))
                SlicerSettings.Keys.Z_SEAM_X -> restored.copy(zSeamXmm = values.optDouble(key, restored.zSeamXmm))
                SlicerSettings.Keys.Z_SEAM_Y -> restored.copy(zSeamYmm = values.optDouble(key, restored.zSeamYmm))
                SlicerSettings.Keys.Z_SEAM_RELATIVE -> restored.copy(zSeamRelative = values.optBoolean(key, restored.zSeamRelative))
                SlicerSettings.Keys.Z_SEAM_CORNER -> restored.copy(zSeamCorner = values.optString(key, restored.zSeamCorner))
                SlicerSettings.Keys.INFILL_DENSITY -> restored.copy(infillDensityPercent = values.optDouble(key, restored.infillDensityPercent))
                SlicerSettings.Keys.INFILL_PATTERN -> restored.copy(infillPattern = values.optString(key, restored.infillPattern))
                SlicerSettings.Keys.ZIG_ZAG_CONNECT_INFILL -> restored.copy(zigZagConnectInfill = values.optBoolean(key, restored.zigZagConnectInfill))
                SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_ENABLED -> restored.copy(
                    thicknessAdaptiveWallsEnabled = values.optBoolean(key, restored.thicknessAdaptiveWallsEnabled),
                )
                SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_FLOW -> restored.copy(
                    thicknessAdaptiveWallsFlowPercent = values.optDouble(key, restored.thicknessAdaptiveWallsFlowPercent),
                )
                SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_BEND_RADIUS -> restored.copy(
                    thicknessAdaptiveWallsBendRadiusMm = values.optDouble(key, restored.thicknessAdaptiveWallsBendRadiusMm),
                )
                SlicerSettings.Keys.THICKNESS_ADAPTIVE_WALLS_EXTRA_WALLS -> restored.copy(
                    thicknessAdaptiveWallsExtraWalls = values.optInt(key, restored.thicknessAdaptiveWallsExtraWalls),
                )
                SlicerSettings.Keys.PRINT_SPEED -> restored.copy(printSpeedMmPerSecond = values.optDouble(key, restored.printSpeedMmPerSecond))
                SlicerSettings.Keys.WALL_SPEED -> restored.copy(wallSpeedMmPerSecond = values.optDouble(key, restored.wallSpeedMmPerSecond))
                SlicerSettings.Keys.OUTER_WALL_SPEED -> restored.copy(outerWallSpeedMmPerSecond = values.optDouble(key, restored.outerWallSpeedMmPerSecond))
                SlicerSettings.Keys.INNER_WALL_SPEED -> restored.copy(innerWallSpeedMmPerSecond = values.optDouble(key, restored.innerWallSpeedMmPerSecond))
                SlicerSettings.Keys.INFILL_SPEED -> restored.copy(infillSpeedMmPerSecond = values.optDouble(key, restored.infillSpeedMmPerSecond))
                SlicerSettings.Keys.TOP_BOTTOM_SPEED -> restored.copy(topBottomSpeedMmPerSecond = values.optDouble(key, restored.topBottomSpeedMmPerSecond))
                SlicerSettings.Keys.TRAVEL_SPEED -> restored.copy(travelSpeedMmPerSecond = values.optDouble(key, restored.travelSpeedMmPerSecond))
                SlicerSettings.Keys.INITIAL_LAYER_SPEED -> restored.copy(initialLayerSpeedMmPerSecond = values.optDouble(key, restored.initialLayerSpeedMmPerSecond))
                SlicerSettings.Keys.NOZZLE_TEMPERATURE -> restored.copy(nozzleTemperatureC = values.optInt(key, restored.nozzleTemperatureC))
                SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE -> restored.copy(initialNozzleTemperatureC = values.optInt(key, restored.initialNozzleTemperatureC))
                SlicerSettings.Keys.BED_TEMPERATURE -> restored.copy(bedTemperatureC = values.optInt(key, restored.bedTemperatureC))
                SlicerSettings.Keys.BUILD_VOLUME_TEMPERATURE -> restored.copy(buildVolumeTemperatureC = values.optDouble(key, restored.buildVolumeTemperatureC))
                SlicerSettings.Keys.MATERIAL_STANDBY_TEMPERATURE -> restored.copy(materialStandbyTemperatureC = values.optDouble(key, restored.materialStandbyTemperatureC))
                SlicerSettings.Keys.MATERIAL_DENSITY -> restored.copy(materialDensityGPerCm3 = values.optDouble(key, restored.materialDensityGPerCm3))
                SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY -> restored.copy(materialAdhesionTendency = values.optInt(key, restored.materialAdhesionTendency))
                SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY -> restored.copy(materialSurfaceEnergyPercent = values.optInt(key, restored.materialSurfaceEnergyPercent))
                KEY_MATERIAL_BRAND -> restored.copy(materialBrand = values.optString(key, restored.materialBrand))
                KEY_MATERIAL_TYPE -> restored.copy(materialType = values.optString(key, restored.materialType))
                KEY_MATERIAL_GUID -> restored.copy(materialGuid = values.optString(key, restored.materialGuid))
                KEY_ENABLED_EXTRUDER_COUNT -> restored.copy(enabledExtruderCount = values.optInt(key, restored.enabledExtruderCount))
                SlicerSettings.Keys.MATERIAL_FLOW -> restored.copy(materialFlowPercent = values.optDouble(key, restored.materialFlowPercent))
                SlicerSettings.Keys.FAN_SPEED -> restored.copy(fanSpeedPercent = values.optDouble(key, restored.fanSpeedPercent))
                SlicerSettings.Keys.INITIAL_FAN_SPEED -> restored.copy(initialFanSpeedPercent = values.optDouble(key, restored.initialFanSpeedPercent))
                SlicerSettings.Keys.FAN_FULL_AT_LAYER -> restored.copy(fanFullAtLayer = values.optInt(key, restored.fanFullAtLayer))
                SlicerSettings.Keys.SUPPORTS_ENABLED -> restored.copy(supportsEnabled = values.optBoolean(key, restored.supportsEnabled))
                SlicerSettings.Keys.SUPPORT_PLACEMENT -> restored.copy(supportPlacement = values.optString(key, restored.supportPlacement))
                SlicerSettings.Keys.SUPPORT_STRUCTURE -> restored.copy(supportStructure = values.optString(key, restored.supportStructure))
                SlicerSettings.Keys.SUPPORT_ANGLE -> restored.copy(supportAngleDegrees = values.optDouble(key, restored.supportAngleDegrees))
                SlicerSettings.Keys.SUPPORT_DENSITY -> restored.copy(supportDensityPercent = values.optDouble(key, restored.supportDensityPercent))
                SlicerSettings.Keys.SUPPORT_PATTERN -> restored.copy(supportPattern = values.optString(key, restored.supportPattern))
                SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED -> restored.copy(supportInterfaceEnabled = values.optBoolean(key, restored.supportInterfaceEnabled))
                SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY -> restored.copy(supportInterfaceDensityPercent = values.optDouble(key, restored.supportInterfaceDensityPercent))
                SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT -> restored.copy(supportInterfaceHeightMm = values.optDouble(key, restored.supportInterfaceHeightMm))
                SlicerSettings.Keys.SUPPORT_Z_DISTANCE -> restored.copy(supportZDistanceMm = values.optDouble(key, restored.supportZDistanceMm))
                SlicerSettings.Keys.SUPPORT_XY_DISTANCE -> restored.copy(supportXyDistanceMm = values.optDouble(key, restored.supportXyDistanceMm))
                SlicerSettings.Keys.SUPPORT_SPEED -> restored.copy(supportSpeedMmPerSecond = values.optDouble(key, restored.supportSpeedMmPerSecond))
                SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED -> restored.copy(supportInterfaceSpeedMmPerSecond = values.optDouble(key, restored.supportInterfaceSpeedMmPerSecond))
                SlicerSettings.Keys.RETRACTION_DISTANCE -> restored.copy(retractionDistanceMm = values.optDouble(key, restored.retractionDistanceMm))
                SlicerSettings.Keys.RETRACTION_SPEED -> restored.copy(retractionSpeedMmPerSecond = values.optDouble(key, restored.retractionSpeedMmPerSecond))
                SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL -> restored.copy(retractionMinimumTravelMm = values.optDouble(key, restored.retractionMinimumTravelMm))
                SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE -> restored.copy(retractAtLayerChange = values.optBoolean(key, restored.retractAtLayerChange))
                SlicerSettings.Keys.COMBING_MODE -> restored.copy(combingMode = values.optString(key, restored.combingMode))
                SlicerSettings.Keys.AVOID_PRINTED_PARTS -> restored.copy(avoidPrintedParts = values.optBoolean(key, restored.avoidPrintedParts))
                SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE -> restored.copy(travelAvoidDistanceMm = values.optDouble(key, restored.travelAvoidDistanceMm))
                SlicerSettings.Keys.Z_HOP -> restored.copy(zHopEnabled = values.optBoolean(key, restored.zHopEnabled))
                SlicerSettings.Keys.Z_HOP_HEIGHT -> restored.copy(zHopHeightMm = values.optDouble(key, restored.zHopHeightMm))
                SlicerSettings.Keys.FIRMWARE_RETRACTION -> restored.copy(firmwareRetraction = values.optBoolean(key, restored.firmwareRetraction))
                SlicerSettings.Keys.COASTING_ENABLED -> restored.copy(coastingEnabled = values.optBoolean(key, restored.coastingEnabled))
                SlicerSettings.Keys.COASTING_VOLUME -> restored.copy(coastingVolumeMm3 = values.optDouble(key, restored.coastingVolumeMm3))
                SlicerSettings.Keys.COASTING_MINIMUM_VOLUME -> restored.copy(coastingMinimumVolumeMm3 = values.optDouble(key, restored.coastingMinimumVolumeMm3))
                SlicerSettings.Keys.COASTING_SPEED -> restored.copy(coastingSpeedPercent = values.optDouble(key, restored.coastingSpeedPercent))
                SlicerSettings.Keys.ADHESION_TYPE -> restored.copy(adhesionType = values.optString(key, restored.adhesionType))
                SlicerSettings.Keys.SKIRT_LINE_COUNT -> restored.copy(skirtLineCount = values.optInt(key, restored.skirtLineCount))
                SlicerSettings.Keys.BRIM_WIDTH -> restored.copy(brimWidthMm = values.optDouble(key, restored.brimWidthMm))
                SlicerSettings.Keys.RAFT_MARGIN -> restored.copy(raftMarginMm = values.optDouble(key, restored.raftMarginMm))
                SlicerSettings.Keys.ARC_OVERHANG_ENABLED -> restored.copy(arcOverhangEnabled = values.optBoolean(key, restored.arcOverhangEnabled))
                SlicerSettings.Keys.ARC_OVERHANG_SPEED -> restored.copy(arcOverhangSpeedMmPerSecond = values.optDouble(key, restored.arcOverhangSpeedMmPerSecond))
                SlicerSettings.Keys.ARC_OVERHANG_FLOW -> restored.copy(arcOverhangFlowPercent = values.optDouble(key, restored.arcOverhangFlowPercent))
                SlicerSettings.Keys.ARC_OVERHANG_LINE_SPACING -> restored.copy(arcOverhangLineSpacingPercent = values.optDouble(key, restored.arcOverhangLineSpacingPercent))
                SlicerSettings.Keys.ARC_OVERHANG_MIN_RADIUS -> restored.copy(arcOverhangMinRadiusMm = values.optDouble(key, restored.arcOverhangMinRadiusMm))
                SlicerSettings.Keys.ARC_OVERHANG_MAX_RADIUS -> restored.copy(arcOverhangMaxRadiusMm = values.optDouble(key, restored.arcOverhangMaxRadiusMm))
                SlicerSettings.Keys.ARC_OVERHANG_MAX_AREA -> restored.copy(arcOverhangMaxAreaMm2 = values.optDouble(key, restored.arcOverhangMaxAreaMm2))
                SlicerSettings.Keys.ARC_OVERHANG_RESOLUTION -> restored.copy(arcOverhangResolutionMm = values.optDouble(key, restored.arcOverhangResolutionMm))
                SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED -> restored.copy(arcOverhangFanSpeedPercent = values.optDouble(key, restored.arcOverhangFanSpeedPercent))
                SlicerSettings.Keys.WAVE_OVERHANG_ENABLED -> restored.copy(waveOverhangEnabled = values.optBoolean(key, restored.waveOverhangEnabled))
                SlicerSettings.Keys.WAVE_OVERHANG_PATTERN -> restored.copy(waveOverhangPattern = values.optString(key, restored.waveOverhangPattern))
                SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING -> restored.copy(waveOverhangLineSpacingMm = values.optDouble(key, restored.waveOverhangLineSpacingMm))
                SlicerSettings.Keys.WAVE_OVERHANG_FLOW -> restored.copy(waveOverhangFlowMm3PerMm = values.optDouble(key, restored.waveOverhangFlowMm3PerMm))
                SlicerSettings.Keys.WAVE_OVERHANG_SPEED -> restored.copy(waveOverhangSpeedMmPerSecond = values.optDouble(key, restored.waveOverhangSpeedMmPerSecond))
                SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED -> restored.copy(waveOverhangFanSpeedPercent = values.optDouble(key, restored.waveOverhangFanSpeedPercent))
                SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP -> restored.copy(waveOverhangPerimeterOverlapMm = values.optDouble(key, restored.waveOverhangPerimeterOverlapMm))
                SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH -> restored.copy(waveOverhangMinimumWidthMm = values.optDouble(key, restored.waveOverhangMinimumWidthMm))
                SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS -> restored.copy(waveOverhangMaxIterations = values.optInt(key, restored.waveOverhangMaxIterations))
                SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS -> restored.copy(waveOverhangReverseOddLayers = values.optBoolean(key, restored.waveOverhangReverseOddLayers))
                SlicerSettings.Keys.BRICK_WALL_ENABLED -> restored.copy(brickWallEnabled = values.optBoolean(key, restored.brickWallEnabled))
                SlicerSettings.Keys.BRICK_WALL_SPEED -> restored.copy(brickWallSpeedMmPerSecond = values.optDouble(key, restored.brickWallSpeedMmPerSecond))
                SlicerSettings.Keys.BRICK_WALL_FLOW -> restored.copy(brickWallFlowPercent = values.optDouble(key, restored.brickWallFlowPercent))
                SlicerSettings.Keys.BRICK_WALL_FAN_SPEED -> restored.copy(brickWallFanSpeedPercent = values.optDouble(key, restored.brickWallFanSpeedPercent))
                SlicerSettings.Keys.BRICK_WALL_MAX_ITERATIONS -> restored.copy(brickWallMaxIterations = values.optInt(key, restored.brickWallMaxIterations))
                SlicerSettings.Keys.BRICK_WALL_BRICK_LENGTH -> restored.copy(brickWallBrickLengthMm = values.optDouble(key, restored.brickWallBrickLengthMm))
                SlicerSettings.Keys.MASONRY_WALLS_ENABLED -> restored.copy(masonryWallsEnabled = values.optBoolean(key, restored.masonryWallsEnabled))
                SlicerSettings.Keys.SMART_OVERHANG_STRATEGY -> restored.copy(smartOverhangStrategy = values.optBoolean(key, restored.smartOverhangStrategy))
                SlicerSettings.Keys.IRONING_ENABLED -> restored.copy(ironingEnabled = values.optBoolean(key, restored.ironingEnabled))
                SlicerSettings.Keys.IRONING_ONLY_HIGHEST_LAYER -> restored.copy(ironingOnlyHighestLayer = values.optBoolean(key, restored.ironingOnlyHighestLayer))
                SlicerSettings.Keys.IRONING_FLOW -> restored.copy(ironingFlowPercent = values.optDouble(key, restored.ironingFlowPercent))
                SlicerSettings.Keys.IRONING_SPEED -> restored.copy(ironingSpeedMmPerSecond = values.optDouble(key, restored.ironingSpeedMmPerSecond))
                SlicerSettings.Keys.INITIAL_LAYER_INSET_DIRECTION -> restored.copy(initialLayerInsetDirection = values.optString(key, restored.initialLayerInsetDirection))
                SlicerSettings.Keys.TRAVEL_RETRACT_BEFORE_OUTER_WALL -> restored.copy(travelRetractBeforeOuterWall = values.optString(key, restored.travelRetractBeforeOuterWall))
                SlicerSettings.Keys.INFILL_START_END_PREFERENCE -> restored.copy(infillStartEndPreference = values.optString(key, restored.infillStartEndPreference))
                SlicerSettings.Keys.INFILL_MOVE_INWARDS_LENGTH -> restored.copy(infillMoveInwardsLengthMm = values.optDouble(key, restored.infillMoveInwardsLengthMm))
                SlicerSettings.Keys.ROOFING_EXPANSION -> restored.copy(roofingExpansionMm = values.optDouble(key, restored.roofingExpansionMm))
                SlicerSettings.Keys.TOP_BOTTOM_SKIN_MERGE_DISTANCE -> restored.copy(topBottomSkinMergeDistanceMm = values.optDouble(key, restored.topBottomSkinMergeDistanceMm))
                SlicerSettings.Keys.SKIN_SUPPORT_ENABLED -> restored.copy(skinSupportEnabled = values.optBoolean(key, restored.skinSupportEnabled))
                SlicerSettings.Keys.SKIN_SUPPORT_DENSITY -> restored.copy(skinSupportDensityPercent = values.optDouble(key, restored.skinSupportDensityPercent))
                SlicerSettings.Keys.SKIN_SUPPORT_SPEED -> restored.copy(skinSupportSpeedMmPerSecond = values.optDouble(key, restored.skinSupportSpeedMmPerSecond))
                SlicerSettings.Keys.SKIN_SUPPORT_MATERIAL_FLOW -> restored.copy(skinSupportMaterialFlowPercent = values.optDouble(key, restored.skinSupportMaterialFlowPercent))
                SlicerSettings.Keys.SKIN_SUPPORT_FAN_SPEED -> restored.copy(skinSupportFanSpeedPercent = values.optDouble(key, restored.skinSupportFanSpeedPercent))
                SlicerSettings.Keys.BRIDGE_INTERLACE_LINES -> restored.copy(bridgeInterlaceLines = values.optBoolean(key, restored.bridgeInterlaceLines))
                SlicerSettings.Keys.SUPPORT_INFILL_MULTIPLIER -> restored.copy(supportInfillMultiplier = values.optInt(key, restored.supportInfillMultiplier))
                SlicerSettings.Keys.SUPPORT_BRIM_MINIMUM_HOLE_AREA -> restored.copy(supportBrimMinimumHoleAreaMm2 = values.optDouble(key, restored.supportBrimMinimumHoleAreaMm2))
                SlicerSettings.Keys.MACHINE_TIME_ESTIMATION_FACTOR -> restored.copy(machineTimeEstimationFactorPercent = values.optDouble(key, restored.machineTimeEstimationFactorPercent))
                SlicerSettings.Keys.MINIMUM_INFILL_LINE_LENGTH -> restored.copy(minimumInfillLineLengthMm = values.optDouble(key, restored.minimumInfillLineLengthMm))
                else -> restored
            }
        }
        return restored
    }
}