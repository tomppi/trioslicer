package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONObject

/**
 * The value rules of one engine and preset kind: which keys are booleans, whole numbers, strings,
 * nullable, and which numeric ranges and enumerated options they accept.
 */
internal data class PresetValueRules(
    val booleanKeys: Set<String> = emptySet(),
    val integerKeys: Set<String> = emptySet(),
    val stringKeys: Set<String> = emptySet(),
    val nullableKeys: Set<String> = emptySet(),
    val numericRanges: Map<String, ClosedFloatingPointRange<Double>> = emptyMap(),
    val strictOptions: Map<String, Set<String>> = emptyMap(),
)

/** Validates untrusted preset JSON before it can modify live slicer settings. */
internal object PresetValueSanitizer {
    /** Validates a stored or edited preset for the engine that owns it. */
    fun sanitize(engine: SlicerEngine, kind: PresetKind, source: JSONObject): JSONObject = when (engine) {
        SlicerEngine.CURA -> sanitizeCura(kind, source)
        SlicerEngine.PRUSA -> sanitizeValues(kind, PrusaPresetSettings.keys(kind), source, PrusaPresetSettings.rules(kind))
        SlicerEngine.ORCA -> sanitizeValues(kind, OrcaPresetSettings.keys(kind), source, OrcaPresetSettings.rules(kind))
    }

    private fun sanitizeCura(kind: PresetKind, source: JSONObject): JSONObject {
        val output = JSONObject()
        PresetSettings.keys(kind).forEach { key ->
            if (!source.has(key) || source.isNull(key)) return@forEach
            output.put(key, normalizeValue(key, source.opt(key)))
        }
        PresetSettings.validateUsable(kind, output)
        validateStoredRelationships(output)
        return output
    }

    private fun sanitizeValues(
        kind: PresetKind,
        keys: Set<String>,
        source: JSONObject,
        rules: PresetValueRules,
    ): JSONObject {
        val output = JSONObject()
        keys.forEach { key ->
            if (!source.has(key)) return@forEach
            if (source.isNull(key)) {
                require(key in rules.nullableKeys) { "Preset value $key cannot be null" }
                output.put(key, JSONObject.NULL)
                return@forEach
            }
            output.put(key, normalizeEngineValue(key, source.opt(key), rules))
        }
        require(output.keys().asSequence().any { key -> !output.isNull(key) }) {
            "The preset has no usable ${kind.label.lowercase()} values"
        }
        return output
    }

    private fun normalizeEngineValue(key: String, raw: Any?, rules: PresetValueRules): Any = when {
        key in rules.booleanKeys -> {
            require(raw is Boolean) { "Preset value $key must be true or false" }
            raw
        }

        key in rules.integerKeys -> {
            val value = engineNumber(key, raw, rules)
            require(value % 1.0 == 0.0) { "Preset value $key must be a whole number" }
            require(value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                "Preset value $key is outside the supported integer range"
            }
            value.toInt()
        }

        key in rules.stringKeys -> {
            require(raw is String) { "Preset value $key must be text" }
            val value = raw.trim()
            require(value.isNotEmpty()) { "Preset value $key cannot be empty" }
            require(value.length <= MAX_STRING_VALUE_LENGTH) { "Preset value $key is too long" }
            require(value.none(Char::isISOControl)) { "Preset value $key contains control characters" }
            rules.strictOptions[key]?.let { allowed ->
                require(value in allowed) { "Preset value $key is invalid: $value" }
            }
            value
        }

        else -> engineNumber(key, raw, rules)
    }

    private fun engineNumber(key: String, raw: Any?, rules: PresetValueRules): Double {
        require(raw is Number) { "Preset value $key must be numeric" }
        val value = raw.toDouble()
        require(value.isFinite()) { "Preset value $key must be finite" }
        val range = rules.numericRanges[key]
            ?: error("No numeric preset validator is registered for $key")
        require(value in range) {
            "Preset value $key=$value is outside ${range.start}..${range.endInclusive}"
        }
        return value
    }

