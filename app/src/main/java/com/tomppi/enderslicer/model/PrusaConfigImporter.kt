package com.tomppi.enderslicer.model

/**
 * Parses a PrusaSlicer .ini configuration (as exported by the PC app:
 * File -> Export -> Export All Config Files, or a single [print]/[filament]/[printer]
 * config) into the app's Prusa slice settings and machine description.
 *
 * The key names are PrusaSlicer's own; unknown keys are ignored so that any
 * full config can be imported.
 */
object PrusaConfigImporter {

    data class Result(
        val settings: PrusaSliceSettings,
        /**
         * The config's G-code templates, or null when it carries none: a profile
         * section that has no start_gcode must not wipe the app's stored one.
         * A key that is present but empty stays an empty string, which is how a
         * config says "no template"; the two are different answers.
         */
        val startGcode: String?,
        val endGcode: String?,
        val widthMm: Double?,
        val depthMm: Double?,
        val originAtCenter: Boolean,
        val nozzleSizeMm: Double?,
        val extruders: Int?,
        val filamentDiameterMm: Double?,
        val gcodeFlavor: String?,
        val unusedKeyCount: Int,
    )

    /** Duplicate keys across sections: machine settings must win over print/filament ones. */
    private val SECTION_PRIORITY = listOf("printer", "filament", "print", "flat")

    fun parse(text: String): Result {
        var section = "flat"
        val values = linkedMapOf<String, Pair<String, String>>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            val index = line.indexOf('=')
            if (index <= 0) continue
            val key = line.substring(0, index).trim()
            val value = unquote(line.substring(index + 1).trim())
            if (key.isNotEmpty() && !key.contains(' ')) {
                val previous = values[key]
                if (previous == null || sectionWins(section, previous.first)) {
                    values[key] = section to value
                }
            }
        }

        fun value(key: String): String? = values[key]?.second
        fun double(key: String): Double? = value(key)?.replace(",", ".")?.trimEnd('%')?.toDoubleOrNull()
        fun int(key: String): Int? = value(key)?.toIntOrNull()
        fun bool(key: String): Boolean? = value(key)?.let { it == "1" || it.equals("true", true) }

        val defaults = PrusaSliceSettings()

