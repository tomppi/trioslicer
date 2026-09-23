package com.tomppi.enderslicer.nonplanar

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConformalSurfaceStorageTest {
    @Test
    fun preparedSurfaceRoundTripsThroughTheSidecar() {
        val directory = Files.createTempDirectory("conformal-sidecar").toFile()
        try {
            val mesh = testMesh(*domeTriangles(0f, 0f, 0f, 80f, 80f, 12f).toTypedArray(), name = "dome.stl")
            val settings = NonPlanarSettings(
                enabled = true,
                conformalShellLayers = 4,
                maximumLiftMm = 15.0,
            )
            val surface = ConformalSurfaceBuilder.build(mesh, settings)
            val prepared = NonPlanarPipeline.ConformalPrepared(surface, settings, 0.2)
            ConformalSurfaceStorage.write(directory, prepared)
            assertTrue(ConformalSurfaceStorage.isPrepared(directory))

            val readBack = ConformalSurfaceStorage.read(File(directory, "conformal-surface.bin"))
            assertEquals(0.2, readBack.layerHeightMm, 1e-12)
            assertEquals(4, readBack.settings.conformalShellLayers)
            assertEquals(1, readBack.surface.regions.size)
            val region = readBack.surface.regions.single()
            assertEquals(12.0, region.maxZ, 1e-6)
            assertEquals(12.0, region.surfaceZ(40.0, 40.0)!!, 1e-3)
            assertEquals(0.0, region.surfaceZ(0.0, 0.0)!!, 1e-3)
        } finally {
            directory.deleteRecursively()
        }
    }
}