    fun validateMerged(kind: PresetKind, settings: SlicerSettings) {
        when (kind) {
            PresetKind.PRINT -> {
                if (settings.adaptiveLayerHeightEnabled) {
                    require(settings.adaptiveLayerHeightVariationMm < settings.layerHeightMm) {
                        "Adaptive layer variation must be smaller than the nominal layer height"
                    }
                    require(
                        settings.adaptiveLayerHeightVariationStepMm <=
                            settings.adaptiveLayerHeightVariationMm.coerceAtLeast(0.001),
                    ) { "Adaptive layer variation step must not exceed the total variation" }
                }
                require(settings.arcOverhangMinRadiusMm <= settings.arcOverhangMaxRadiusMm) {
                    "Arc-overhang minimum radius must not exceed its maximum radius"
                }
                require(!(settings.arcOverhangEnabled && settings.waveOverhangEnabled)) {
                    "Arc and Wave overhangs cannot both be enabled"
                }
                require(!(settings.brickWallEnabled && (settings.arcOverhangEnabled || settings.waveOverhangEnabled))) {
                    "Brick walls cannot be combined with Arc or Wave overhangs"
                }
            }

            PresetKind.FILAMENT -> {
                if (settings.coastingEnabled) {
                    require(settings.coastingMinimumVolumeMm3 >= settings.coastingVolumeMm3) {
                        "Minimum volume before coasting must be at least the coasting volume"
                    }
                }
            }
        }
    }

    private fun normalizeValue(key: String, raw: Any?): Any = when {
        key in booleanKeys -> {
            require(raw is Boolean) { "Preset value $key must be true or false" }
            raw
        }

        key in integerKeys -> {
            val value = validatedNumber(key, raw)
            require(value % 1.0 == 0.0) { "Preset value $key must be a whole number" }
            require(value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                "Preset value $key is outside the supported integer range"
            }
            value.toInt()
        }

        key in stringKeys -> {
            require(raw is String) { "Preset value $key must be text" }
            val value = raw.trim()
            require(value.isNotEmpty()) { "Preset value $key cannot be empty" }
            require(value.length <= MAX_STRING_VALUE_LENGTH) { "Preset value $key is too long" }
            require(value.none(Char::isISOControl)) { "Preset value $key contains control characters" }
            strictOptions[key]?.let { allowed ->
                require(value in allowed) {
                    "Preset value $key is invalid: $value"
                }
            }
            value
        }

        else -> validatedNumber(key, raw)
    }

    private fun validatedNumber(key: String, raw: Any?): Double {
        require(raw is Number) { "Preset value $key must be numeric" }
        val value = raw.toDouble()
        require(value.isFinite()) { "Preset value $key must be finite" }
        val range = numericRanges[key] ?: error("No numeric preset validator is registered for $key")
        require(value in range) {
            "Preset value $key=$value is outside ${range.start}..${range.endInclusive}"
        }
        return value
    }

    private fun validateStoredRelationships(values: JSONObject) {
        fun number(key: String): Double? = if (values.has(key) && !values.isNull(key)) {
            (values.opt(key) as? Number)?.toDouble()
        } else {
            null
        }

        val adaptiveVariation = number(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION)
        val adaptiveStep = number(SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP)
        if (adaptiveVariation != null && adaptiveStep != null) {
            require(adaptiveStep <= adaptiveVariation.coerceAtLeast(0.001)) {
                "Adaptive layer variation step must not exceed the total variation"
            }
        }

        val arcMinimum = number(SlicerSettings.Keys.ARC_OVERHANG_MIN_RADIUS)
        val arcMaximum = number(SlicerSettings.Keys.ARC_OVERHANG_MAX_RADIUS)
        if (arcMinimum != null && arcMaximum != null) {
            require(arcMinimum <= arcMaximum) {
                "Arc-overhang minimum radius must not exceed its maximum radius"
            }
        }

        val arcEnabled = values.opt(SlicerSettings.Keys.ARC_OVERHANG_ENABLED) as? Boolean
        val waveEnabled = values.opt(SlicerSettings.Keys.WAVE_OVERHANG_ENABLED) as? Boolean
        require(arcEnabled != true || waveEnabled != true) {
            "Arc and Wave overhangs cannot both be enabled"
        }

        val coastingVolume = number(SlicerSettings.Keys.COASTING_VOLUME)
        val coastingMinimum = number(SlicerSettings.Keys.COASTING_MINIMUM_VOLUME)
        if (coastingVolume != null && coastingMinimum != null) {
            require(coastingMinimum >= coastingVolume) {
                "Minimum volume before coasting must be at least the coasting volume"
            }
        }
    }