        val infillDensity = double("fill_density")
        val settings = defaults.copy(
            layerHeightMm = double("layer_height") ?: defaults.layerHeightMm,
            firstLayerHeightMm = double("first_layer_height") ?: defaults.firstLayerHeightMm,
            perimeters = int("perimeters") ?: defaults.perimeters,
            topSolidLayers = int("top_solid_layers") ?: defaults.topSolidLayers,
            bottomSolidLayers = int("bottom_solid_layers") ?: defaults.bottomSolidLayers,
            thinWalls = bool("thin_walls") ?: defaults.thinWalls,
            externalPerimetersFirst = bool("external_perimeters_first") ?: defaults.externalPerimetersFirst,
            fillDensityPercent = infillDensity ?: defaults.fillDensityPercent,
            fillPattern = value("fill_pattern") ?: defaults.fillPattern,
            skirtLoops = int("skirts") ?: defaults.skirtLoops,
            skirtHeightLayers = int("skirt_height") ?: defaults.skirtHeightLayers,
            skirtDistanceMm = double("skirt_distance") ?: defaults.skirtDistanceMm,
            brimWidthMm = double("brim_width") ?: defaults.brimWidthMm,
            overhangs = bool("overhangs") ?: defaults.overhangs,
            firstLayerExtrusionWidthMm = double("first_layer_extrusion_width"),
            perimeterExtrusionWidthMm = double("perimeter_extrusion_width"),
            externalPerimeterExtrusionWidthMm = double("external_perimeter_extrusion_width"),
            infillExtrusionWidthMm = double("infill_extrusion_width"),
            solidInfillExtrusionWidthMm = double("solid_infill_extrusion_width"),
            topInfillExtrusionWidthMm = double("top_infill_extrusion_width"),
            supportMaterial = bool("support_material") ?: defaults.supportMaterial,
            supportThresholdAngleDegrees = double("support_material_threshold_angle") ?: defaults.supportThresholdAngleDegrees,
            supportPattern = value("support_material_pattern") ?: defaults.supportPattern,
            supportInterface = bool("support_material_interface") ?: defaults.supportInterface,
            supportInterfaceLayers = int("support_material_interface_layers") ?: defaults.supportInterfaceLayers,
            printSpeedMmPerSecond = double("print_speed") ?: defaults.printSpeedMmPerSecond,
            externalPerimeterSpeedMmPerSecond = double("external_perimeter_speed") ?: defaults.externalPerimeterSpeedMmPerSecond,
            infillSpeedMmPerSecond = double("infill_speed") ?: defaults.infillSpeedMmPerSecond,
            firstLayerSpeedMmPerSecond = double("first_layer_speed") ?: defaults.firstLayerSpeedMmPerSecond,
            travelSpeedMmPerSecond = double("travel_speed") ?: defaults.travelSpeedMmPerSecond,
            nozzleTemperatureC = int("temperature") ?: defaults.nozzleTemperatureC,
            firstLayerTemperatureC = int("first_layer_temperature") ?: defaults.firstLayerTemperatureC,
            bedTemperatureC = int("bed_temperature") ?: defaults.bedTemperatureC,
            firstLayerBedTemperatureC = int("first_layer_bed_temperature") ?: defaults.firstLayerBedTemperatureC,
            fanSpeedPercent = int("fan_speed") ?: defaults.fanSpeedPercent,
            retractionLengthMm = double("retraction_length") ?: defaults.retractionLengthMm,
            retractionSpeedMmPerSecond = double("retraction_speed") ?: defaults.retractionSpeedMmPerSecond,
            retractionMinTravelMm = double("retraction_min_travel") ?: defaults.retractionMinTravelMm,
            retractLiftMm = double("retract_lift") ?: defaults.retractLiftMm,
            useFirmwareRetraction = bool("use_firmware_retraction") ?: defaults.useFirmwareRetraction,
            extrusionMultiplierPercent = double("extrusion_multiplier")?.times(100.0) ?: defaults.extrusionMultiplierPercent,
        )

        val bedShape = value("bed_shape")
        val (widthMm, depthMm, originAtCenter) = parseBedShape(bedShape)

        return Result(
            settings = settings,
            startGcode = value("start_gcode")?.let(::decodeEscaped),
            endGcode = value("end_gcode")?.let(::decodeEscaped),
            widthMm = widthMm,
            depthMm = depthMm,
            originAtCenter = originAtCenter,
            nozzleSizeMm = double("nozzle_diameter"),
            extruders = int("extruder_count"),
            filamentDiameterMm = double("filament_diameter"),
            gcodeFlavor = value("gcode_flavor"),
            unusedKeyCount = values.size,
        )
    }

    /** True when [section] outranks [other] for an already-stored duplicate key. */
    private fun sectionWins(section: String, other: String): Boolean =
        SECTION_PRIORITY.indexOf(section).let { index ->
            index >= 0 && index < SECTION_PRIORITY.indexOf(other)
        }

    private fun parseBedShape(bedShape: String?): Triple<Double?, Double?, Boolean> {
        if (bedShape.isNullOrBlank()) return Triple(null, null, false)
        val corners = bedShape.split(',')
            .mapNotNull { part ->
                val xy = part.split('x')
                if (xy.size == 2) {
                    (xy[0].toDoubleOrNull() to xy[1].toDoubleOrNull())
                } else {
                    null
                }
            }
        val xs = corners.mapNotNull { it.first }
        val ys = corners.mapNotNull { it.second }
        if (xs.isEmpty() || ys.isEmpty()) return Triple(null, null, false)
        val minX = xs.minOrNull()!!
        val maxX = xs.maxOrNull()!!
        val minY = ys.minOrNull()!!
        val maxY = ys.maxOrNull()!!
        return Triple(maxX - minX, maxY - minY, minX < 0.0 || minY < 0.0)
    }

    private fun unquote(raw: String): String {
        if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            return raw.substring(1, raw.length - 1)
        }
        return raw
    }

    /** Decodes PrusaSlicer's escaped newlines in start/end gcode values. */
    fun decodeEscaped(text: String): String = text.replace("\\n", "\n").replace("\\r", "")
}
