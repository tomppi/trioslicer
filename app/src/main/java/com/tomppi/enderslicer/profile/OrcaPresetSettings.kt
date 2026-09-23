package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.data.OrcaSliceSettingsJson
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.OrcaSliceSettings.Keys
import com.tomppi.enderslicer.model.SlicerEngine
import org.json.JSONObject

/**
 * Named presets for the OrcaSlicer engine.
 *
 * The vendor profile selection ([OrcaSliceSettings.printerPreset] and its process and filament
 * counterparts) is deliberately outside both categories: picking a preset must not re-point the
 * machine or restate a vendor profile. The catalog keys in `extraKeys` stay outside as well,
 * exactly as they do for Cura.
 */
internal object OrcaPresetSettings : PresetSchema<OrcaSliceSettings> {
    override val engine: SlicerEngine = SlicerEngine.ORCA

    private val printKeys: Set<String> = linkedSetOf(
        Keys.LAYER_HEIGHT,
        Keys.FIRST_LAYER_HEIGHT,
        Keys.WALL_LOOPS,
        Keys.TOP_SHELL_LAYERS,
        Keys.BOTTOM_SHELL_LAYERS,
        Keys.SPARSE_INFILL_DENSITY,
        Keys.SPARSE_INFILL_PATTERN,
        Keys.SKIRT_LOOPS,
        Keys.BRIM_WIDTH,
        Keys.SUPPORT_ENABLED,
        Keys.SUPPORT_THRESHOLD_ANGLE,
        Keys.SUPPORT_BASE_PATTERN,
        Keys.SUPPORT_INTERFACE_TOP_LAYERS,
        Keys.INNER_WALL_SPEED,
        Keys.OUTER_WALL_SPEED,
        Keys.INITIAL_LAYER_SPEED,
        Keys.SPARSE_INFILL_SPEED,
        Keys.INTERNAL_SOLID_INFILL_SPEED,
        Keys.TRAVEL_SPEED,
    )

    private val filamentKeys: Set<String> = linkedSetOf(
        Keys.NOZZLE_TEMPERATURE,
        Keys.INITIAL_LAYER_NOZZLE_TEMPERATURE,
        Keys.HOT_PLATE_TEMPERATURE,
        Keys.INITIAL_LAYER_HOT_PLATE_TEMPERATURE,
        Keys.FAN_MAX_SPEED,
        Keys.FAN_MIN_SPEED,
        Keys.FILAMENT_TYPE,
        Keys.FILAMENT_FLOW_RATIO,
        Keys.RETRACTION_LENGTH,
        Keys.RETRACTION_SPEED,
        Keys.Z_HOP,
        Keys.USE_FIRMWARE_RETRACTION,
    )

    private val printRules = PresetValueRules(
        booleanKeys = setOf(Keys.SUPPORT_ENABLED),
        integerKeys = setOf(
            Keys.WALL_LOOPS,
            Keys.TOP_SHELL_LAYERS,
            Keys.BOTTOM_SHELL_LAYERS,
            Keys.SKIRT_LOOPS,
            Keys.SUPPORT_THRESHOLD_ANGLE,
            Keys.SUPPORT_INTERFACE_TOP_LAYERS,
        ),
        stringKeys = setOf(Keys.SPARSE_INFILL_PATTERN, Keys.SUPPORT_BASE_PATTERN),
        numericRanges = mapOf(
            Keys.LAYER_HEIGHT to 0.01..5.0,
            Keys.FIRST_LAYER_HEIGHT to 0.01..5.0,
            Keys.WALL_LOOPS to 0.0..1000.0,
            Keys.TOP_SHELL_LAYERS to 0.0..1000.0,
            Keys.BOTTOM_SHELL_LAYERS to 0.0..1000.0,
            Keys.SPARSE_INFILL_DENSITY to 0.0..100.0,
            Keys.SKIRT_LOOPS to 0.0..1000.0,
            Keys.BRIM_WIDTH to 0.0..100.0,
            Keys.SUPPORT_THRESHOLD_ANGLE to 0.0..90.0,
            Keys.SUPPORT_INTERFACE_TOP_LAYERS to 0.0..1000.0,
            Keys.INNER_WALL_SPEED to 0.1..1000.0,
            Keys.OUTER_WALL_SPEED to 0.1..1000.0,
            Keys.INITIAL_LAYER_SPEED to 0.1..1000.0,
            Keys.SPARSE_INFILL_SPEED to 0.1..1000.0,
            Keys.INTERNAL_SOLID_INFILL_SPEED to 0.1..1000.0,
            Keys.TRAVEL_SPEED to 0.1..1000.0,
        ),
        strictOptions = mapOf(
            Keys.SPARSE_INFILL_PATTERN to OrcaSliceSettings.INFILL_PATTERNS.toSet(),
            Keys.SUPPORT_BASE_PATTERN to OrcaSliceSettings.SUPPORT_PATTERNS.toSet(),
        ),
    )

    private val filamentRules = PresetValueRules(
        booleanKeys = setOf(Keys.USE_FIRMWARE_RETRACTION),
        integerKeys = setOf(
            Keys.NOZZLE_TEMPERATURE,
            Keys.INITIAL_LAYER_NOZZLE_TEMPERATURE,
            Keys.HOT_PLATE_TEMPERATURE,
            Keys.INITIAL_LAYER_HOT_PLATE_TEMPERATURE,
            Keys.FAN_MAX_SPEED,
            Keys.FAN_MIN_SPEED,
        ),
        stringKeys = setOf(Keys.FILAMENT_TYPE),
        numericRanges = mapOf(
            Keys.NOZZLE_TEMPERATURE to 150.0..500.0,
            Keys.INITIAL_LAYER_NOZZLE_TEMPERATURE to 150.0..500.0,
            Keys.HOT_PLATE_TEMPERATURE to 0.0..200.0,
            Keys.INITIAL_LAYER_HOT_PLATE_TEMPERATURE to 0.0..200.0,
            Keys.FAN_MAX_SPEED to 0.0..100.0,
            Keys.FAN_MIN_SPEED to 0.0..100.0,
            Keys.FILAMENT_FLOW_RATIO to 1.0..300.0,
            Keys.RETRACTION_LENGTH to 0.0..100.0,
            Keys.RETRACTION_SPEED to 0.0..1000.0,
            Keys.Z_HOP to 0.0..100.0,
        ),
    )

    fun rules(kind: PresetKind): PresetValueRules =
        if (kind == PresetKind.PRINT) printRules else filamentRules

    override fun keys(kind: PresetKind): Set<String> =
        if (kind == PresetKind.PRINT) printKeys else filamentKeys

    override fun capture(kind: PresetKind, settings: OrcaSliceSettings): JSONObject {
        val all = JSONObject(OrcaSliceSettingsJson.serialize(settings))
        val output = JSONObject()
        keys(kind).sorted().forEach { key -> if (all.has(key)) output.put(key, all.opt(key)) }
        return output
    }

    override fun apply(
        kind: PresetKind,
        current: OrcaSliceSettings,
        values: JSONObject,
    ): OrcaSliceSettings = OrcaSliceSettingsJson.mergeValues(current, values)

    override fun matches(kind: PresetKind, settings: OrcaSliceSettings, values: JSONObject): Boolean =
        PresetValues.matches(keys(kind), capture(kind, settings), values)

    /** No cross-field rules exist for these categories; ranges and types are the whole gate. */
    override fun validateMerged(kind: PresetKind, settings: OrcaSliceSettings) = Unit
}
