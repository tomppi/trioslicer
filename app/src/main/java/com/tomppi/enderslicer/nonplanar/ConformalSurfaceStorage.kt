package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.engine.publishAtomic
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Request-side handoff between surface preparation and G-code projection. */
internal object ConformalSurfaceStorage {
    private const val FILE_NAME = "conformal-surface.bin"
    private const val MAGIC = 0x434E4652
    private const val VERSION = 1
    const val MAX_REGIONS = 256

    fun write(workspace: File, prepared: NonPlanarPipeline.ConformalPrepared) {
        require(workspace.isDirectory) { "Conformal surface workspace is unavailable" }
        require(prepared.surface.regions.size <= MAX_REGIONS) {
            "The model has " + prepared.surface.regions.size + " printable surface regions; " +
                "the limit is " + MAX_REGIONS
        }
        val destination = File(workspace, FILE_NAME)
        val temporary = File(workspace, "$FILE_NAME.tmp")
        temporary.delete()
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeDouble(prepared.layerHeightMm)
                output.writeDouble(prepared.settings.maximumZSpeedMmPerSecond)
                output.writeInt(prepared.settings.conformalShellLayers)
                output.writeInt(prepared.surface.regions.size)
                for (region in prepared.surface.regions) {
                    output.writeDouble(region.minX)
                    output.writeDouble(region.minY)
                    output.writeDouble(region.minZ)
                    output.writeDouble(region.maxX)
                    output.writeDouble(region.maxY)
                    output.writeDouble(region.maxZ)
                    output.writeDouble(region.areaMm2)
                    output.writeInt(region.triangleCount)
                    region.triangles.forEach(output::writeFloat)
                }
            }
            check(temporary.isFile && temporary.length() > 64L) { "Unable to persist the conformal surface" }
            publishAtomic(temporary, destination, "the conformal surface")
        } finally {
            temporary.delete()
        }
    }

    fun conformalStagedGcode(
        file: File,
        printerEnvelope: PrinterEnvelope,
    ): ConformalGcodeTransformer.Diagnostics? {
        val sidecar = File(file.parentFile, FILE_NAME)
        if (!sidecar.isFile) return null
        val prepared = read(sidecar)
        return prepared.transformGcode(file, printerEnvelope)
    }

    fun isPrepared(workspace: File): Boolean = File(workspace, FILE_NAME).isFile

    fun read(file: File): NonPlanarPipeline.ConformalPrepared =
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC) { "Invalid conformal surface marker" }
            require(input.readInt() == VERSION) { "Unsupported conformal surface version" }
            val layerHeightMm = input.readDouble()
            val maximumZSpeedMmPerSecond = input.readDouble()
            val conformalShellLayers = input.readInt()
            val regionCount = input.readInt()
            require(layerHeightMm.isFinite() && layerHeightMm in 0.04..1.2) {
                "Invalid conformal layer height"
            }
            require(regionCount in 0..MAX_REGIONS) { "Invalid conformal region count" }
            val regions = ArrayList<ConformalSurface.Region>(regionCount)
            repeat(regionCount) {
                val minX = input.readDouble()
                val minY = input.readDouble()
                val minZ = input.readDouble()
                val maxX = input.readDouble()
                val maxY = input.readDouble()
                val maxZ = input.readDouble()
                val area = input.readDouble()
                val triangleCount = input.readInt()
                require(triangleCount in 1..2_000_000) { "Invalid conformal region triangle count" }
                // Corrupt bounds must fail loudly here, before buildRegion's
                // grid allocation overflows on a nonsense span.
                require(minX <= maxX && maxX - minX <= 10_000.0) { "Invalid conformal region X bounds" }
                require(minY <= maxY && maxY - minY <= 10_000.0) { "Invalid conformal region Y bounds" }
                val triangles = FloatArray(triangleCount * 9) { input.readFloat() }
                require(triangles.all(Float::isFinite)) { "Conformal surface contains a non-finite vertex" }
                regions += ConformalSurfaceBuilder.rebuildRegion(
                    triangles = triangles,
                    minX = minX, minY = minY, minZ = minZ,
                    maxX = maxX, maxY = maxY, maxZ = maxZ,
                    areaMm2 = area,
                )
            }
            val settings = NonPlanarSettings(
                enabled = true,
                maximumZSpeedMmPerSecond = maximumZSpeedMmPerSecond,
                conformalShellLayers = conformalShellLayers,
            ).validated()
            NonPlanarPipeline.ConformalPrepared(
                surface = ConformalSurface(regions, ConformalDiagnostics(
                    sourceTriangles = 0,
                    candidateTriangles = 0,
                    regionsFound = regions.size,
                    regionsFilteredBySpan = 0,
                    regionsFilteredByArea = 0,
                )),
                settings = settings,
                layerHeightMm = layerHeightMm,
            )
        }
}
