package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.ExtraSettingValidation
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Writes a PrusaSlicer 3.x JSON configuration for the bundled console.
 *
 * The 3.0 launcher's --load accepts the --save format: {preset, configuration}
 * where [configuration] carries the resolved per-bucket option values. The
 * writer starts from the shipped MK4 base config (which supplies the preset
 * metadata, the hardware config and the tool/filament defaults), then overlays
 * every app-controlled option into the matching bucket:
 *
 *  - print_settings    slice quality, infill, support, speeds, retraction
 *  - printer_settings  machine (gcode flavor, bed shape, start/end gcode)
 *  - filament_settings per-slot values (arrays), i.e. temperatures, fan
 *
 * 3.x types: Percentage / FloatOrPercentage values are {value, is_percent}
 * objects, filament and tool overrides are per-slot arrays, so the app renders
 * them in the shapes validated by PrusaSlicer's own ConfigLoad tests.
 */
object PrusaConfigWriter {
    // The "(managed by the app)" hint the All-settings sheet shows comes from
    // AllSettingsCatalogs.PRUSA_MANAGED_KEYS; PrusaConfigWriterTest pins that
    // list to the keys rendered below, so the two cannot drift apart.

    private fun put(bucket: JSONObject, key: String, value: Any) {
        bucket.put(key, value)
    }

    private fun number(value: Double): Double = if (value == value.toLong().toDouble()) value.toLong().toDouble() else value

    private fun percentage(value: Double, isPercent: Boolean = true): JSONObject =
        JSONObject().put("value", number(value)).put("is_percent", isPercent)

    private fun one(value: Any): JSONArray = JSONArray().put(value)

    /** True when the app's support switch should generate supports everywhere. */
    private fun supportEnum(settings: PrusaSliceSettings): String =
        if (settings.supportMaterial) "everywhere" else "none"

    /**
     * Renders the complete JSON configuration text for the 3.x console.
     *
     * @param baseConfigJson The shipped base configuration (--save format,
     *   {preset, configuration}); its preset and hardware config are preserved.
     */
    fun render(
        settings: PrusaSliceSettings,
        printer: PrinterDefinition,
        startGcode: String,
        endGcode: String,
        baseConfigJson: String,
        filamentType: String = "PLA",
        presets: PrusaPresetValues.Buckets? = null,
    ): String {
        val root = JSONObject(baseConfigJson)
        val configuration = root.getJSONObject("configuration")
        val print = configuration.optJSONObject("print_settings") ?: JSONObject()
        val printerSettings = configuration.optJSONObject("printer_settings") ?: JSONObject()
        val filament = configuration.optJSONObject("filament_settings") ?: JSONObject()

        // The chosen machine, quality and material presets supply what the sheet does not state;
        // every value the app writes below wins over them, exactly as it wins over the base.
        presets?.let { applied ->
            merge(applied.print, print)
            merge(applied.printer, printerSettings)
            merge(applied.filament, filament)
        }

        // --- print settings ---
        put(print, "layer_height", number(settings.layerHeightMm))
        put(print, "first_layer_height", percentage(settings.firstLayerHeightMm, false))
        put(print, "perimeters", settings.perimeters)
        put(print, "top_solid_layers", settings.topSolidLayers)
        put(print, "bottom_solid_layers", settings.bottomSolidLayers)
        put(print, "thin_walls", settings.thinWalls)
        put(print, "external_perimeters_first", settings.externalPerimetersFirst)
        put(print, "fill_density", percentage(settings.fillDensityPercent))
        put(print, "fill_pattern", settings.fillPattern)
        // The MK4 base profile enables arc fitting (G2/G3); the app's G-code
        // pipeline models linear moves only, so keep arcs off for export.
        put(print, "arc_fitting", "disabled")
        put(print, "skirts", settings.skirtLoops)
        put(print, "skirt_height", settings.skirtHeightLayers)
        put(print, "skirt_distance", number(settings.skirtDistanceMm))
        put(print, "brim_width", number(settings.brimWidthMm))
        put(print, "overhangs", settings.overhangs)
        settings.firstLayerExtrusionWidthMm?.let { put(print, "first_layer_extrusion_width", number(it)) }
        settings.perimeterExtrusionWidthMm?.let { put(print, "perimeter_extrusion_width", number(it)) }
        settings.externalPerimeterExtrusionWidthMm?.let { put(print, "external_perimeter_extrusion_width", number(it)) }
        settings.infillExtrusionWidthMm?.let { put(print, "infill_extrusion_width", number(it)) }
        settings.solidInfillExtrusionWidthMm?.let { put(print, "solid_infill_extrusion_width", number(it)) }
        settings.topInfillExtrusionWidthMm?.let { put(print, "top_infill_extrusion_width", number(it)) }
        // support
        put(print, "support_material", supportEnum(settings))
        put(print, "support_material_threshold", settings.supportThresholdAngleDegrees.toInt())
        put(print, "support_material_pattern", settings.supportPattern)
        put(print, "support_material_interface_layers", if (settings.supportInterface) settings.supportInterfaceLayers else 0)
        // speeds (absolute mm/s)
        put(print, "perimeter_speed", percentage(settings.printSpeedMmPerSecond, false))
        put(print, "external_perimeter_speed", percentage(settings.externalPerimeterSpeedMmPerSecond, false))
        put(print, "infill_speed", percentage(settings.infillSpeedMmPerSecond, false))
        put(print, "first_layer_speed", percentage(settings.firstLayerSpeedMmPerSecond, false))
        put(print, "travel_speed", number(settings.travelSpeedMmPerSecond))
        // retraction (3.x names; effective via the MK4 tool defaults)
        put(print, "retract_length", number(settings.retractionLengthMm))
        put(print, "retract_speed", number(settings.retractionSpeedMmPerSecond))
        put(print, "retract_before_travel", number(settings.retractionMinTravelMm))
        put(print, "retract_lift", number(settings.retractLiftMm))

        // --- printer settings ---
        put(printerSettings, "gcode_flavor", prusaGcodeFlavor(printer.gcodeFlavor))
        // Do not emit the base profile's machine limits (MK4 accelerations on an
        // Ender would override the printer's own limits); keep firmware values.
        put(printerSettings, "machine_limits_usage", "ignore")
        // The base profile contributes MK4 machine limits (M201 X4000...); the
        // alpha11 launcher emits them regardless, so pin Creality-class limits
        // that match the printer firmware the app targets.
        put(printerSettings, "machine_max_acceleration_x", one(500))
        put(printerSettings, "machine_max_acceleration_y", one(500))
        put(printerSettings, "machine_max_acceleration_z", one(100))
        put(printerSettings, "machine_max_feedrate_e", one(100))
        put(printerSettings, "machine_max_jerk_e", one(8))
        put(printerSettings, "machine_max_acceleration_e", one(1000))
        put(printerSettings, "machine_max_feedrate_x", one(500))
        put(printerSettings, "machine_max_feedrate_y", one(500))
        put(printerSettings, "machine_max_feedrate_z", one(20))

        put(printerSettings, "machine_max_jerk_x", one(8))
        put(printerSettings, "machine_max_jerk_y", one(8))
        put(printerSettings, "machine_max_jerk_z", one(8))

        put(printerSettings, "bed_shape", bedShapeArray(printer))
        put(printerSettings, "printer_model", "Ender")
        put(printerSettings, "use_firmware_retraction", settings.useFirmwareRetraction)
        put(printerSettings, "start_gcode", startGcode.trim().replace("\r\n", "\n"))
        put(printerSettings, "end_gcode", endGcode.trim().replace("\r\n", "\n"))

        // --- filament settings (per-slot arrays) ---
        put(filament, "filament_type", one(filamentType))
        put(filament, "filament_diameter", one(number(printer.filamentDiameterMm)))
        put(filament, "temperature", one(settings.nozzleTemperatureC))
        put(filament, "first_layer_temperature", one(settings.firstLayerTemperatureC))
        put(filament, "bed_temperature", one(settings.bedTemperatureC))
        put(filament, "first_layer_bed_temperature", one(settings.firstLayerBedTemperatureC))
        put(filament, "max_fan_speed", one(settings.fanSpeedPercent))
        put(filament, "min_fan_speed", one(settings.fanSpeedPercent))
        put(filament, "extrusion_multiplier", one(number(settings.extrusionMultiplierPercent / 100.0)))
        // The MK4 base preset enables pressure advance (M572 S0.03); keep the
        // Ender's own calibration untouched.
        put(filament, "pressure_advance", one("disabled"))

        // --- extra catalog keys: rendered into print settings (flat, 3.x names) ---
        settings.extraKeys.toSortedMap().forEach { (key, value) ->
            if (!ExtraSettingValidation.isValidKey(key)) return@forEach
            // A blank or malformed value otherwise reaches the launcher as an
            // unusable option and fails the slice with a generic engine error
            // that never names the key; reject it here instead.
            ExtraSettingValidation.requireValid(key, value)
            put(print, key, value)
        }

        configuration.put("print_settings", print)
        configuration.put("printer_settings", printerSettings)
        configuration.put("filament_settings", filament)
        return root.toString()
    }

