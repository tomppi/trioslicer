package com.tomppi.enderslicer.data

import android.content.res.AssetManager
import com.tomppi.enderslicer.model.PrinterDefinition

/** Built-in default without synchronous asset parsing during ViewModel construction. */
object PrinterDefinitionLoader {
    @Suppress("UNUSED_PARAMETER")
    fun loadModifiedEnder3V2(assets: AssetManager): PrinterDefinition = MODIFIED_ENDER_3_V2

    private val MODIFIED_ENDER_3_V2 = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 230.0,
        depthMm = 230.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0,
        printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0,
        printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )
}
