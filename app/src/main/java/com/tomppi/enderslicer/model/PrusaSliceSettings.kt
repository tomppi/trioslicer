package com.tomppi.enderslicer.model

/**
 * Slice settings for the PrusaSlicer engine.
 *
 * Field names and defaults mirror PrusaSlicer's own option names so that a
 * PrusaSlicer user sees and edits exactly the vocabulary they already know
 * (layer height, perimeters, top solid layers, fill density, ...). The engine
 * config writer maps these to the PrusaSlicer .ini keys directly.
 */
data class PrusaSliceSettings(
    // Quality
    val layerHeightMm: Double = 0.20,
    val firstLayerHeightMm: Double = 0.30,
    // Perimeters & shells
    val perimeters: Int = 2,
    val topSolidLayers: Int = 4,
    val bottomSolidLayers: Int = 4,
    val thinWalls: Boolean = false,
    val externalPerimetersFirst: Boolean = false,
    // Infill
    val fillDensityPercent: Double = 15.0,
    val fillPattern: String = "grid",
    // Skirt & brim
    val skirtLoops: Int = 1,
    val skirtHeightLayers: Int = 1,
    val skirtDistanceMm: Double = 2.0,
    val brimWidthMm: Double = 0.0,
    // Overhang control
    val overhangs: Boolean = true,
    // Extrusion widths (null = PrusaSlicer automatic). Imported from PC profiles.
    val firstLayerExtrusionWidthMm: Double? = null,
    val perimeterExtrusionWidthMm: Double? = null,
    val externalPerimeterExtrusionWidthMm: Double? = null,
    val infillExtrusionWidthMm: Double? = null,
    val solidInfillExtrusionWidthMm: Double? = null,
    val topInfillExtrusionWidthMm: Double? = null,
    /** Extra PrusaSlicer keys added from the all-settings catalog; written verbatim. */
    val extraKeys: Map<String, String> = emptyMap(),
    // Support material
    val supportMaterial: Boolean = false,
    val supportThresholdAngleDegrees: Double = 55.0,
    val supportPattern: String = "rectilinear",
    val supportInterface: Boolean = true,
    val supportInterfaceLayers: Int = 2,
    // Speed
    val printSpeedMmPerSecond: Double = 60.0,
    val externalPerimeterSpeedMmPerSecond: Double = 25.0,
    val infillSpeedMmPerSecond: Double = 50.0,
    val firstLayerSpeedMmPerSecond: Double = 20.0,
    val travelSpeedMmPerSecond: Double = 150.0,
    // Temperature & fan
    val nozzleTemperatureC: Int = 210,
    val firstLayerTemperatureC: Int = 215,
    val bedTemperatureC: Int = 60,
    val firstLayerBedTemperatureC: Int = 60,
    val fanSpeedPercent: Int = 100,
    // Retraction & extrusion
    val retractionLengthMm: Double = 0.8,
    val retractionSpeedMmPerSecond: Double = 45.0,
    val retractionMinTravelMm: Double = 2.0,
    val retractLiftMm: Double = 0.0,
    val useFirmwareRetraction: Boolean = false,
    val extrusionMultiplierPercent: Double = 100.0,
) {
    object Keys {
        const val LAYER_HEIGHT = "layerHeightMm"
        const val FIRST_LAYER_HEIGHT = "firstLayerHeightMm"
        const val PERIMETERS = "perimeters"
        const val TOP_SOLID_LAYERS = "topSolidLayers"
        const val BOTTOM_SOLID_LAYERS = "bottomSolidLayers"
        const val THIN_WALLS = "thinWalls"
        const val EXTERNAL_PERIMETERS_FIRST = "externalPerimetersFirst"
        const val FILL_DENSITY = "fillDensityPercent"
        const val FILL_PATTERN = "fillPattern"
        const val SKIRT_LOOPS = "skirtLoops"
        const val SKIRT_HEIGHT_LAYERS = "skirtHeightLayers"
        const val SKIRT_DISTANCE = "skirtDistanceMm"
        const val OVERHANGS = "overhangs"
        const val FIRST_LAYER_EXTRUSION_WIDTH = "firstLayerExtrusionWidthMm"
        const val PERIMETER_EXTRUSION_WIDTH = "perimeterExtrusionWidthMm"
        const val EXTERNAL_PERIMETER_EXTRUSION_WIDTH = "externalPerimeterExtrusionWidthMm"
        const val INFILL_EXTRUSION_WIDTH = "infillExtrusionWidthMm"
        const val SOLID_INFILL_EXTRUSION_WIDTH = "solidInfillExtrusionWidthMm"
        const val TOP_INFILL_EXTRUSION_WIDTH = "topInfillExtrusionWidthMm"
        const val BRIM_WIDTH = "brimWidthMm"
        const val SUPPORT_MATERIAL = "supportMaterial"
        const val SUPPORT_THRESHOLD_ANGLE = "supportThresholdAngleDegrees"
        const val SUPPORT_PATTERN = "supportPattern"
        const val SUPPORT_INTERFACE = "supportInterface"
        const val SUPPORT_INTERFACE_LAYERS = "supportInterfaceLayers"
        const val PRINT_SPEED = "printSpeedMmPerSecond"
        const val EXTERNAL_PERIMETER_SPEED = "externalPerimeterSpeedMmPerSecond"
        const val INFILL_SPEED = "infillSpeedMmPerSecond"
        const val FIRST_LAYER_SPEED = "firstLayerSpeedMmPerSecond"
        const val TRAVEL_SPEED = "travelSpeedMmPerSecond"
        const val NOZZLE_TEMPERATURE = "nozzleTemperatureC"
        const val FIRST_LAYER_TEMPERATURE = "firstLayerTemperatureC"
        const val BED_TEMPERATURE = "bedTemperatureC"
        const val FIRST_LAYER_BED_TEMPERATURE = "firstLayerBedTemperatureC"
        const val FAN_SPEED = "fanSpeedPercent"
        const val RETRACTION_LENGTH = "retractionLengthMm"
        const val RETRACTION_SPEED = "retractionSpeedMmPerSecond"
        const val RETRACTION_MIN_TRAVEL = "retractionMinTravelMm"
        const val RETRACT_LIFT = "retractLiftMm"
        const val USE_FIRMWARE_RETRACTION = "useFirmwareRetraction"
        const val EXTRUSION_MULTIPLIER = "extrusionMultiplierPercent"
    }

    companion object {
        /** PrusaSlicer fill pattern values the writer accepts for fill_pattern. */
        val FILL_PATTERNS = listOf(
            "rectilinear",
            "grid",
            "aligned rectilinear",
            "honeycomb",
            "triangles",
            "stars",
            "cubic",
            "gyroid",
            "cross-hatch",
        )

        /** PrusaSlicer support material pattern values for support_material_pattern. */
        val SUPPORT_PATTERNS = listOf(
            "rectilinear",
            "grid",
            "honeycomb",
            "snug",
        )
    }
}
