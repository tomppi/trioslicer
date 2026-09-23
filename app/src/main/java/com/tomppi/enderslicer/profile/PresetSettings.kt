package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONObject
import kotlin.math.abs

object PresetSettings {
    private val printKeys: Set<String> = linkedSetOf(
        SlicerSettings.Keys.LAYER_HEIGHT,
        SlicerSettings.Keys.INITIAL_LAYER_HEIGHT,
        SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED,
        SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION,
        SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP,
        SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD,
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
        SlicerSettings.Keys.PRINT_SPEED,
        SlicerSettings.Keys.WALL_SPEED,
        SlicerSettings.Keys.OUTER_WALL_SPEED,
        SlicerSettings.Keys.INNER_WALL_SPEED,
        SlicerSettings.Keys.INFILL_SPEED,
        SlicerSettings.Keys.TOP_BOTTOM_SPEED,
        SlicerSettings.Keys.TRAVEL_SPEED,
        SlicerSettings.Keys.INITIAL_LAYER_SPEED,
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
        SlicerSettings.Keys.COMBING_MODE,
        SlicerSettings.Keys.AVOID_PRINTED_PARTS,
        SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE,
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
        SlicerSettings.Keys.BRICK_WALL_MAX_ITERATIONS,
        SlicerSettings.Keys.BRICK_WALL_BRICK_LENGTH,
        SlicerSettings.Keys.SMART_OVERHANG_STRATEGY,
        SlicerSettings.Keys.IRONING_ENABLED,
        SlicerSettings.Keys.IRONING_ONLY_HIGHEST_LAYER,
        SlicerSettings.Keys.IRONING_FLOW,
        SlicerSettings.Keys.IRONING_SPEED,
    )

    private val filamentKeys: Set<String> = linkedSetOf(
        SlicerSettings.Keys.FILAMENT_DIAMETER,
        SlicerSettings.Keys.NOZZLE_TEMPERATURE,
        SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE,
        SlicerSettings.Keys.BED_TEMPERATURE,
        SlicerSettings.Keys.BUILD_VOLUME_TEMPERATURE,
        SlicerSettings.Keys.MATERIAL_STANDBY_TEMPERATURE,
        SlicerSettings.Keys.MATERIAL_DENSITY,
        SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY,
        SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY,
        SlicerSettings.Keys.MATERIAL_FLOW,
        SlicerSettings.Keys.FAN_SPEED,
        SlicerSettings.Keys.INITIAL_FAN_SPEED,
        SlicerSettings.Keys.FAN_FULL_AT_LAYER,
        SlicerSettings.Keys.RETRACTION_DISTANCE,
        SlicerSettings.Keys.RETRACTION_SPEED,
        SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL,
        SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE,
        SlicerSettings.Keys.Z_HOP,
        SlicerSettings.Keys.Z_HOP_HEIGHT,
        SlicerSettings.Keys.FIRMWARE_RETRACTION,
        SlicerSettings.Keys.COASTING_ENABLED,
        SlicerSettings.Keys.COASTING_VOLUME,
        SlicerSettings.Keys.COASTING_MINIMUM_VOLUME,
        SlicerSettings.Keys.COASTING_SPEED,
    )

    // SlicerSettings constructor is near the JVM's 255-slot limit, so these
    // seven fields are grouped). JSON keys stay flat for preset compatibility.
    private val nestedBeadFields: Map<String, Pair<String, String>> = linkedMapOf(
    )

    private val fieldsByKey: Map<String, java.lang.reflect.Field> by lazy {
        (printKeys + filamentKeys).filterNot { it in nestedBeadFields }.associateWith { key ->
            SlicerSettings::class.java.getDeclaredField(key).apply { isAccessible = true }
        }
    }

    fun keys(kind: PresetKind): Set<String> = when (kind) {
        PresetKind.PRINT -> printKeys
        PresetKind.FILAMENT -> filamentKeys
    }

    fun capture(kind: PresetKind, settings: SlicerSettings): JSONObject {
        val output = JSONObject()
        keys(kind).sorted().forEach { key ->
            val value = when (val nested = nestedBeadFields[key]) {
                null -> fieldsByKey.getValue(key).get(settings)
                else -> {
                    val container = SlicerSettings::class.java.getDeclaredField(nested.first)
                        .apply { isAccessible = true }
                        .get(settings) as Any
                    SlicerSettings::class.java.getDeclaredField(nested.second)
                        .apply { isAccessible = true }
                        .get(container)
                }
            }
            output.put(key, value)
        }
        return output
    }

