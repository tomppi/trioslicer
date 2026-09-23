package com.tomppi.enderslicer.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil

data class SlicerSettings(
    val printerName: String = "Modified Ender 3 V2",
    val machineWidthMm: Double = 230.0,
    val machineDepthMm: Double = 230.0,
    val machineHeightMm: Double = 250.0,
    val buildPlateShape: String = "rectangular",
    val originAtCenter: Boolean = false,
    val heatedBed: Boolean = true,
    val heatedBuildVolume: Boolean = false,
    val gcodeFlavor: String = "Marlin",
    val nozzleSizeMm: Double = 0.4,
    val filamentDiameterMm: Double = 1.75,
    val printheadXMinMm: Double = -26.0,
    val printheadYMinMm: Double = -32.0,
    val printheadXMaxMm: Double = 32.0,
    val printheadYMaxMm: Double = 34.0,
    val gantryHeightMm: Double = 25.0,
    val customStartGcodeEnabled: Boolean = false,
    val customStartGcode: String = "",
    val customEndGcodeEnabled: Boolean = false,
    val customEndGcode: String = "",
    val layerHeightMm: Double = 0.20,
    val initialLayerHeightMm: Double = 0.28,
    val adaptiveLayerHeightEnabled: Boolean = false,
    val adaptiveLayerHeightVariationMm: Double = 0.10,
    val adaptiveLayerHeightVariationStepMm: Double = 0.01,
    val adaptiveLayerHeightThreshold: Double = 0.20,
    val adaptiveMeshLevelingEnabled: Boolean = false,
    val amlMarginMm: Double = 5.0,
    val amlGridPoints: Int = 6,
    val lineWidthMm: Double = 0.40,
    val slicingTolerance: String = "middle",
    val wallLineCount: Int = 2,
    val wallThicknessMm: Double = 0.8,
    val topLayers: Int = 4,
    val bottomLayers: Int = 4,
    val topBottomThicknessMm: Double = 0.8,
    val topSkinAngles: String = "45,135",
    val topBottomPattern: String = "lines",
    val initialBottomLayers: Int = 4,
    val holeHorizontalExpansionMm: Double = 0.0,
    val initialLayerHorizontalExpansionMm: Double = 0.0,
    val zSeamType: String = "sharpest_corner",
    val zSeamXmm: Double = 115.0,
    val zSeamYmm: Double = 230.0,
    val zSeamRelative: Boolean = false,
    val zSeamCorner: String = "z_seam_corner_inner",
    val infillDensityPercent: Double = 10.0,
    val infillPattern: String = "cubic",
    val zigZagConnectInfill: Boolean = true,
    val thicknessAdaptiveWallsEnabled: Boolean = false,
    val thicknessAdaptiveWallsFlowPercent: Double = 100.0,
    val thicknessAdaptiveWallsBendRadiusMm: Double = 12.0,
    val thicknessAdaptiveWallsExtraWalls: Int = 4,
    val printSpeedMmPerSecond: Double = 200.0,
    val wallSpeedMmPerSecond: Double = 100.0,
    val outerWallSpeedMmPerSecond: Double = 100.0,
    val innerWallSpeedMmPerSecond: Double = 100.0,
    val infillSpeedMmPerSecond: Double = 200.0,
    val topBottomSpeedMmPerSecond: Double = 100.0,
    val travelSpeedMmPerSecond: Double = 250.0,
    val initialLayerSpeedMmPerSecond: Double = 30.0,
    val nozzleTemperatureC: Int = 210,
    val initialNozzleTemperatureC: Int = 210,
    val bedTemperatureC: Int = 60,
    val buildVolumeTemperatureC: Double = 28.0,
    val materialStandbyTemperatureC: Double = 180.0,
    val materialDensityGPerCm3: Double = 1.24,
    val materialAdhesionTendency: Int = 0,
    val materialSurfaceEnergyPercent: Int = 100,
    val materialBrand: String = "Generic",
    val materialType: String = "PLA",
    val materialGuid: String = "",
    val enabledExtruderCount: Int = 1,
    val materialFlowPercent: Double = 100.0,
    val fanSpeedPercent: Double = 100.0,
    val initialFanSpeedPercent: Double = 0.0,
    val fanFullAtLayer: Int = 4,
    val supportsEnabled: Boolean = true,
    val supportPlacement: String = "everywhere",
    val supportStructure: String = "tree",
    val supportAngleDegrees: Double = 56.0,
    val supportDensityPercent: Double = 15.0,
    val supportPattern: String = "zigzag",
    val supportInterfaceEnabled: Boolean = true,
    val supportInterfaceDensityPercent: Double = 100.0,
    val supportInterfaceHeightMm: Double = 0.8,
    val supportZDistanceMm: Double = 0.20,
    val supportXyDistanceMm: Double = 0.80,
    val supportSpeedMmPerSecond: Double = 100.0,
    val supportInterfaceSpeedMmPerSecond: Double = 100.0,
    val retractionDistanceMm: Double = 1.5,
    val retractionSpeedMmPerSecond: Double = 120.0,
    val retractionMinimumTravelMm: Double = 1.5,
    val retractAtLayerChange: Boolean = true,
    val combingMode: String = "all",
    val avoidPrintedParts: Boolean = false,
    val travelAvoidDistanceMm: Double = 0.625,
    val zHopEnabled: Boolean = false,
    val zHopHeightMm: Double = 0.2,
    val firmwareRetraction: Boolean = true,
    val coastingEnabled: Boolean = false,
    val coastingVolumeMm3: Double = 0.064,
    val coastingMinimumVolumeMm3: Double = 0.8,
    val coastingSpeedPercent: Double = 90.0,
    val adhesionType: String = "none",
    val skirtLineCount: Int = 1,
    val brimWidthMm: Double = 8.0,
    val raftMarginMm: Double = 10.0,
    val arcOverhangEnabled: Boolean = false,
    val arcOverhangSpeedMmPerSecond: Double = 5.0,
    val arcOverhangFlowPercent: Double = 105.0,
    val arcOverhangLineSpacingPercent: Double = 100.0,
    val arcOverhangMinRadiusMm: Double = 0.6,
    val arcOverhangMaxRadiusMm: Double = 30.0,
    val arcOverhangMaxAreaMm2: Double = 1200.0,
    val arcOverhangResolutionMm: Double = 0.15,
    val arcOverhangFanSpeedPercent: Double = 100.0,
    val waveOverhangEnabled: Boolean = false,
    val waveOverhangPattern: String = "smart",
    val waveOverhangLineSpacingMm: Double = 0.35,
    val waveOverhangFlowMm3PerMm: Double = 0.16,
    val waveOverhangSpeedMmPerSecond: Double = 5.0,
    val waveOverhangFanSpeedPercent: Double = 100.0,
    val waveOverhangPerimeterOverlapMm: Double = 0.10,
    val waveOverhangMinimumWidthMm: Double = 0.70,
    val waveOverhangMaxIterations: Int = 400,
    val waveOverhangReverseOddLayers: Boolean = true,
    val brickWallEnabled: Boolean = false,
    val brickWallSpeedMmPerSecond: Double = 25.0,
    val brickWallFlowPercent: Double = 105.0,
    val brickWallFanSpeedPercent: Double = 100.0,
    val brickWallMaxIterations: Int = 60,
    val brickWallBrickLengthMm: Double = 1.6,
    val masonryWallsEnabled: Boolean = false,
    val smartOverhangStrategy: Boolean = false,
    val ironingEnabled: Boolean = false,
    val ironingOnlyHighestLayer: Boolean = false,
    val ironingFlowPercent: Double = 10.0,
    val ironingSpeedMmPerSecond: Double = 20.0,
    val initialLayerInsetDirection: String = "inside_out",
    val travelRetractBeforeOuterWall: String = "automatic",
    val infillStartEndPreference: String = "start_closest",
    val infillMoveInwardsLengthMm: Double = 0.0,
    val roofingExpansionMm: Double = 0.0,
    val topBottomSkinMergeDistanceMm: Double = 1.2,
    val skinSupportEnabled: Boolean = true,
    val skinSupportDensityPercent: Double = 100.0,
    val skinSupportSpeedMmPerSecond: Double = 15.0,
    val skinSupportMaterialFlowPercent: Double = 60.0,
    val skinSupportFanSpeedPercent: Double = 100.0,
    val bridgeInterlaceLines: Boolean = false,
    val supportInfillMultiplier: Int = 1,
    val supportBrimMinimumHoleAreaMm2: Double = 16.0,
    val machineTimeEstimationFactorPercent: Double = 100.0,
    val minimumInfillLineLengthMm: Double = 0.0,
    val overriddenSettingKeys: Set<String> = emptySet(),
) {
    fun isOverridden(key: String): Boolean = key in overriddenSettingKeys

    fun withRecomputedDerived(): SlicerSettings {
        var result = this
        if (!isOverridden(Keys.WALL_THICKNESS)) {
            result = result.copy(wallThicknessMm = wallLineCount * lineWidthMm)
        }
        val layerCount = if (result.layerHeightMm > 0.0) {
            val ratio = BigDecimal.valueOf(result.topBottomThicknessMm / result.layerHeightMm)
                .setScale(4, RoundingMode.HALF_EVEN)
                .toDouble()
            ceil(ratio).toInt().coerceAtLeast(0)
        } else {
            null
        }
        if (layerCount != null) {
            if (!isOverridden(Keys.TOP_LAYERS)) {
                result = result.copy(topLayers = layerCount)
            }
            if (!isOverridden(Keys.BOTTOM_LAYERS)) {
                result = result.copy(bottomLayers = layerCount)
            }
        }
        if (!isOverridden(Keys.INITIAL_BOTTOM_LAYERS)) {
            result = result.copy(initialBottomLayers = result.bottomLayers)
        }
        if (!isOverridden(Keys.WALL_SPEED)) {
            result = result.copy(wallSpeedMmPerSecond = result.printSpeedMmPerSecond / 2.0)
        }
        if (!isOverridden(Keys.OUTER_WALL_SPEED)) {
            result = result.copy(outerWallSpeedMmPerSecond = result.wallSpeedMmPerSecond)
        }
        if (!isOverridden(Keys.INNER_WALL_SPEED)) {
            result = result.copy(innerWallSpeedMmPerSecond = result.wallSpeedMmPerSecond)
        }
        if (!isOverridden(Keys.INFILL_SPEED)) {
            result = result.copy(infillSpeedMmPerSecond = result.printSpeedMmPerSecond)
        }
        if (!isOverridden(Keys.TOP_BOTTOM_SPEED)) {
            result = result.copy(topBottomSpeedMmPerSecond = result.printSpeedMmPerSecond / 2.0)
        }
        if (!isOverridden(Keys.SUPPORT_SPEED)) {
            result = result.copy(supportSpeedMmPerSecond = result.wallSpeedMmPerSecond)
        }
        if (!isOverridden(Keys.SUPPORT_INTERFACE_SPEED)) {
            result = result.copy(supportInterfaceSpeedMmPerSecond = result.supportSpeedMmPerSecond)
        }
        if (!isOverridden(Keys.INITIAL_NOZZLE_TEMPERATURE)) {
            result = result.copy(initialNozzleTemperatureC = result.nozzleTemperatureC)
        }
        return result
    }

    object Keys {
        const val PRINTER_NAME = "printerName"
        const val MACHINE_WIDTH = "machineWidthMm"
        const val MACHINE_DEPTH = "machineDepthMm"
        const val MACHINE_HEIGHT = "machineHeightMm"
        const val BUILD_PLATE_SHAPE = "buildPlateShape"
        const val ORIGIN_AT_CENTER = "originAtCenter"
        const val HEATED_BED = "heatedBed"
        const val HEATED_BUILD_VOLUME = "heatedBuildVolume"
        const val GCODE_FLAVOR = "gcodeFlavor"
        const val NOZZLE_SIZE = "nozzleSizeMm"
        const val FILAMENT_DIAMETER = "filamentDiameterMm"
        const val ENABLED_EXTRUDER_COUNT = "enabledExtruderCount"
        const val PRINTHEAD_X_MIN = "printheadXMinMm"
        const val PRINTHEAD_Y_MIN = "printheadYMinMm"
        const val PRINTHEAD_X_MAX = "printheadXMaxMm"
        const val PRINTHEAD_Y_MAX = "printheadYMaxMm"
        const val GANTRY_HEIGHT = "gantryHeightMm"
        const val CUSTOM_START_GCODE_ENABLED = "customStartGcodeEnabled"
        const val CUSTOM_START_GCODE = "customStartGcode"
        const val CUSTOM_END_GCODE_ENABLED = "customEndGcodeEnabled"
        const val CUSTOM_END_GCODE = "customEndGcode"
        const val LAYER_HEIGHT = "layerHeightMm"
        const val INITIAL_LAYER_HEIGHT = "initialLayerHeightMm"
        const val ADAPTIVE_LAYER_HEIGHT_ENABLED = "adaptiveLayerHeightEnabled"
        const val ADAPTIVE_LAYER_HEIGHT_VARIATION = "adaptiveLayerHeightVariationMm"
        const val ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP = "adaptiveLayerHeightVariationStepMm"
        const val ADAPTIVE_LAYER_HEIGHT_THRESHOLD = "adaptiveLayerHeightThreshold"
        const val ADAPTIVE_MESH_LEVELING_ENABLED = "adaptiveMeshLevelingEnabled"
        const val AML_MARGIN_MM = "amlMarginMm"
        const val AML_GRID_POINTS = "amlGridPoints"
        const val LINE_WIDTH = "lineWidthMm"
        const val SLICING_TOLERANCE = "slicingTolerance"
        const val WALL_LINE_COUNT = "wallLineCount"
        const val WALL_THICKNESS = "wallThicknessMm"
        const val TOP_LAYERS = "topLayers"
        const val BOTTOM_LAYERS = "bottomLayers"
        const val TOP_BOTTOM_THICKNESS = "topBottomThicknessMm"
        const val TOP_SKIN_ANGLES = "topSkinAngles"
        const val TOP_BOTTOM_PATTERN = "topBottomPattern"
        const val INITIAL_BOTTOM_LAYERS = "initialBottomLayers"
        const val HOLE_HORIZONTAL_EXPANSION = "holeHorizontalExpansionMm"
        const val INITIAL_LAYER_HORIZONTAL_EXPANSION = "initialLayerHorizontalExpansionMm"
        const val Z_SEAM_TYPE = "zSeamType"
        const val Z_SEAM_X = "zSeamXmm"
        const val Z_SEAM_Y = "zSeamYmm"
        const val Z_SEAM_RELATIVE = "zSeamRelative"
        const val Z_SEAM_CORNER = "zSeamCorner"
        const val INFILL_DENSITY = "infillDensityPercent"
        const val INFILL_PATTERN = "infillPattern"
        const val ZIG_ZAG_CONNECT_INFILL = "zigZagConnectInfill"
        const val THICKNESS_ADAPTIVE_WALLS_ENABLED = "thicknessAdaptiveWallsEnabled"
        const val THICKNESS_ADAPTIVE_WALLS_FLOW = "thicknessAdaptiveWallsFlowPercent"
        const val THICKNESS_ADAPTIVE_WALLS_BEND_RADIUS = "thicknessAdaptiveWallsBendRadiusMm"
        const val THICKNESS_ADAPTIVE_WALLS_EXTRA_WALLS = "thicknessAdaptiveWallsExtraWalls"
        const val PRINT_SPEED = "printSpeedMmPerSecond"
        const val WALL_SPEED = "wallSpeedMmPerSecond"
        const val OUTER_WALL_SPEED = "outerWallSpeedMmPerSecond"
        const val INNER_WALL_SPEED = "innerWallSpeedMmPerSecond"
        const val INFILL_SPEED = "infillSpeedMmPerSecond"
        const val TOP_BOTTOM_SPEED = "topBottomSpeedMmPerSecond"
        const val TRAVEL_SPEED = "travelSpeedMmPerSecond"
        const val INITIAL_LAYER_SPEED = "initialLayerSpeedMmPerSecond"
        const val NOZZLE_TEMPERATURE = "nozzleTemperatureC"
        const val INITIAL_NOZZLE_TEMPERATURE = "initialNozzleTemperatureC"
        const val BED_TEMPERATURE = "bedTemperatureC"
        const val BUILD_VOLUME_TEMPERATURE = "buildVolumeTemperatureC"
        const val MATERIAL_STANDBY_TEMPERATURE = "materialStandbyTemperatureC"
        const val MATERIAL_DENSITY = "materialDensityGPerCm3"
        const val MATERIAL_ADHESION_TENDENCY = "materialAdhesionTendency"
        const val MATERIAL_SURFACE_ENERGY = "materialSurfaceEnergyPercent"
        const val MATERIAL_FLOW = "materialFlowPercent"
        const val FAN_SPEED = "fanSpeedPercent"
        const val INITIAL_FAN_SPEED = "initialFanSpeedPercent"
        const val FAN_FULL_AT_LAYER = "fanFullAtLayer"
        const val SUPPORTS_ENABLED = "supportsEnabled"
        const val SUPPORT_PLACEMENT = "supportPlacement"
        const val SUPPORT_STRUCTURE = "supportStructure"
        const val SUPPORT_ANGLE = "supportAngleDegrees"
        const val SUPPORT_DENSITY = "supportDensityPercent"
        const val SUPPORT_PATTERN = "supportPattern"
        const val SUPPORT_INTERFACE_ENABLED = "supportInterfaceEnabled"
        const val SUPPORT_INTERFACE_DENSITY = "supportInterfaceDensityPercent"
        const val SUPPORT_INTERFACE_HEIGHT = "supportInterfaceHeightMm"
        const val SUPPORT_Z_DISTANCE = "supportZDistanceMm"
        const val SUPPORT_XY_DISTANCE = "supportXyDistanceMm"
        const val SUPPORT_SPEED = "supportSpeedMmPerSecond"
        const val SUPPORT_INTERFACE_SPEED = "supportInterfaceSpeedMmPerSecond"
        const val RETRACTION_DISTANCE = "retractionDistanceMm"
        const val RETRACTION_SPEED = "retractionSpeedMmPerSecond"
        const val RETRACTION_MINIMUM_TRAVEL = "retractionMinimumTravelMm"
        const val RETRACT_AT_LAYER_CHANGE = "retractAtLayerChange"
        const val COMBING_MODE = "combingMode"
        const val AVOID_PRINTED_PARTS = "avoidPrintedParts"
        const val TRAVEL_AVOID_DISTANCE = "travelAvoidDistanceMm"
        const val Z_HOP = "zHopEnabled"
        const val Z_HOP_HEIGHT = "zHopHeightMm"
        const val FIRMWARE_RETRACTION = "firmwareRetraction"
        const val COASTING_ENABLED = "coastingEnabled"
        const val COASTING_VOLUME = "coastingVolumeMm3"
        const val COASTING_MINIMUM_VOLUME = "coastingMinimumVolumeMm3"
        const val COASTING_SPEED = "coastingSpeedPercent"
        const val ADHESION_TYPE = "adhesionType"
        const val SKIRT_LINE_COUNT = "skirtLineCount"
        const val BRIM_WIDTH = "brimWidthMm"
        const val RAFT_MARGIN = "raftMarginMm"
        const val ARC_OVERHANG_ENABLED = "arcOverhangEnabled"
        const val ARC_OVERHANG_SPEED = "arcOverhangSpeedMmPerSecond"
        const val ARC_OVERHANG_FLOW = "arcOverhangFlowPercent"
        const val ARC_OVERHANG_LINE_SPACING = "arcOverhangLineSpacingPercent"
        const val ARC_OVERHANG_MIN_RADIUS = "arcOverhangMinRadiusMm"
        const val ARC_OVERHANG_MAX_RADIUS = "arcOverhangMaxRadiusMm"
        const val ARC_OVERHANG_MAX_AREA = "arcOverhangMaxAreaMm2"
        const val ARC_OVERHANG_RESOLUTION = "arcOverhangResolutionMm"
        const val ARC_OVERHANG_FAN_SPEED = "arcOverhangFanSpeedPercent"
        const val WAVE_OVERHANG_ENABLED = "waveOverhangEnabled"
        const val WAVE_OVERHANG_PATTERN = "waveOverhangPattern"
        const val WAVE_OVERHANG_LINE_SPACING = "waveOverhangLineSpacingMm"
        const val WAVE_OVERHANG_FLOW = "waveOverhangFlowMm3PerMm"
        const val WAVE_OVERHANG_SPEED = "waveOverhangSpeedMmPerSecond"
        const val WAVE_OVERHANG_FAN_SPEED = "waveOverhangFanSpeedPercent"
        const val WAVE_OVERHANG_PERIMETER_OVERLAP = "waveOverhangPerimeterOverlapMm"
        const val WAVE_OVERHANG_MINIMUM_WIDTH = "waveOverhangMinimumWidthMm"
        const val WAVE_OVERHANG_MAX_ITERATIONS = "waveOverhangMaxIterations"
        const val WAVE_OVERHANG_REVERSE_ODD_LAYERS = "waveOverhangReverseOddLayers"
        const val BRICK_WALL_ENABLED = "brickWallEnabled"
        const val BRICK_WALL_SPEED = "brickWallSpeedMmPerSecond"
        const val BRICK_WALL_FLOW = "brickWallFlowPercent"
        const val BRICK_WALL_FAN_SPEED = "brickWallFanSpeedPercent"
        const val BRICK_WALL_MAX_ITERATIONS = "brickWallMaxIterations"
        const val BRICK_WALL_BRICK_LENGTH = "brickWallBrickLengthMm"
        const val MASONRY_WALLS_ENABLED = "masonryWallsEnabled"
        const val SMART_OVERHANG_STRATEGY = "smartOverhangStrategy"
        const val IRONING_ENABLED = "ironingEnabled"
        const val IRONING_ONLY_HIGHEST_LAYER = "ironingOnlyHighestLayer"
        const val IRONING_FLOW = "ironingFlowPercent"
        const val IRONING_SPEED = "ironingSpeedMmPerSecond"
        const val INITIAL_LAYER_INSET_DIRECTION = "initialLayerInsetDirection"
        const val TRAVEL_RETRACT_BEFORE_OUTER_WALL = "travelRetractBeforeOuterWall"
        const val INFILL_START_END_PREFERENCE = "infillStartEndPreference"
        const val INFILL_MOVE_INWARDS_LENGTH = "infillMoveInwardsLengthMm"
        const val ROOFING_EXPANSION = "roofingExpansionMm"
        const val TOP_BOTTOM_SKIN_MERGE_DISTANCE = "topBottomSkinMergeDistanceMm"
        const val SKIN_SUPPORT_ENABLED = "skinSupportEnabled"
        const val SKIN_SUPPORT_DENSITY = "skinSupportDensityPercent"
        const val SKIN_SUPPORT_SPEED = "skinSupportSpeedMmPerSecond"
        const val SKIN_SUPPORT_MATERIAL_FLOW = "skinSupportMaterialFlowPercent"
        const val SKIN_SUPPORT_FAN_SPEED = "skinSupportFanSpeedPercent"
        const val BRIDGE_INTERLACE_LINES = "bridgeInterlaceLines"
        const val SUPPORT_INFILL_MULTIPLIER = "supportInfillMultiplier"
        const val SUPPORT_BRIM_MINIMUM_HOLE_AREA = "supportBrimMinimumHoleAreaMm2"
        const val MACHINE_TIME_ESTIMATION_FACTOR = "machineTimeEstimationFactorPercent"
        const val MINIMUM_INFILL_LINE_LENGTH = "minimumInfillLineLengthMm"
    }
}