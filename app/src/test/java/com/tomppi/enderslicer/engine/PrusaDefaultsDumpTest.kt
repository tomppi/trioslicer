package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerSettings
import java.io.File
import org.junit.Test

/** Writes the exact app-default Prusa config for PC-console parity comparison. */
class PrusaDefaultsDumpTest {
    @Test
    fun dumpAppDefaultsIni() {
        val printer = PrinterDefinition(
            name = "Modified Ender 3 V2",
            widthMm = 230.0, depthMm = 230.0, heightMm = 250.0,
            buildPlateShape = "rectangular", originAtCenter = false,
            heatedBed = true, heatedBuildVolume = false,
            gcodeFlavor = "Marlin", extruders = 1,
            nozzleSizeMm = 0.4, filamentDiameterMm = 1.75,
            printheadXMinMm = -26.0, printheadYMinMm = -32.0,
            printheadXMaxMm = 32.0, printheadYMaxMm = 34.0,
            gantryHeightMm = 25.0,
        )
        // Mirror BuiltInGcode.START / END plus PrusaSlicer's standard prepends used by the app.
        val start = "M104 S200 ; set temperature\n" +
            "G28 ; home all axes\n" +
            "G1 Z5 F5000 ; lift nozzle\n" +
            "M109 S200 ; set temperature and wait for it to be reached\n" +
            "G21 ; set units to millimeters\n" +
            "G90 ; use absolute coordinates\n" +
            "M82 ; use absolute distances for extrusion\n" +
            "G92 E0"
        val end = "G1 E-2 F2400\n" +
            "G92 E0\n" +
            "G28 X0 Y0 ; home X and Y\n" +
            "M104 S0 ; turn off temperature\n" +
            "M140 S0 ; turn off heatbed\n" +
            "M107 ; turn off fan\n" +
            "M84 ; disable motors"
        val baseConfig = File("src/main/assets/prusa3-base.json").readText()
        val config = PrusaConfigWriter.render(PrusaSliceSettings(), printer, start, end, baseConfig)
        val out = File("build/app-defaults.json")
        out.parentFile?.mkdirs()
        out.writeText(config)
        println("DUMPED " + out.absolutePath)
        println(config)
    }
}