    fun apply(kind: PresetKind, current: SlicerSettings, values: JSONObject): SlicerSettings {
        var changed = current
        val appliedKeys = linkedSetOf<String>()
        keys(kind).forEach { key ->
            if (!values.has(key) || values.isNull(key)) return@forEach
            val raw = values.opt(key)
            if (!isCompatibleValue(key, raw)) return@forEach
            changed = when (key) {
                SlicerSettings.Keys.LAYER_HEIGHT -> changed.copy(layerHeightMm = values.optDouble(key, changed.layerHeightMm))
                SlicerSettings.Keys.INITIAL_LAYER_HEIGHT -> changed.copy(initialLayerHeightMm = values.optDouble(key, changed.initialLayerHeightMm))
                SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED -> changed.copy(adaptiveLayerHeightEnabled = values.optBoolean(key, changed.adaptiveLayerHeightEnabled))
                SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION -> changed.copy(adaptiveLayerHeightVariationMm = values.optDouble(key, changed.adaptiveLayerHeightVariationMm))
                SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP -> changed.copy(adaptiveLayerHeightVariationStepMm = values.optDouble(key, changed.adaptiveLayerHeightVariationStepMm))
                SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD -> changed.copy(adaptiveLayerHeightThreshold = values.optDouble(key, changed.adaptiveLayerHeightThreshold))
                SlicerSettings.Keys.LINE_WIDTH -> changed.copy(lineWidthMm = values.optDouble(key, changed.lineWidthMm))
                SlicerSettings.Keys.SLICING_TOLERANCE -> changed.copy(slicingTolerance = values.optString(key, changed.slicingTolerance))
                SlicerSettings.Keys.WALL_LINE_COUNT -> changed.copy(wallLineCount = values.optInt(key, changed.wallLineCount))
                SlicerSettings.Keys.WALL_THICKNESS -> changed.copy(wallThicknessMm = values.optDouble(key, changed.wallThicknessMm))
                SlicerSettings.Keys.TOP_LAYERS -> changed.copy(topLayers = values.optInt(key, changed.topLayers))
                SlicerSettings.Keys.BOTTOM_LAYERS -> changed.copy(bottomLayers = values.optInt(key, changed.bottomLayers))
                SlicerSettings.Keys.TOP_BOTTOM_THICKNESS -> changed.copy(topBottomThicknessMm = values.optDouble(key, changed.topBottomThicknessMm))
                SlicerSettings.Keys.TOP_SKIN_ANGLES -> changed.copy(topSkinAngles = values.optString(key, changed.topSkinAngles))
                SlicerSettings.Keys.TOP_BOTTOM_PATTERN -> changed.copy(topBottomPattern = values.optString(key, changed.topBottomPattern))
                SlicerSettings.Keys.INITIAL_BOTTOM_LAYERS -> changed.copy(initialBottomLayers = values.optInt(key, changed.initialBottomLayers))
                SlicerSettings.Keys.HOLE_HORIZONTAL_EXPANSION -> changed.copy(holeHorizontalExpansionMm = values.optDouble(key, changed.holeHorizontalExpansionMm))
                SlicerSettings.Keys.INITIAL_LAYER_HORIZONTAL_EXPANSION -> changed.copy(
                    initialLayerHorizontalExpansionMm = values.optDouble(key, changed.initialLayerHorizontalExpansionMm),
                )
                SlicerSettings.Keys.Z_SEAM_TYPE -> changed.copy(zSeamType = values.optString(key, changed.zSeamType))
                SlicerSettings.Keys.Z_SEAM_X -> changed.copy(zSeamXmm = values.optDouble(key, changed.zSeamXmm))
                SlicerSettings.Keys.Z_SEAM_Y -> changed.copy(zSeamYmm = values.optDouble(key, changed.zSeamYmm))
                SlicerSettings.Keys.Z_SEAM_RELATIVE -> changed.copy(zSeamRelative = values.optBoolean(key, changed.zSeamRelative))
                SlicerSettings.Keys.Z_SEAM_CORNER -> changed.copy(zSeamCorner = values.optString(key, changed.zSeamCorner))
                SlicerSettings.Keys.INFILL_DENSITY -> changed.copy(infillDensityPercent = values.optDouble(key, changed.infillDensityPercent))
                SlicerSettings.Keys.INFILL_PATTERN -> changed.copy(infillPattern = values.optString(key, changed.infillPattern))
                SlicerSettings.Keys.ZIG_ZAG_CONNECT_INFILL -> changed.copy(zigZagConnectInfill = values.optBoolean(key, changed.zigZagConnectInfill))
                SlicerSettings.Keys.PRINT_SPEED -> changed.copy(printSpeedMmPerSecond = values.optDouble(key, changed.printSpeedMmPerSecond))
                SlicerSettings.Keys.WALL_SPEED -> changed.copy(wallSpeedMmPerSecond = values.optDouble(key, changed.wallSpeedMmPerSecond))
                SlicerSettings.Keys.OUTER_WALL_SPEED -> changed.copy(outerWallSpeedMmPerSecond = values.optDouble(key, changed.outerWallSpeedMmPerSecond))
                SlicerSettings.Keys.INNER_WALL_SPEED -> changed.copy(innerWallSpeedMmPerSecond = values.optDouble(key, changed.innerWallSpeedMmPerSecond))
                SlicerSettings.Keys.INFILL_SPEED -> changed.copy(infillSpeedMmPerSecond = values.optDouble(key, changed.infillSpeedMmPerSecond))
                SlicerSettings.Keys.TOP_BOTTOM_SPEED -> changed.copy(topBottomSpeedMmPerSecond = values.optDouble(key, changed.topBottomSpeedMmPerSecond))
                SlicerSettings.Keys.TRAVEL_SPEED -> changed.copy(travelSpeedMmPerSecond = values.optDouble(key, changed.travelSpeedMmPerSecond))
                SlicerSettings.Keys.INITIAL_LAYER_SPEED -> changed.copy(initialLayerSpeedMmPerSecond = values.optDouble(key, changed.initialLayerSpeedMmPerSecond))
                SlicerSettings.Keys.SUPPORTS_ENABLED -> changed.copy(supportsEnabled = values.optBoolean(key, changed.supportsEnabled))
                SlicerSettings.Keys.SUPPORT_PLACEMENT -> changed.copy(supportPlacement = values.optString(key, changed.supportPlacement))
                SlicerSettings.Keys.SUPPORT_STRUCTURE -> changed.copy(supportStructure = values.optString(key, changed.supportStructure))
                SlicerSettings.Keys.SUPPORT_ANGLE -> changed.copy(supportAngleDegrees = values.optDouble(key, changed.supportAngleDegrees))
                SlicerSettings.Keys.SUPPORT_DENSITY -> changed.copy(supportDensityPercent = values.optDouble(key, changed.supportDensityPercent))
                SlicerSettings.Keys.SUPPORT_PATTERN -> changed.copy(supportPattern = values.optString(key, changed.supportPattern))
                SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED -> changed.copy(supportInterfaceEnabled = values.optBoolean(key, changed.supportInterfaceEnabled))
                SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY -> changed.copy(supportInterfaceDensityPercent = values.optDouble(key, changed.supportInterfaceDensityPercent))
                SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT -> changed.copy(supportInterfaceHeightMm = values.optDouble(key, changed.supportInterfaceHeightMm))
                SlicerSettings.Keys.SUPPORT_Z_DISTANCE -> changed.copy(supportZDistanceMm = values.optDouble(key, changed.supportZDistanceMm))
                SlicerSettings.Keys.SUPPORT_XY_DISTANCE -> changed.copy(supportXyDistanceMm = values.optDouble(key, changed.supportXyDistanceMm))
                SlicerSettings.Keys.SUPPORT_SPEED -> changed.copy(supportSpeedMmPerSecond = values.optDouble(key, changed.supportSpeedMmPerSecond))
                SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED -> changed.copy(supportInterfaceSpeedMmPerSecond = values.optDouble(key, changed.supportInterfaceSpeedMmPerSecond))
                SlicerSettings.Keys.COMBING_MODE -> changed.copy(combingMode = values.optString(key, changed.combingMode))
                SlicerSettings.Keys.AVOID_PRINTED_PARTS -> changed.copy(avoidPrintedParts = values.optBoolean(key, changed.avoidPrintedParts))
                SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE -> changed.copy(travelAvoidDistanceMm = values.optDouble(key, changed.travelAvoidDistanceMm))
                SlicerSettings.Keys.ADHESION_TYPE -> changed.copy(adhesionType = values.optString(key, changed.adhesionType))
                SlicerSettings.Keys.SKIRT_LINE_COUNT -> changed.copy(skirtLineCount = values.optInt(key, changed.skirtLineCount))
                SlicerSettings.Keys.BRIM_WIDTH -> changed.copy(brimWidthMm = values.optDouble(key, changed.brimWidthMm))
                SlicerSettings.Keys.RAFT_MARGIN -> changed.copy(raftMarginMm = values.optDouble(key, changed.raftMarginMm))
                SlicerSettings.Keys.ARC_OVERHANG_ENABLED -> changed.copy(arcOverhangEnabled = values.optBoolean(key, changed.arcOverhangEnabled))
                SlicerSettings.Keys.ARC_OVERHANG_SPEED -> changed.copy(arcOverhangSpeedMmPerSecond = values.optDouble(key, changed.arcOverhangSpeedMmPerSecond))
                SlicerSettings.Keys.ARC_OVERHANG_FLOW -> changed.copy(arcOverhangFlowPercent = values.optDouble(key, changed.arcOverhangFlowPercent))
                SlicerSettings.Keys.ARC_OVERHANG_LINE_SPACING -> changed.copy(arcOverhangLineSpacingPercent = values.optDouble(key, changed.arcOverhangLineSpacingPercent))
                SlicerSettings.Keys.ARC_OVERHANG_MIN_RADIUS -> changed.copy(arcOverhangMinRadiusMm = values.optDouble(key, changed.arcOverhangMinRadiusMm))
                SlicerSettings.Keys.ARC_OVERHANG_MAX_RADIUS -> changed.copy(arcOverhangMaxRadiusMm = values.optDouble(key, changed.arcOverhangMaxRadiusMm))
                SlicerSettings.Keys.ARC_OVERHANG_MAX_AREA -> changed.copy(arcOverhangMaxAreaMm2 = values.optDouble(key, changed.arcOverhangMaxAreaMm2))
                SlicerSettings.Keys.ARC_OVERHANG_RESOLUTION -> changed.copy(arcOverhangResolutionMm = values.optDouble(key, changed.arcOverhangResolutionMm))
                SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED -> changed.copy(arcOverhangFanSpeedPercent = values.optDouble(key, changed.arcOverhangFanSpeedPercent))
                SlicerSettings.Keys.WAVE_OVERHANG_ENABLED -> changed.copy(waveOverhangEnabled = values.optBoolean(key, changed.waveOverhangEnabled))
                SlicerSettings.Keys.WAVE_OVERHANG_PATTERN -> changed.copy(waveOverhangPattern = values.optString(key, changed.waveOverhangPattern))
                SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING -> changed.copy(waveOverhangLineSpacingMm = values.optDouble(key, changed.waveOverhangLineSpacingMm))
                SlicerSettings.Keys.WAVE_OVERHANG_FLOW -> changed.copy(waveOverhangFlowMm3PerMm = values.optDouble(key, changed.waveOverhangFlowMm3PerMm))
                SlicerSettings.Keys.WAVE_OVERHANG_SPEED -> changed.copy(waveOverhangSpeedMmPerSecond = values.optDouble(key, changed.waveOverhangSpeedMmPerSecond))
                SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED -> changed.copy(waveOverhangFanSpeedPercent = values.optDouble(key, changed.waveOverhangFanSpeedPercent))
                SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP -> changed.copy(waveOverhangPerimeterOverlapMm = values.optDouble(key, changed.waveOverhangPerimeterOverlapMm))
                SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH -> changed.copy(waveOverhangMinimumWidthMm = values.optDouble(key, changed.waveOverhangMinimumWidthMm))
                SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS -> changed.copy(waveOverhangMaxIterations = values.optInt(key, changed.waveOverhangMaxIterations))
                SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS -> changed.copy(waveOverhangReverseOddLayers = values.optBoolean(key, changed.waveOverhangReverseOddLayers))
                SlicerSettings.Keys.BRICK_WALL_ENABLED -> changed.copy(brickWallEnabled = values.optBoolean(key, changed.brickWallEnabled))
                SlicerSettings.Keys.BRICK_WALL_SPEED -> changed.copy(brickWallSpeedMmPerSecond = values.optDouble(key, changed.brickWallSpeedMmPerSecond))
                SlicerSettings.Keys.BRICK_WALL_FLOW -> changed.copy(brickWallFlowPercent = values.optDouble(key, changed.brickWallFlowPercent))
                SlicerSettings.Keys.BRICK_WALL_FAN_SPEED -> changed.copy(brickWallFanSpeedPercent = values.optDouble(key, changed.brickWallFanSpeedPercent))
                SlicerSettings.Keys.BRICK_WALL_MAX_ITERATIONS -> changed.copy(brickWallMaxIterations = values.optInt(key, changed.brickWallMaxIterations))
                SlicerSettings.Keys.BRICK_WALL_BRICK_LENGTH -> changed.copy(brickWallBrickLengthMm = values.optDouble(key, changed.brickWallBrickLengthMm))
                SlicerSettings.Keys.SMART_OVERHANG_STRATEGY -> changed.copy(smartOverhangStrategy = values.optBoolean(key, changed.smartOverhangStrategy))
                SlicerSettings.Keys.IRONING_ENABLED -> changed.copy(ironingEnabled = values.optBoolean(key, changed.ironingEnabled))
                SlicerSettings.Keys.IRONING_ONLY_HIGHEST_LAYER -> changed.copy(ironingOnlyHighestLayer = values.optBoolean(key, changed.ironingOnlyHighestLayer))
                SlicerSettings.Keys.IRONING_FLOW -> changed.copy(ironingFlowPercent = values.optDouble(key, changed.ironingFlowPercent))
                SlicerSettings.Keys.IRONING_SPEED -> changed.copy(ironingSpeedMmPerSecond = values.optDouble(key, changed.ironingSpeedMmPerSecond))
                SlicerSettings.Keys.FILAMENT_DIAMETER -> changed.copy(filamentDiameterMm = values.optDouble(key, changed.filamentDiameterMm))
                SlicerSettings.Keys.NOZZLE_TEMPERATURE -> changed.copy(nozzleTemperatureC = values.optInt(key, changed.nozzleTemperatureC))
                SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE -> changed.copy(initialNozzleTemperatureC = values.optInt(key, changed.initialNozzleTemperatureC))
                SlicerSettings.Keys.BED_TEMPERATURE -> changed.copy(bedTemperatureC = values.optInt(key, changed.bedTemperatureC))
                SlicerSettings.Keys.BUILD_VOLUME_TEMPERATURE -> changed.copy(buildVolumeTemperatureC = values.optDouble(key, changed.buildVolumeTemperatureC))
                SlicerSettings.Keys.MATERIAL_STANDBY_TEMPERATURE -> changed.copy(materialStandbyTemperatureC = values.optDouble(key, changed.materialStandbyTemperatureC))
                SlicerSettings.Keys.MATERIAL_DENSITY -> changed.copy(materialDensityGPerCm3 = values.optDouble(key, changed.materialDensityGPerCm3))
                SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY -> changed.copy(materialAdhesionTendency = values.optInt(key, changed.materialAdhesionTendency))
                SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY -> changed.copy(materialSurfaceEnergyPercent = values.optInt(key, changed.materialSurfaceEnergyPercent))
                SlicerSettings.Keys.MATERIAL_FLOW -> changed.copy(materialFlowPercent = values.optDouble(key, changed.materialFlowPercent))
                SlicerSettings.Keys.FAN_SPEED -> changed.copy(fanSpeedPercent = values.optDouble(key, changed.fanSpeedPercent))
                SlicerSettings.Keys.INITIAL_FAN_SPEED -> changed.copy(initialFanSpeedPercent = values.optDouble(key, changed.initialFanSpeedPercent))
                SlicerSettings.Keys.FAN_FULL_AT_LAYER -> changed.copy(fanFullAtLayer = values.optInt(key, changed.fanFullAtLayer))
                SlicerSettings.Keys.RETRACTION_DISTANCE -> changed.copy(retractionDistanceMm = values.optDouble(key, changed.retractionDistanceMm))
                SlicerSettings.Keys.RETRACTION_SPEED -> changed.copy(retractionSpeedMmPerSecond = values.optDouble(key, changed.retractionSpeedMmPerSecond))
                SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL -> changed.copy(retractionMinimumTravelMm = values.optDouble(key, changed.retractionMinimumTravelMm))
                SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE -> changed.copy(retractAtLayerChange = values.optBoolean(key, changed.retractAtLayerChange))
                SlicerSettings.Keys.Z_HOP -> changed.copy(zHopEnabled = values.optBoolean(key, changed.zHopEnabled))
                SlicerSettings.Keys.Z_HOP_HEIGHT -> changed.copy(zHopHeightMm = values.optDouble(key, changed.zHopHeightMm))
                SlicerSettings.Keys.FIRMWARE_RETRACTION -> changed.copy(firmwareRetraction = values.optBoolean(key, changed.firmwareRetraction))
                SlicerSettings.Keys.COASTING_ENABLED -> changed.copy(coastingEnabled = values.optBoolean(key, changed.coastingEnabled))
                SlicerSettings.Keys.COASTING_VOLUME -> changed.copy(coastingVolumeMm3 = values.optDouble(key, changed.coastingVolumeMm3))
                SlicerSettings.Keys.COASTING_MINIMUM_VOLUME -> changed.copy(coastingMinimumVolumeMm3 = values.optDouble(key, changed.coastingMinimumVolumeMm3))
                SlicerSettings.Keys.COASTING_SPEED -> changed.copy(coastingSpeedPercent = values.optDouble(key, changed.coastingSpeedPercent))
                else -> changed
            }
            appliedKeys += key
        }
        require(appliedKeys.isNotEmpty()) { "The preset has no usable ${kind.label.lowercase()} values" }
        require(!(changed.arcOverhangEnabled && changed.waveOverhangEnabled)) {
            "Arc and Wave overhangs cannot both be enabled"
        }
        require(!(changed.brickWallEnabled && (changed.arcOverhangEnabled || changed.waveOverhangEnabled))) {
            "Brick walls cannot be combined with Arc or Wave overhangs"
        }
        require(!(changed.masonryWallsEnabled && (changed.arcOverhangEnabled || changed.waveOverhangEnabled || changed.brickWallEnabled))) {
            "Masonry walls cannot be combined with Arc, Wave or Brick-wall overhangs"
        }
        return changed.copy(overriddenSettingKeys = changed.overriddenSettingKeys + appliedKeys)
    }

