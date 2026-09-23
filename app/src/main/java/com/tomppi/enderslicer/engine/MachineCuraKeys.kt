package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition

/**
 * The machine-level Cura setting keys both transports emit, in emission order.
 * Keep this in lockstep with the settings the patched engine resolves.
 */
internal object MachineCuraKeys {
    fun values(printer: PrinterDefinition, startGcode: String, endGcode: String): List<Pair<String, String>> {
        val headPolygon = listOf(
            listOf(printer.printheadXMinMm, printer.printheadYMaxMm),
            listOf(printer.printheadXMinMm, printer.printheadYMinMm),
            listOf(printer.printheadXMaxMm, printer.printheadYMinMm),
            listOf(printer.printheadXMaxMm, printer.printheadYMaxMm),
        ).joinToString(prefix = "[", postfix = "]", separator = ",") { point ->
            point.joinToString(prefix = "[", postfix = "]", separator = ",")
        }
        return listOf(
            "machine_name" to printer.name,
            "machine_width" to printer.widthMm.toString(),
            "machine_depth" to printer.depthMm.toString(),
            "machine_height" to printer.heightMm.toString(),
            "machine_shape" to printer.buildPlateShape,
            "machine_center_is_zero" to printer.originAtCenter.toString(),
            "machine_heated_bed" to printer.heatedBed.toString(),
            "machine_heated_build_volume" to printer.heatedBuildVolume.toString(),
            "machine_extruder_count" to printer.extruders.toString(),
            "machine_gcode_flavor" to printer.gcodeFlavor,
            "machine_start_gcode" to startGcode,
            "machine_end_gcode" to endGcode,
            "gantry_height" to printer.gantryHeightMm.toString(),
            "machine_nozzle_size" to printer.nozzleSizeMm.toString(),
            "material_diameter" to printer.filamentDiameterMm.toString(),
            "machine_head_with_fans_polygon" to headPolygon,
        )
    }
}
