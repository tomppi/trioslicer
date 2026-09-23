package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.viewer.StlParser
import java.io.File
import java.nio.channels.ClosedByInterruptException

/**
 * Android-native non-planar pipeline (Ahlers' method).
 *
 * The displayed STL is sliced by CuraEngine exactly as shown. Afterwards the
 * ConformalGcodeTransformer projects the top toolpaths straight down onto the
 * printable surface regions found on the mesh, removes the stair steps they
 * replace, and emits conformal shells that ride the true 3D surface - the
 * nozzle dives below the layer plane to the thinnest part of the model and
 * climbs to the thickest.
 */
internal object NonPlanarPipeline {
    /** True non-planar slicing: the model is NOT warped; its toolpath is. */
    data class ConformalPrepared(
        val surface: ConformalSurface,
        val settings: NonPlanarSettings,
        val layerHeightMm: Double,
    ) {
        fun transformGcode(
            file: File,
            printerEnvelope: PrinterEnvelope,
        ): ConformalGcodeTransformer.Diagnostics = ConformalGcodeTransformer.transform(
            file = file,
            surface = surface,
            layerHeightMm = layerHeightMm,
            maximumZSpeedMmPerSecond = settings.maximumZSpeedMmPerSecond,
            conformalShellLayers = settings.conformalShellLayers,
            printerEnvelope = printerEnvelope,
        )
    }

    fun prepareConformal(
        modelFile: File,
        settings: NonPlanarSettings,
        layerHeightMm: Double,
        nozzleDiameterMm: Double,
    ): ConformalPrepared {
        require(modelFile.isFile && modelFile.length() > 0L) { "Non-planar input STL is missing" }
        val safe = settings.validated()
        require(safe.enabled) { "Non-planar printing is not enabled" }
        require(layerHeightMm.isFinite() && layerHeightMm in 0.04..1.2) { "Invalid non-planar layer height" }
        require(nozzleDiameterMm.isFinite() && nozzleDiameterMm in 0.1..2.0) { "Invalid non-planar nozzle diameter" }
        val mesh = parseCancellable(modelFile)
        val surface = ConformalSurfaceBuilder.build(mesh, safe)
        require(surface.regions.isNotEmpty()) {
            "Non-planar printing found no printable surface region: " +
                "lower the maximum path slope or raise the maximum lift so the model's top surface qualifies"
        }
        // Fail before the expensive slice instead of at sidecar write time.
        require(surface.regions.size <= ConformalSurfaceStorage.MAX_REGIONS) {
            "The model has " + surface.regions.size + " printable surface regions; " +
                "the limit is " + ConformalSurfaceStorage.MAX_REGIONS
        }
        return ConformalPrepared(surface, safe, layerHeightMm)
    }

    private fun parseCancellable(file: File): com.tomppi.enderslicer.viewer.StlMesh = try {
        StlParser.parse(file, file.name)
    } catch (closed: ClosedByInterruptException) {
        throw InterruptedException("Non-planar processing was cancelled")
    }
}