    fun matches(kind: PresetKind, settings: SlicerSettings, values: JSONObject): Boolean =
        matchesValues(kind, capture(kind, settings), values)

    fun matchesValues(kind: PresetKind, currentValues: JSONObject, savedValues: JSONObject): Boolean {
        return keys(kind).all { key ->
            if (!currentValues.has(key) || currentValues.isNull(key)) return@all false
            if (!savedValues.has(key) || savedValues.isNull(key)) return@all false
            val saved = savedValues.opt(key)
            if (!isCompatibleValue(key, saved)) return@all false
            equivalent(currentValues.opt(key), saved)
        }
    }

    fun validateUsable(kind: PresetKind, values: JSONObject) {
        require(
            keys(kind).any { key -> values.has(key) && !values.isNull(key) && isCompatibleValue(key, values.opt(key)) },
        ) { "The preset has no usable ${kind.label.lowercase()} values" }
    }

    fun validateComplete(kind: PresetKind, values: JSONObject) {
        val missing = keys(kind).filterNot { key ->
            values.has(key) && !values.isNull(key) && isCompatibleValue(key, values.opt(key))
        }
        require(missing.isEmpty()) { "Preset is missing ${missing.size} required values" }
    }

    private fun isCompatibleValue(key: String, value: Any?): Boolean {
        if (value == null || value == JSONObject.NULL) return false
        val type = fieldsByKey.getValue(key).type
        return when (type) {
            java.lang.Double.TYPE -> value is Number
            java.lang.Integer.TYPE -> value is Number
            java.lang.Boolean.TYPE -> value is Boolean
            String::class.java -> value is String
            else -> false
        }
    }

    private fun equivalent(current: Any?, saved: Any?): Boolean = when {
        current is Number && saved is Number -> abs(current.toDouble() - saved.toDouble()) <= 0.000_001
        else -> current == saved
    }
}