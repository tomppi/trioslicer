package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the app's Prusa-dialect parsers over the real 3.0.0-alpha11 desktop
 * output (a11-appdefaults.gcode, produced by slicing the parity model with the
 * JSON config from [PrusaConfigWriter]). Guards against G-code format drift in
 * the upgrade: the sanitizer, layer preview and nozzle-path readers must all
 * accept the 3.x dialect.
 */
class PrusaAlpha11GcodePipelineTest {
    @Test
    fun alpha11DesktopGcodeRunsThroughAppParsers() {
        val source = File("C:/Users/FREDRIK/Documents/enderslicercura/.build/prusa-slicer/a11-appdefaults.gcode")
        assumeTrue("alpha11 desktop gcode not present on this machine", source.isFile)
        val dir = kotlin.io.path.createTempDirectory("prusa3-pipeline").toFile()
        val copy = File(dir, "alpha11.gcode")
        source.copyTo(copy, overwrite = true)

        val summary = GcodeSanitizer.validateAndRepair(
            file = copy,
            settingsTransport = "prusa-json",
            dialect = GcodeDialect.PRUSA,
        )
        println("ALPHA11-SUMMARY layers=" + summary.layerCount +
            " estimate=" + summary.estimatedSeconds +
            " filament=" + summary.filamentMillimeters)
        check(summary.layerCount == 137) { "expected 137 layers, got " + summary.layerCount }
        check(summary.estimatedSeconds != null) { "estimate must not be null" }

        val preview = GcodeLayerPreviewParser.parse(copy, GcodeDialect.PRUSA)
        check(preview != null) { "layer preview must parse alpha11 gcode" }
        check(preview.layers.isNotEmpty()) { "layer preview must contain layers" }
        println("ALPHA11-PREVIEW layers=" + preview.layers.size)
        dir.deleteRecursively()
    }
}
