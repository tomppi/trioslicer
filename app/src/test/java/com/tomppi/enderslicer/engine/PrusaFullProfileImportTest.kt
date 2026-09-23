package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaConfigImporter
import com.tomppi.enderslicer.model.PrusaSliceSettings
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Round-trips the real PC profile (gui-full.ini) through the app importer -> writer. */
class PrusaFullProfileImportTest {
    @Test
    fun importRealProfileAndRewriteConfig() {
        // Local dev fixture only (repo/.build/prusa-slicer/gui-full.ini); CI and
        // fresh checkouts skip this test instead of failing on a missing file.
        val repoRoot = File(System.getProperty("user.dir")).parentFile
        val source = File(repoRoot, ".build/prusa-slicer/gui-full.ini")
        assumeTrue("gui-full.ini not available at " + source.absolutePath, source.isFile)
        val imported = PrusaConfigImporter.parse(source.readText())
        val printer = PrinterDefinition(
            name = "Ender 3 V2", widthMm = 230.0, depthMm = 230.0, heightMm = 250.0,
            buildPlateShape = "rectangular",
            originAtCenter = false, heatedBed = true, heatedBuildVolume = false,
            gcodeFlavor = "Marlin", extruders = 1,
            nozzleSizeMm = imported.nozzleSizeMm ?: 0.4,
            filamentDiameterMm = imported.filamentDiameterMm ?: 1.75,
            printheadXMinMm = -26.0, printheadYMinMm = -32.0,
            printheadXMaxMm = 32.0, printheadYMaxMm = 34.0,
            gantryHeightMm = 25.0,
        )
        val baseConfig = File("src/main/assets/prusa3-base.json").readText()
        val config = PrusaConfigWriter.render(
            imported.settings,
            printer,
            requireNotNull(imported.startGcode),
            requireNotNull(imported.endGcode),
            baseConfig,
        )
        val out = File("build/imported-full.json")
        out.parentFile?.mkdirs()
        out.writeText(config)
        println("IMPORTED-SUMMARY")
        println("layer=" + imported.settings.layerHeightMm)
        println("first=" + imported.settings.firstLayerHeightMm)
        println("perimeters=" + imported.settings.perimeters)
        println("topSolid=" + imported.settings.topSolidLayers)
        println("bottomSolid=" + imported.settings.bottomSolidLayers)
        println("fill=" + imported.settings.fillDensityPercent + " " + imported.settings.fillPattern)
        println("speed=" + imported.settings.printSpeedMmPerSecond)
        println("t=" + imported.settings.nozzleTemperatureC + "/" + imported.settings.bedTemperatureC)
        println("startKeyCount=" + imported.unusedKeyCount)
        println("WROTE " + out.absolutePath + " bytes=" + out.length())
    }
}
