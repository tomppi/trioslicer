package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** One-off harness: run the Prusa-dialect sanitizer against the on-device 342-layer gcode. */
class PrusaRealGcodeSanitizeHarnessTest {
    @Test
    fun realDeviceGcode() {
        val source = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_180857_770.gcode")
        assumeTrue("device gcode not present on this machine", source.isFile)
        val copy = File(kotlin.io.path.createTempDirectory("prusa-real").toFile(), "real.gcode")
        source.copyTo(copy, overwrite = true)
        val summary = GcodeSanitizer.validateAndRepair(
            file = copy,
            settingsTransport = "prusa-ini",
            dialect = GcodeDialect.PRUSA,
        )
        println("REAL-SUMMARY layers=" + summary.layerCount +
            " estimate=" + summary.estimatedSeconds +
            " filament=" + summary.filamentMillimeters)
        check(summary.layerCount == 342) { "expected 342 layers, got " + summary.layerCount }
        check(summary.estimatedSeconds != null) { "estimate must not be null" }
    }
}