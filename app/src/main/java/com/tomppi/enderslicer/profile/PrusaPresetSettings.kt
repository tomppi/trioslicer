package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.data.PrusaSliceSettingsJson
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.PrusaSliceSettings.Keys
import com.tomppi.enderslicer.model.SlicerEngine
import org.json.JSONObject

/**
 * Named presets for the PrusaSlicer engine.
 *
 * The categories mirror the Cura ones: a print profile carries quality, walls, infill,
 * skirt/brim, support and speed, and a filament profile carries temperatures, cooling,
 * retraction and extrusion. Machine geometry, the selected PrusaSlicer vendor presets and the
 * catalog keys in `extraKeys` stay outside both, so applying a preset can neither re-point the
 * machine nor silently restate a whole vendor profile.
 */
internal object PrusaPresetSettings : PresetSchema<PrusaSliceSettings> {
    override val engine: SlicerEngine = SlicerEngine.PRUSA

    private val printKeys: Set<String> = linkedSetOf(
        Keys.LAYER_HEIGHT,
        Keys.FIRST_LAYER_HEIGHT,
        Keys.PERIMETERS,
        Keys.TOP_SOLID_LAYERS,
        Keys.BOTTOM_SOLID_LAYERS,
        Keys.THIN_WALLS,
        Keys.EXTERNAL_PERIMETERS_FIRST,
        Keys.FILL_DENSITY,
        Keys.FILL_PATTERN,
        Keys.SKIRT_LOOPS,
        Keys.SKIRT_HEIGHT_LAYERS,
        Keys.SKIRT_DISTANCE,
        Keys.BRIM_WIDTH,
        Keys.OVERHANGS,
        Keys.FIRST_LAYER_EXTRUSION_WIDTH,
        Keys.PERIMETER_EXTRUSION_WIDTH,
        Keys.EXTERNAL_PERIMETER_EXTRUSION_WIDTH,
        Keys.INFILL_EXTRUSION_WIDTH,
        Keys.SOLID_INFILL_EXTRUSION_WIDTH,
        Keys.TOP_INFILL_EXTRUSION_WIDTH,
        Keys.SUPPORT_MATERIAL,
        Keys.SUPPORT_THRESHOLD_ANGLE,
        Keys.SUPPORT_PATTERN,
        Keys.SUPPORT_INTERFACE,
        Keys.SUPPORT_INTERFACE_LAYERS,
        Keys.PRINT_SPEED,
        Keys.EXTERNAL_PERIMETER_SPEED,
        Keys.INFILL_SPEED,
        Keys.FIRST_LAYER_SPEED,
        Keys.TRAVEL_SPEED,
    )

    private val filamentKeys: Set<String> = linkedSetOf(
        Keys.NOZZLE_TEMPERATURE,
        Keys.FIRST_LAYER_TEMPERATURE,
        Keys.BED_TEMPERATURE,
        Keys.FIRST_LAYER_BED_TEMPERATURE,
        Keys.FAN_SPEED,
        Keys.RETRACTION_LENGTH,
        Keys.RETRACTION_SPEED,
        Keys.RETRACTION_MIN_TRAVEL,
        Keys.RETRACT_LIFT,
        Keys.USE_FIRMWARE_RETRACTION,
        Keys.EXTRUSION_MULTIPLIER,
    )

    /** Null means "let PrusaSlicer decide", which a preset stores and restores like any value. */
    private val nullablePrintKeys: Set<String> = setOf(
        Keys.FIRST_LAYER_EXTRUSION_WIDTH,
        Keys.PERIMETER_EXTRUSION_WIDTH,
        Keys.EXTERNAL_PERIMETER_EXTRUSION_WIDTH,
        Keys.INFILL_EXTRUSION_WIDTH,
        Keys.SOLID_INFILL_EXTRUSION_WIDTH,
        Keys.TOP_INFILL_EXTRUSION_WIDTH,
    )