    private val booleanKeys = setOf(
        SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_ENABLED,
        SlicerSettings.Keys.Z_SEAM_RELATIVE,
        SlicerSettings.Keys.ZIG_ZAG_CONNECT_INFILL,
        SlicerSettings.Keys.SUPPORTS_ENABLED,
        SlicerSettings.Keys.SUPPORT_INTERFACE_ENABLED,
        SlicerSettings.Keys.AVOID_PRINTED_PARTS,
        SlicerSettings.Keys.ARC_OVERHANG_ENABLED,
        SlicerSettings.Keys.WAVE_OVERHANG_ENABLED,
        SlicerSettings.Keys.WAVE_OVERHANG_REVERSE_ODD_LAYERS,
        SlicerSettings.Keys.BRICK_WALL_ENABLED,
        SlicerSettings.Keys.SMART_OVERHANG_STRATEGY,
        SlicerSettings.Keys.IRONING_ENABLED,
        SlicerSettings.Keys.IRONING_ONLY_HIGHEST_LAYER,
        SlicerSettings.Keys.RETRACT_AT_LAYER_CHANGE,
        SlicerSettings.Keys.Z_HOP,
        SlicerSettings.Keys.FIRMWARE_RETRACTION,
        SlicerSettings.Keys.COASTING_ENABLED,
    )

    private val integerKeys = setOf(
        SlicerSettings.Keys.WALL_LINE_COUNT,
        SlicerSettings.Keys.TOP_LAYERS,
        SlicerSettings.Keys.BOTTOM_LAYERS,
        SlicerSettings.Keys.INITIAL_BOTTOM_LAYERS,
        SlicerSettings.Keys.SKIRT_LINE_COUNT,
        SlicerSettings.Keys.NOZZLE_TEMPERATURE,
        SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE,
        SlicerSettings.Keys.BED_TEMPERATURE,
        SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY,
        SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY,
        SlicerSettings.Keys.FAN_FULL_AT_LAYER,
        SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS,
        SlicerSettings.Keys.BRICK_WALL_MAX_ITERATIONS,
    )

    private val stringKeys = setOf(
        SlicerSettings.Keys.SLICING_TOLERANCE,
        SlicerSettings.Keys.Z_SEAM_TYPE,
        SlicerSettings.Keys.Z_SEAM_CORNER,
        SlicerSettings.Keys.INFILL_PATTERN,
        SlicerSettings.Keys.SUPPORT_PLACEMENT,
        SlicerSettings.Keys.SUPPORT_STRUCTURE,
        SlicerSettings.Keys.SUPPORT_PATTERN,
        SlicerSettings.Keys.COMBING_MODE,
        SlicerSettings.Keys.ADHESION_TYPE,
        SlicerSettings.Keys.WAVE_OVERHANG_PATTERN,
        SlicerSettings.Keys.TOP_SKIN_ANGLES,
        SlicerSettings.Keys.TOP_BOTTOM_PATTERN,
    )

    private val strictOptions = mapOf(
        SlicerSettings.Keys.SLICING_TOLERANCE to setOf("middle", "exclusive", "inclusive"),
        SlicerSettings.Keys.Z_SEAM_TYPE to setOf("back", "shortest", "random", "sharpest_corner"),
        SlicerSettings.Keys.Z_SEAM_CORNER to setOf(
            "z_seam_corner_none",
            "z_seam_corner_inner",
            "z_seam_corner_outer",
            "z_seam_corner_any",
            "z_seam_corner_weighted",
        ),
        SlicerSettings.Keys.WAVE_OVERHANG_PATTERN to setOf("smart", "monotonic", "zigzag"),
        SlicerSettings.Keys.TOP_BOTTOM_PATTERN to setOf("lines", "concentric", "zigzag"),
    )