    /** Renders and writes the configuration to [file], returning its absolute path. */
    fun write(
        file: File,
        settings: PrusaSliceSettings,
        printer: PrinterDefinition,
        startGcode: String,
        endGcode: String,
        baseConfigJson: String,
        filamentType: String = "PLA",
        presets: PrusaPresetValues.Buckets? = null,
    ): File {
        file.parentFile?.mkdirs()
        file.writeText(
            render(settings, printer, startGcode, endGcode, baseConfigJson, filamentType, presets),
        )
        return file
    }

    private fun merge(values: JSONObject, into: JSONObject) {
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            into.put(key, values.opt(key))
        }
    }

    /** Returns the [key] value inside the given 3.x configuration bucket, or null. */
    fun bucketValue(configJson: String, bucket: String, key: String): Any? =
        JSONObject(configJson).optJSONObject("configuration")?.optJSONObject(bucket)?.opt(key)

    internal fun bedShapeArray(printer: PrinterDefinition): JSONArray {
        val halfW = printer.widthMm / 2.0
        val halfD = printer.depthMm / 2.0
        val points: Array<Pair<Double, Double>> = if (printer.originAtCenter) {
            arrayOf(
                -halfW to -halfD,
                halfW to -halfD,
                halfW to halfD,
                -halfW to halfD,
            )
        } else {
            arrayOf(
                0.0 to 0.0,
                printer.widthMm to 0.0,
                printer.widthMm to printer.depthMm,
                0.0 to printer.depthMm,
            )
        }
        val shape = JSONArray()
        points.forEach { (x, y) ->
            shape.put(JSONArray().put(number(x)).put(number(y)))
        }
        return shape
    }

    internal fun prusaGcodeFlavor(flavor: String): String = when (flavor.lowercase()) {
        "marlin", "ender3", "marlin2" -> "marlin2"
        "reprap" -> "reprap"
        "klipper" -> "klipper"
        else -> "marlin2"
    }
}