    private val printRules = PresetValueRules(
        booleanKeys = setOf(
            Keys.THIN_WALLS,
            Keys.EXTERNAL_PERIMETERS_FIRST,
            Keys.OVERHANGS,
            Keys.SUPPORT_MATERIAL,
            Keys.SUPPORT_INTERFACE,
        ),
        integerKeys = setOf(
            Keys.PERIMETERS,
            Keys.TOP_SOLID_LAYERS,
            Keys.BOTTOM_SOLID_LAYERS,
            Keys.SKIRT_LOOPS,
            Keys.SKIRT_HEIGHT_LAYERS,
            Keys.SUPPORT_INTERFACE_LAYERS,
        ),
        stringKeys = setOf(Keys.FILL_PATTERN, Keys.SUPPORT_PATTERN),
        nullableKeys = nullablePrintKeys,
        numericRanges = mapOf(
            Keys.LAYER_HEIGHT to 0.01..5.0,
            Keys.FIRST_LAYER_HEIGHT to 0.01..5.0,
            Keys.PERIMETERS to 0.0..1000.0,
            Keys.TOP_SOLID_LAYERS to 0.0..1000.0,
            Keys.BOTTOM_SOLID_LAYERS to 0.0..1000.0,
            Keys.FILL_DENSITY to 0.0..100.0,
            Keys.SKIRT_LOOPS to 0.0..1000.0,
            Keys.SKIRT_HEIGHT_LAYERS to 0.0..1000.0,
            Keys.SKIRT_DISTANCE to 0.0..100.0,
            Keys.BRIM_WIDTH to 0.0..100.0,
            Keys.FIRST_LAYER_EXTRUSION_WIDTH to 0.01..5.0,
            Keys.PERIMETER_EXTRUSION_WIDTH to 0.01..5.0,
            Keys.EXTERNAL_PERIMETER_EXTRUSION_WIDTH to 0.01..5.0,
            Keys.INFILL_EXTRUSION_WIDTH to 0.01..5.0,
            Keys.SOLID_INFILL_EXTRUSION_WIDTH to 0.01..5.0,
            Keys.TOP_INFILL_EXTRUSION_WIDTH to 0.01..5.0,
            Keys.SUPPORT_THRESHOLD_ANGLE to 0.0..90.0,
            Keys.SUPPORT_INTERFACE_LAYERS to 0.0..1000.0,
            Keys.PRINT_SPEED to 0.1..1000.0,
            Keys.EXTERNAL_PERIMETER_SPEED to 0.1..1000.0,
            Keys.INFILL_SPEED to 0.1..1000.0,
            Keys.FIRST_LAYER_SPEED to 0.1..1000.0,
            Keys.TRAVEL_SPEED to 0.1..1000.0,
        ),
        strictOptions = mapOf(
            Keys.FILL_PATTERN to PrusaSliceSettings.FILL_PATTERNS.toSet(),
            Keys.SUPPORT_PATTERN to PrusaSliceSettings.SUPPORT_PATTERNS.toSet(),
        ),
    )

    private val filamentRules = PresetValueRules(
        booleanKeys = setOf(Keys.USE_FIRMWARE_RETRACTION),
        integerKeys = setOf(
            Keys.NOZZLE_TEMPERATURE,
            Keys.FIRST_LAYER_TEMPERATURE,
            Keys.BED_TEMPERATURE,
            Keys.FIRST_LAYER_BED_TEMPERATURE,
            Keys.FAN_SPEED,
        ),
        numericRanges = mapOf(
            Keys.NOZZLE_TEMPERATURE to 150.0..500.0,
            Keys.FIRST_LAYER_TEMPERATURE to 150.0..500.0,
            Keys.BED_TEMPERATURE to 0.0..200.0,
            Keys.FIRST_LAYER_BED_TEMPERATURE to 0.0..200.0,
            Keys.FAN_SPEED to 0.0..100.0,
            Keys.RETRACTION_LENGTH to 0.0..100.0,
            Keys.RETRACTION_SPEED to 0.0..1000.0,
            Keys.RETRACTION_MIN_TRAVEL to 0.0..1000.0,
            Keys.RETRACT_LIFT to 0.0..100.0,
            Keys.EXTRUSION_MULTIPLIER to 1.0..300.0,
        ),
    )

    fun rules(kind: PresetKind): PresetValueRules =
        if (kind == PresetKind.PRINT) printRules else filamentRules

    override fun keys(kind: PresetKind): Set<String> =
        if (kind == PresetKind.PRINT) printKeys else filamentKeys

    override fun nullableKeys(kind: PresetKind): Set<String> =
        if (kind == PresetKind.PRINT) nullablePrintKeys else emptySet()

    override fun capture(kind: PresetKind, settings: PrusaSliceSettings): JSONObject {
        val all = JSONObject(PrusaSliceSettingsJson.serialize(settings))
        val nullable = nullableKeys(kind)
        val output = JSONObject()
        keys(kind).sorted().forEach { key ->
            when {
                all.has(key) -> output.put(key, all.opt(key))
                // The serializer drops a null value, but "automatic" is a value a preset carries.
                key in nullable -> output.put(key, JSONObject.NULL)
            }
        }
        return output
    }

    override fun apply(
        kind: PresetKind,
        current: PrusaSliceSettings,
        values: JSONObject,
    ): PrusaSliceSettings = PrusaSliceSettingsJson.mergeValues(current, values)

    override fun matches(kind: PresetKind, settings: PrusaSliceSettings, values: JSONObject): Boolean =
        PresetValues.matches(keys(kind), capture(kind, settings), values, nullableKeys(kind))

    /** No cross-field rules exist for these categories; ranges and types are the whole gate. */
    override fun validateMerged(kind: PresetKind, settings: PrusaSliceSettings) = Unit
}