    private val numericRanges: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
        SlicerSettings.Keys.LAYER_HEIGHT to 0.01..5.0,
        SlicerSettings.Keys.INITIAL_LAYER_HEIGHT to 0.01..5.0,
        SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION to 0.0..5.0,
        SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_VARIATION_STEP to 0.001..5.0,
        SlicerSettings.Keys.ADAPTIVE_LAYER_HEIGHT_THRESHOLD to 0.0..1.0,
        SlicerSettings.Keys.LINE_WIDTH to 0.01..5.0,
        SlicerSettings.Keys.WALL_LINE_COUNT to 0.0..1_000_000.0,
        SlicerSettings.Keys.WALL_THICKNESS to 0.0..100.0,
        SlicerSettings.Keys.TOP_LAYERS to 0.0..1_000_000.0,
        SlicerSettings.Keys.BOTTOM_LAYERS to 0.0..1_000_000.0,
        SlicerSettings.Keys.TOP_BOTTOM_THICKNESS to 0.0..2000.0,
        SlicerSettings.Keys.INITIAL_BOTTOM_LAYERS to 0.0..1_000_000.0,
        SlicerSettings.Keys.HOLE_HORIZONTAL_EXPANSION to -10.0..10.0,
        SlicerSettings.Keys.INITIAL_LAYER_HORIZONTAL_EXPANSION to -10.0..10.0,
        SlicerSettings.Keys.Z_SEAM_X to -2000.0..2000.0,
        SlicerSettings.Keys.Z_SEAM_Y to -2000.0..2000.0,
        SlicerSettings.Keys.INFILL_DENSITY to 0.0..100.0,
        SlicerSettings.Keys.PRINT_SPEED to 0.1..1000.0,
        SlicerSettings.Keys.WALL_SPEED to 0.1..1000.0,
        SlicerSettings.Keys.OUTER_WALL_SPEED to 0.1..1000.0,
        SlicerSettings.Keys.INNER_WALL_SPEED to 0.1..1000.0,
        SlicerSettings.Keys.INFILL_SPEED to 0.1..1000.0,
        SlicerSettings.Keys.TOP_BOTTOM_SPEED to 0.1..1000.0,
        SlicerSettings.Keys.TRAVEL_SPEED to 0.1..1000.0,
        SlicerSettings.Keys.INITIAL_LAYER_SPEED to 0.1..1000.0,
        SlicerSettings.Keys.SUPPORT_ANGLE to 0.0..90.0,
        SlicerSettings.Keys.SUPPORT_DENSITY to 0.0..100.0,
        SlicerSettings.Keys.SUPPORT_INTERFACE_DENSITY to 0.0..100.0,
        SlicerSettings.Keys.SUPPORT_INTERFACE_HEIGHT to 0.0..100.0,
        SlicerSettings.Keys.SUPPORT_Z_DISTANCE to 0.0..20.0,
        SlicerSettings.Keys.SUPPORT_XY_DISTANCE to 0.0..20.0,
        SlicerSettings.Keys.SUPPORT_SPEED to 0.1..1000.0,
        SlicerSettings.Keys.SUPPORT_INTERFACE_SPEED to 0.1..1000.0,
        SlicerSettings.Keys.TRAVEL_AVOID_DISTANCE to 0.0..100.0,
        SlicerSettings.Keys.SKIRT_LINE_COUNT to 0.0..1000.0,
        SlicerSettings.Keys.BRIM_WIDTH to 0.0..100.0,
        SlicerSettings.Keys.RAFT_MARGIN to 0.0..100.0,
        SlicerSettings.Keys.ARC_OVERHANG_SPEED to 0.1..1000.0,
        SlicerSettings.Keys.ARC_OVERHANG_FLOW to 1.0..300.0,
        SlicerSettings.Keys.ARC_OVERHANG_LINE_SPACING to 1.0..1000.0,
        SlicerSettings.Keys.ARC_OVERHANG_MIN_RADIUS to 0.0..1000.0,
        SlicerSettings.Keys.ARC_OVERHANG_MAX_RADIUS to 0.0..1000.0,
        SlicerSettings.Keys.ARC_OVERHANG_MAX_AREA to 0.0..100_000_000.0,
        SlicerSettings.Keys.ARC_OVERHANG_RESOLUTION to 0.001..10.0,
        SlicerSettings.Keys.ARC_OVERHANG_FAN_SPEED to 0.0..100.0,
        SlicerSettings.Keys.WAVE_OVERHANG_LINE_SPACING to 0.1..2.0,
        SlicerSettings.Keys.WAVE_OVERHANG_FLOW to 0.02..1.5,
        SlicerSettings.Keys.WAVE_OVERHANG_SPEED to 0.5..50.0,
        SlicerSettings.Keys.WAVE_OVERHANG_FAN_SPEED to 0.0..100.0,
        SlicerSettings.Keys.WAVE_OVERHANG_PERIMETER_OVERLAP to 0.0..2.0,
        SlicerSettings.Keys.WAVE_OVERHANG_MINIMUM_WIDTH to 0.0..10.0,
        SlicerSettings.Keys.WAVE_OVERHANG_MAX_ITERATIONS to 1.0..2000.0,
        SlicerSettings.Keys.BRICK_WALL_SPEED to 0.5..100.0,
        SlicerSettings.Keys.BRICK_WALL_FLOW to 50.0..200.0,
        SlicerSettings.Keys.BRICK_WALL_FAN_SPEED to 0.0..100.0,
        SlicerSettings.Keys.BRICK_WALL_MAX_ITERATIONS to 2.0..200.0,
        SlicerSettings.Keys.BRICK_WALL_BRICK_LENGTH to 0.5..10.0,
        SlicerSettings.Keys.IRONING_FLOW to 0.0..100.0,
        SlicerSettings.Keys.IRONING_SPEED to 0.1..1000.0,
        SlicerSettings.Keys.FILAMENT_DIAMETER to 0.5..5.0,
        SlicerSettings.Keys.NOZZLE_TEMPERATURE to 150.0..500.0,
        SlicerSettings.Keys.INITIAL_NOZZLE_TEMPERATURE to 150.0..500.0,
        SlicerSettings.Keys.BED_TEMPERATURE to 0.0..200.0,
        SlicerSettings.Keys.BUILD_VOLUME_TEMPERATURE to -273.15..285.0,
        SlicerSettings.Keys.MATERIAL_STANDBY_TEMPERATURE to -273.15..500.0,
        SlicerSettings.Keys.MATERIAL_DENSITY to 0.01..100.0,
        SlicerSettings.Keys.MATERIAL_ADHESION_TENDENCY to 0.0..10.0,
        SlicerSettings.Keys.MATERIAL_SURFACE_ENERGY to 0.0..100.0,
        SlicerSettings.Keys.MATERIAL_FLOW to 1.0..300.0,
        SlicerSettings.Keys.FAN_SPEED to 0.0..100.0,
        SlicerSettings.Keys.INITIAL_FAN_SPEED to 0.0..100.0,
        SlicerSettings.Keys.FAN_FULL_AT_LAYER to 0.0..1_000_000.0,
        SlicerSettings.Keys.RETRACTION_DISTANCE to 0.0..100.0,
        SlicerSettings.Keys.RETRACTION_SPEED to 0.0..1000.0,
        SlicerSettings.Keys.RETRACTION_MINIMUM_TRAVEL to 0.0..1000.0,
        SlicerSettings.Keys.Z_HOP_HEIGHT to 0.0..100.0,
        SlicerSettings.Keys.COASTING_VOLUME to 0.0..1000.0,
        SlicerSettings.Keys.COASTING_MINIMUM_VOLUME to 0.0..100_000.0,
        SlicerSettings.Keys.COASTING_SPEED to 0.0001..1000.0,
    )

    private const val MAX_STRING_VALUE_LENGTH = 80
}
