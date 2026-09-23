package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Builds the PC-twin config from the uploaded device gcode using the app writer. */
class PrusaDeviceTwinTest {
    @Test
    fun buildDeviceTwin() {
        val deviceGcode = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_180857_770.gcode")
        assumeTrue("device gcode not present on this machine", deviceGcode.isFile)
        val lines = deviceGcode.readLines().map { it.trimEnd() }
        val firstLayer = lines.indexOfFirst { it == ";LAYER_CHANGE" }
        val lastCustom = lines.indexOfLast { it == ";TYPE:Custom" }
        val startBlock = lines.subList(1, firstLayer).filter { it.isNotBlank() && !it.startsWith(";") && !it.startsWith("#") }
        val endBlock = lines.subList(lastCustom + 1, lines.size).filter { it.isNotBlank() && !it.startsWith(";") && !it.startsWith("#") }
        val start = startBlock.joinToString("\n")
        val end = endBlock.joinToString("\n")

        val printer = PrinterDefinition(
            name = "Modified Ender 3 V2", widthMm = 230.0, depthMm = 230.0, heightMm = 250.0,
            buildPlateShape = "rectangular", originAtCenter = false,
            heatedBed = true, heatedBuildVolume = false, gcodeFlavor = "Marlin", extruders = 1,
            nozzleSizeMm = 0.4, filamentDiameterMm = 1.75,
            printheadXMinMm = -26.0, printheadYMinMm = -32.0,
            printheadXMaxMm = 32.0, printheadYMaxMm = 34.0, gantryHeightMm = 25.0,
        )
        val profileSource = File("C:/Users/FREDRIK/Documents/enderslicercura/.build/prusa-slicer/gui-full.ini")
        val imported = com.tomppi.enderslicer.model.PrusaConfigImporter.parse(profileSource.readText())
        val baseConfig = File("src/main/assets/prusa3-base.json").readText()
        val config = PrusaConfigWriter.render(imported.settings, printer.copy(nozzleSizeMm = imported.nozzleSizeMm ?: 0.4), start, end, baseConfig)
        val out = File("build/devtwin.json")
        out.parentFile?.mkdirs()
        out.writeText(config)
        println("TWIN " + out.absolutePath + " startBytes=" + start.length + " endBytes=" + end.length)
    }
}