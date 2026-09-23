package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlParser
import com.tomppi.enderslicer.viewer.VertexData
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThicknessAdaptiveWallsTest {

    @Test
    fun modifierSlabsStayInsideBuildVolumeAtBedLevel() {
        val directory = Files.createTempDirectory("adaptive-walls-bed").toFile()
        try {
            val modelFile = File(directory, "box.stl")
            writeBox(modelFile, centerX = 100f, centerY = 100f, zBottom = 0f, sizeXY = 10f, height = 20f)

            val modifiers = ThicknessAdaptiveWalls.generate(
                modelFile = modelFile,
                settings = SlicerSettings(),
                destination = File(directory, "modifiers"),
            )

            assertTrue("Bend regions on a square box must produce modifiers", modifiers.isNotEmpty())
            modifiers.forEach { modifier ->
                val bounds = StlParser.parse(modifier.file, modifier.file.name, MeshTriangleLimits.current()).bounds
                assertTrue(
                    "Modifier ${modifier.file.name} dips below the build plate: minZ=${bounds.minZ}",
                    bounds.minZ >= 0f,
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun modifierSlabsStayInsideBuildVolumeAtFullHeight() {
        val directory = Files.createTempDirectory("adaptive-walls-ceiling").toFile()
        try {
            val settings = SlicerSettings()
            val modelFile = File(directory, "box.stl")
            writeBox(modelFile, centerX = 100f, centerY = 100f, zBottom = 230f, sizeXY = 10f, height = 20f)

            val modifiers = ThicknessAdaptiveWalls.generate(
                modelFile = modelFile,
                settings = settings,
                destination = File(directory, "modifiers"),
            )

            assertTrue(modifiers.isNotEmpty())
            modifiers.forEach { modifier ->
                val bounds = StlParser.parse(modifier.file, modifier.file.name, MeshTriangleLimits.current()).bounds
                assertTrue(
                    "Modifier ${modifier.file.name} exceeds the gantry height: maxZ=${bounds.maxZ}",
                    bounds.maxZ <= settings.machineHeightMm.toFloat(),
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun slabPaddingIsKeptWhereItFits() {
        val directory = Files.createTempDirectory("adaptive-walls-floating").toFile()
        try {
            val modelFile = File(directory, "box.stl")
            writeBox(modelFile, centerX = 100f, centerY = 100f, zBottom = 5f, sizeXY = 10f, height = 20f)

            val modifiers = ThicknessAdaptiveWalls.generate(
                modelFile = modelFile,
                settings = SlicerSettings(),
                destination = File(directory, "modifiers"),
            )

            assertTrue(modifiers.isNotEmpty())
            modifiers.forEach { modifier ->
                val bounds = StlParser.parse(modifier.file, modifier.file.name, MeshTriangleLimits.current()).bounds
                assertTrue("Slab must keep its 0.1 mm pad below a floating model: minZ=${bounds.minZ}", bounds.minZ < 5f)
                assertTrue(bounds.minZ >= 0f)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeBox(file: File, centerX: Float, centerY: Float, zBottom: Float, sizeXY: Float, height: Float) {
        val x0 = centerX - sizeXY / 2f
        val x1 = centerX + sizeXY / 2f
        val y0 = centerY - sizeXY / 2f
        val y1 = centerY + sizeXY / 2f
        val z0 = zBottom
        val z1 = zBottom + height
        val triangles = mutableListOf<Float>()
        fun tri(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float,
        ) {
            triangles.add(ax); triangles.add(ay); triangles.add(az)
            triangles.add(bx); triangles.add(by); triangles.add(bz)
            triangles.add(cx); triangles.add(cy); triangles.add(cz)
        }
        tri(x0, y0, z0, x1, y1, z0, x1, y0, z0); tri(x0, y0, z0, x0, y1, z0, x1, y1, z0)
        tri(x0, y0, z1, x1, y0, z1, x1, y1, z1); tri(x0, y0, z1, x1, y1, z1, x0, y1, z1)
        tri(x0, y0, z0, x1, y0, z0, x1, y0, z1); tri(x0, y0, z0, x1, y0, z1, x0, y0, z1)
        tri(x0, y1, z0, x1, y1, z1, x1, y1, z0); tri(x0, y1, z0, x0, y1, z1, x1, y1, z1)
        tri(x0, y0, z0, x0, y0, z1, x0, y1, z1); tri(x0, y0, z0, x0, y1, z1, x0, y1, z0)
        tri(x1, y0, z0, x1, y1, z1, x1, y0, z1); tri(x1, y0, z0, x1, y1, z0, x1, y1, z1)

        val interleaved = FloatArray(triangles.size / 9 * 18)
        var source = 0
        var target = 0
        repeat(triangles.size / 9) {
            repeat(3) {
                interleaved[target++] = triangles[source++]
                interleaved[target++] = triangles[source++]
                interleaved[target++] = triangles[source++]
                interleaved[target++] = 0f
                interleaved[target++] = 0f
                interleaved[target++] = 1f
            }
        }
        StlMeshWriter.writeBinary(
            StlMesh(
                displayName = file.name,
                interleavedVertices = VertexData.fromArray(interleaved),
                triangleCount = triangles.size / 9,
                bounds = MeshBounds(x0, y0, z0, x1, y1, z1),
            ),
            file,
        )
    }
}
