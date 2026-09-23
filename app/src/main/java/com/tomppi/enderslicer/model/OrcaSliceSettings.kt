package com.tomppi.enderslicer.model

/**
 * Slice settings for the OrcaSlicer engine.
 *
 * OrcaSlicer is a PrusaSlicer fork, but its option vocabulary is the Bambu-derived one:
 * infill density is `sparse_infill_density`, perimeters are `wall_loops`, the bed
 * temperature is `hot_plate_temp` and retraction belongs to the printer rather than to the
 * print settings. Every name here was read back from the shipped engine's own
 * `--dump-settings` output (674 options), and [OrcaConfigWriter] writes exactly these keys.
 *
 * [printerPreset], [processPreset] and [filamentPreset] name presets from the bundled vendor
 * profile tree; the console selects them by name and then layers these settings over them, so
 * a user tweak never has to restate a whole profile.
 */
data class OrcaSliceSettings(
    // Presets from resources/profiles, selected by name.
    val printerPreset: String = DEFAULT_PRINTER_PRESET,
    val processPreset: String = DEFAULT_PROCESS_PRESET,
    val filamentPreset: String = DEFAULT_FILAMENT_PRESET,
    // Quality
    val layerHeightMm: Double = 0.20,
    val firstLayerHeightMm: Double = 0.20,
    // Walls and shells
    val wallLoops: Int = 3,
    val topShellLayers: Int = 4,
    val bottomShellLayers: Int = 3,
    // Infill
    val sparseInfillDensityPercent: Double = 15.0,
    val sparseInfillPattern: String = "grid",
    // Skirt and brim
    val skirtLoops: Int = 1,
    val brimWidthMm: Double = 0.0,
    // Support
    val supportEnabled: Boolean = false,
    val supportThresholdAngleDegrees: Int = 30,
    val supportBasePattern: String = "default",
    val supportInterfaceTopLayers: Int = 3,
    // Speeds (mm/s)
    val innerWallSpeedMmPerSecond: Double = 40.0,
    val outerWallSpeedMmPerSecond: Double = 25.0,
    val initialLayerSpeedMmPerSecond: Double = 15.0,
    val sparseInfillSpeedMmPerSecond: Double = 50.0,
    val internalSolidInfillSpeedMmPerSecond: Double = 50.0,
    val travelSpeedMmPerSecond: Double = 150.0,
    // Temperature and fan (per extruder slot; a single-slot printer writes one value)
    val nozzleTemperatureC: Int = 220,
    val initialLayerNozzleTemperatureC: Int = 220,
    val hotPlateTemperatureC: Int = 60,
    val initialLayerHotPlateTemperatureC: Int = 60,
    val fanMaxSpeedPercent: Int = 100,
    val fanMinSpeedPercent: Int = 20,
    // Filament
    val filamentType: String = "PLA",
    val filamentFlowRatioPercent: Double = 100.0,
    // Retraction (a printer setting in this engine)
    val retractionLengthMm: Double = 0.8,
    val retractionSpeedMmPerSecond: Double = 30.0,
    val zHopMm: Double = 0.4,
    val useFirmwareRetraction: Boolean = false,
    /** Extra engine keys, written to the print bucket verbatim. */
    val extraKeys: Map<String, String> = emptyMap(),
) {
    object Keys {
        const val LAYER_HEIGHT = "layerHeightMm"
        const val FIRST_LAYER_HEIGHT = "firstLayerHeightMm"
        const val WALL_LOOPS = "wallLoops"
        const val TOP_SHELL_LAYERS = "topShellLayers"
        const val BOTTOM_SHELL_LAYERS = "bottomShellLayers"
        const val SPARSE_INFILL_DENSITY = "sparseInfillDensityPercent"
        const val SPARSE_INFILL_PATTERN = "sparseInfillPattern"
        const val SKIRT_LOOPS = "skirtLoops"
        const val BRIM_WIDTH = "brimWidthMm"
        const val SUPPORT_ENABLED = "supportEnabled"
        const val SUPPORT_THRESHOLD_ANGLE = "supportThresholdAngleDegrees"
        const val SUPPORT_BASE_PATTERN = "supportBasePattern"
        const val SUPPORT_INTERFACE_TOP_LAYERS = "supportInterfaceTopLayers"
        const val INNER_WALL_SPEED = "innerWallSpeedMmPerSecond"
        const val OUTER_WALL_SPEED = "outerWallSpeedMmPerSecond"
        const val INITIAL_LAYER_SPEED = "initialLayerSpeedMmPerSecond"
        const val SPARSE_INFILL_SPEED = "sparseInfillSpeedMmPerSecond"
        const val INTERNAL_SOLID_INFILL_SPEED = "internalSolidInfillSpeedMmPerSecond"
        const val TRAVEL_SPEED = "travelSpeedMmPerSecond"
        const val NOZZLE_TEMPERATURE = "nozzleTemperatureC"
        const val INITIAL_LAYER_NOZZLE_TEMPERATURE = "initialLayerNozzleTemperatureC"
        const val HOT_PLATE_TEMPERATURE = "hotPlateTemperatureC"
        const val INITIAL_LAYER_HOT_PLATE_TEMPERATURE = "initialLayerHotPlateTemperatureC"
        const val FAN_MAX_SPEED = "fanMaxSpeedPercent"
        const val FAN_MIN_SPEED = "fanMinSpeedPercent"
        const val FILAMENT_TYPE = "filamentType"
        const val FILAMENT_FLOW_RATIO = "filamentFlowRatioPercent"
        const val RETRACTION_LENGTH = "retractionLengthMm"
        const val RETRACTION_SPEED = "retractionSpeedMmPerSecond"
        const val Z_HOP = "zHopMm"
        const val USE_FIRMWARE_RETRACTION = "useFirmwareRetraction"
    }

    companion object {
        /**
         * The machine the app is validated against.
         *
         * The presets are the ones the bundled Creality vendor bundle ships, so a slice works
         * before the user has picked anything; choosing another printer replaces all three.
         */
        const val DEFAULT_PRINTER_PRESET = "Creality Ender-3 V2 0.4 nozzle"
        const val DEFAULT_PROCESS_PRESET = "0.20mm Standard @Creality Ender3V2"
        const val DEFAULT_FILAMENT_PRESET = "Creality Generic PLA"

        /** `sparse_infill_pattern` values the writer accepts (from the engine's own enum). */
        val INFILL_PATTERNS = listOf(
            "rectilinear",
            "alignedrectilinear",
            "grid",
            "triangles",
            "tri-hexagon",
            "cubic",
            "gyroid",
            "crosshatch",
            "honeycomb",
            "3dhoneycomb",
            "concentric",
        )

        /** `support_base_pattern` values the writer accepts. */
        val SUPPORT_PATTERNS = listOf(
            "default",
            "rectilinear",
            "rectilinear-grid",
            "honeycomb",
            "lightning",
            "hollow",
        )
    }
}
