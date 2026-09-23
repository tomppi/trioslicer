package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.ExtraSettingValidation
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrinterDefinition
import java.io.File
import java.util.Locale

/**
 * Writes the three ini files the OrcaSlicer console layers over its selected presets.
 *
 * Orca splits its options across printer, print and filament buckets the way PrusaSlicer does,
 * so the app writes one file per bucket and passes each to the matching `--*-config`
 * argument. The console applies them over the presets it selected by name, which is what makes
 * a user tweak additive: nothing here restates a complete profile.
 *
 * Every key name is one the shipped engine declares - read back from its own
 * `--dump-settings` output - because this fork renamed most of PrusaSlicer's: perimeters are
 * `wall_loops`, infill density is `sparse_infill_density`, the bed is `hot_plate_temp`, and
 * retraction belongs to the printer bucket. `OrcaConfigWriterTest` pins that list.
 *
 * @param printer the machine envelope the app edited, already merged with the user's machine
 *   settings the way [PrusaConfigWriter] receives it.
 */
object OrcaConfigWriter {

    data class Rendered(
        val printer: String,
        val print: String,
        val filament: String,
    )

    data class Files(
        val printer: File,
        val print: File,
        val filament: File,
    )

    /** Integer-looking doubles are written without a trailing `.0`, as the engine does. */
    private fun number(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.')

    private fun bool(value: Boolean): String = if (value) "1" else "0"

    /** G-code blocks travel as one line with `\n` escapes, the form the engine itself writes. */
    private fun gcodeBlock(value: String): String =
        value.trim().replace("\r\n", "\n").replace("\n", "\\n")

    private fun render(bucket: Map<String, String>): String =
        buildString {
            bucket.forEach { (key, value) -> append(key).append(" = ").append(value).append('\n') }
        }

    /** Renders the three buckets for one slice. */
    fun render(
        settings: OrcaSliceSettings,
        printer: PrinterDefinition,
        startGcode: String,
        endGcode: String,
    ): Rendered {
        val print = linkedMapOf<String, String>()
        print["layer_height"] = number(settings.layerHeightMm)
        print["initial_layer_print_height"] = number(settings.firstLayerHeightMm)
        print["wall_loops"] = settings.wallLoops.toString()
        print["top_shell_layers"] = settings.topShellLayers.toString()
        print["bottom_shell_layers"] = settings.bottomShellLayers.toString()
        print["sparse_infill_density"] = number(settings.sparseInfillDensityPercent) + "%"
        print["sparse_infill_pattern"] = settings.sparseInfillPattern
        print["skirt_loops"] = settings.skirtLoops.toString()
        print["brim_width"] = number(settings.brimWidthMm)
        print["enable_support"] = bool(settings.supportEnabled)
        print["support_threshold_angle"] = settings.supportThresholdAngleDegrees.toString()
        print["support_base_pattern"] = settings.supportBasePattern
        print["support_interface_top_layers"] = settings.supportInterfaceTopLayers.toString()
        print["inner_wall_speed"] = number(settings.innerWallSpeedMmPerSecond)
        print["outer_wall_speed"] = number(settings.outerWallSpeedMmPerSecond)
        print["initial_layer_speed"] = number(settings.initialLayerSpeedMmPerSecond)
        print["sparse_infill_speed"] = number(settings.sparseInfillSpeedMmPerSecond)
        print["internal_solid_infill_speed"] = number(settings.internalSolidInfillSpeedMmPerSecond)
        print["travel_speed"] = number(settings.travelSpeedMmPerSecond)
        // The app's G-code pipeline models linear moves only, so arcs stay off whatever the
        // selected process profile asks for.
        print["enable_arc_fitting"] = "0"
        settings.extraKeys.toSortedMap().forEach { (key, value) ->
            if (!ExtraSettingValidation.isValidKey(key)) return@forEach
            ExtraSettingValidation.requireValid(key, value)
            print[key] = value
        }

        val filament = linkedMapOf<String, String>()
        filament["filament_type"] = settings.filamentType
        filament["filament_diameter"] = number(printer.filamentDiameterMm)
        filament["nozzle_temperature"] = settings.nozzleTemperatureC.toString()
        filament["nozzle_temperature_initial_layer"] = settings.initialLayerNozzleTemperatureC.toString()
        filament["hot_plate_temp"] = settings.hotPlateTemperatureC.toString()
        filament["hot_plate_temp_initial_layer"] = settings.initialLayerHotPlateTemperatureC.toString()
        filament["fan_max_speed"] = settings.fanMaxSpeedPercent.toString()
        filament["fan_min_speed"] = settings.fanMinSpeedPercent.toString()
        filament["filament_flow_ratio"] = number(settings.filamentFlowRatioPercent / 100.0)

        val printerBucket = linkedMapOf<String, String>()
        printerBucket["printable_area"] = bedShape(printer)
        printerBucket["printable_height"] = number(printer.heightMm)
        printerBucket["nozzle_diameter"] = number(printer.nozzleSizeMm)
        printerBucket["gcode_flavor"] = orcaGcodeFlavor(printer.gcodeFlavor)
        printerBucket["use_firmware_retraction"] = bool(settings.useFirmwareRetraction)
        printerBucket["retraction_length"] = number(settings.retractionLengthMm)
        printerBucket["retraction_speed"] = number(settings.retractionSpeedMmPerSecond)
        printerBucket["z_hop"] = number(settings.zHopMm)
        printerBucket["machine_start_gcode"] = gcodeBlock(startGcode)
        printerBucket["machine_end_gcode"] = gcodeBlock(endGcode)

        return Rendered(
            printer = render(printerBucket),
            print = render(print),
            filament = render(filament),
        )
    }

    /** Renders and writes the three buckets next to each other, returning their paths. */
    fun write(
        directory: File,
        settings: OrcaSliceSettings,
        printer: PrinterDefinition,
        startGcode: String,
        endGcode: String,
    ): Files {
        val rendered = render(settings, printer, startGcode, endGcode)
        directory.mkdirs()
        return Files(
            printer = File(directory, "orca-printer.ini").apply { writeText(rendered.printer) },
            print = File(directory, "orca-print.ini").apply { writeText(rendered.print) },
            filament = File(directory, "orca-filament.ini").apply { writeText(rendered.filament) },
        )
    }

    /**
     * The printable area as the engine writes it: `0x0,220x0,220x220,0x220`.
     *
     * A centred printer describes the bed around the origin, which is what the app's
     * `originAtCenter` machine setting means.
     */
    internal fun bedShape(printer: PrinterDefinition): String {
        val halfWidth = printer.widthMm / 2.0
        val halfDepth = printer.depthMm / 2.0
        val points = if (printer.originAtCenter) {
            listOf(
                -halfWidth to -halfDepth,
                halfWidth to -halfDepth,
                halfWidth to halfDepth,
                -halfWidth to halfDepth,
            )
        } else {
            listOf(
                0.0 to 0.0,
                printer.widthMm to 0.0,
                printer.widthMm to printer.depthMm,
                0.0 to printer.depthMm,
            )
        }
        return points.joinToString(",") { (x, y) -> number(x) + "x" + number(y) }
    }

    /** The engine's own `gcode_flavor` spellings, not the app's labels. */
    internal fun orcaGcodeFlavor(flavor: String): String = when (flavor.lowercase()) {
        "marlin" -> "marlin"
        "marlin2", "ender3" -> "marlin2"
        "klipper" -> "klipper"
        "reprap", "reprapfirmware" -> "reprapfirmware"
        "repetier" -> "repetier"
        else -> "marlin2"
    }
}
