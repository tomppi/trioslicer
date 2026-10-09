package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.conical.ConicalSettings
import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import com.tomppi.enderslicer.nonplanar.NonPlanarSettings
import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlSliceTransform
import com.tomppi.enderslicer.viewer.VertexData
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class CuraResolvedSettingsWriterTest {
    @Test
    fun keepsStagedMeshBytesAndCopiesMeshSupportIntoExtruderScope() {
        val directory = Files.createTempDirectory("enderslicer-resolved").toFile()
        try {
            val modelFile = File(directory, "current.stl")
            writeTriangle(modelFile, 100.123456f, 100.654321f, 1f)
            val originalBytes = modelFile.readBytes()
            val destination = File(directory, "resolved-settings.json")
            val resolved = resolvedSettings(centerIsZero = false)

            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = modelFile.name,
                resolved = resolved,
            )

            val root = JSONObject(destination.readText())
            assertEquals("230", root.getJSONObject("global").getString("machine_width"))

            val extruder = root.getJSONObject("extruder.0")
            assertEquals("210", extruder.getString("material_print_temperature"))
            assertEquals("0", extruder.getString("support_infill_rate"))
            assertEquals("33.333", extruder.getString("support_interface_density"))
            assertTrue(extruder.getBoolean("support_enable"))
            assertTrue(extruder.getBoolean("support_interface_enable"))
            assertTrue(extruder.getBoolean("support_roof_enable"))
            assertEquals("0.2", extruder.getString("support_z_distance"))
            assertFalse(extruder.getBoolean("center_object"))
            assertEquals("[[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]", extruder.getString("mesh_rotation_matrix"))
            assertEquals(0.0, extruder.getDouble("enderslicer_mesh_translation_x"), 1e-12)
            assertEquals(0.0, extruder.getDouble("enderslicer_mesh_translation_y"), 1e-12)
            assertEquals(0.0, extruder.getDouble("enderslicer_mesh_translation_z"), 1e-12)
            assertEquals(-115.0, extruder.getDouble("mesh_position_x"), 1e-9)
            assertEquals(-115.0, extruder.getDouble("mesh_position_y"), 1e-9)
            assertEquals(0.0, extruder.getDouble("mesh_position_z"), 1e-9)

            val model = root.getJSONObject("current.stl")
            assertEquals(0, model.getInt("extruder_nr"))
            assertTrue(model.getBoolean("support_interface_enable"))
            assertTrue(model.getBoolean("support_roof_enable"))
            assertEquals(0.0, model.getDouble("enderslicer_mesh_translation_x"), 1e-12)
            assertEquals(0.0, model.getDouble("enderslicer_mesh_translation_y"), 1e-12)
            assertEquals(0.0, model.getDouble("enderslicer_mesh_translation_z"), 1e-12)
            assertEquals(-115.0, model.getDouble("mesh_position_x"), 1e-9)
            assertEquals(-115.0, model.getDouble("mesh_position_y"), 1e-9)
            assertEquals(0.0, model.getDouble("mesh_position_z"), 1e-9)

            assertArrayEquals(
                "Writing resolved settings must not rewrite or re-round STL coordinates",
                originalBytes,
                modelFile.readBytes(),
            )
            val firstVertex = firstVertex(modelFile)
            assertEquals(100.123456, firstVertex[0].toDouble(), 1e-5)
            assertEquals(100.654321, firstVertex[1].toDouble(), 1e-5)
            assertEquals(1.0, firstVertex[2].toDouble(), 1e-6)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun writesExplicitRequestTransformForTheRequestLocalSourceMesh() {
        val requestRoot = Files.createTempDirectory("enderslicer-direct-affine").toFile()
        try {
            val stagingDirectory = File(requestRoot, "staging").apply { mkdirs() }
            val displayedFile = File(stagingDirectory, "transformed.stl")
            val sourceVertices = interleavedTriangle(10f, 20f, 30f)
            val displayedVertices = interleavedTriangle(100f, 110f, 0f)
            val transform = StlSliceTransform(
                linear = listOf(
                    1.0, 0.0, 0.0,
                    0.0, 0.0, -1.0,
                    0.0, 1.0, 0.0,
                ),
                translationXmm = 115.25,
                translationYmm = 114.75,
                translationZmm = 22.462965929567872,
            )
            StlMeshWriter.writeBinary(
                StlMesh(
                    displayName = "test.stl",
                    interleavedVertices = VertexData.fromArray(displayedVertices),
                    triangleCount = 1,
                    bounds = MeshBounds(100f, 110f, 0f, 101f, 111f, 1f),
                    slicingSourceInterleavedVertices = VertexData.fromArray(sourceVertices),
                    slicingTransform = transform,
                ),
                displayedFile,
            )
            val stagedSource = requireNotNull(StlMeshWriter.resolvedSliceSource(displayedFile))

            val requestDirectory = File(requestRoot, "request").apply { mkdirs() }
            val modelFile = File(requestDirectory, "current.stl")
            stagedSource.modelFile.copyTo(modelFile)
            val sourceBytes = modelFile.readBytes()
            val destination = File(requestDirectory, "resolved-settings.json")

            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = modelFile.name,
                resolved = resolvedSettings(centerIsZero = false),
                modelTransform = stagedSource.transform,
            )

            assertArrayEquals(sourceBytes, modelFile.readBytes())
            val firstVertex = firstVertex(modelFile)
            assertEquals(10.0, firstVertex[0].toDouble(), 1e-6)
            assertEquals(20.0, firstVertex[1].toDouble(), 1e-6)
            assertEquals(30.0, firstVertex[2].toDouble(), 1e-6)

            val root = JSONObject(destination.readText())
            val model = root.getJSONObject("current.stl")
            assertEquals("[[1.0,0.0,0.0],[0.0,0.0,-1.0],[0.0,1.0,0.0]]", model.getString("mesh_rotation_matrix"))
            assertEquals(115.25, model.getDouble("enderslicer_mesh_translation_x"), 1e-12)
            assertEquals(114.75, model.getDouble("enderslicer_mesh_translation_y"), 1e-12)
            assertEquals(22.462965929567872, model.getDouble("enderslicer_mesh_translation_z"), 1e-12)
            assertEquals(-115.0, model.getDouble("mesh_position_x"), 1e-9)
            assertEquals(-115.0, model.getDouble("mesh_position_y"), 1e-9)
            assertEquals(0.0, model.getDouble("mesh_position_z"), 1e-12)

            val extruder = root.getJSONObject("extruder.0")
            assertEquals(115.25, extruder.getDouble("enderslicer_mesh_translation_x"), 1e-12)
            assertEquals(114.75, extruder.getDouble("enderslicer_mesh_translation_y"), 1e-12)
            assertEquals(22.462965929567872, extruder.getDouble("enderslicer_mesh_translation_z"), 1e-12)
        } finally {
            requestRoot.deleteRecursively()
        }
    }

    @Test
    fun keepsZeroOffsetsAndMeshBytesForCenterOriginMachines() {
        val directory = Files.createTempDirectory("enderslicer-resolved-center").toFile()
        try {
            val modelFile = File(directory, "current.stl")
            writeTriangle(modelFile, 10f, 20f, 1f)
            val originalBytes = modelFile.readBytes()
            val destination = File(directory, "resolved-settings.json")
            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = modelFile.name,
                resolved = resolvedSettings(centerIsZero = true),
            )
            val root = JSONObject(destination.readText())
            val extruder = root.getJSONObject("extruder.0")
            assertEquals(0.0, extruder.getDouble("mesh_position_x"), 1e-9)
            assertEquals(0.0, extruder.getDouble("mesh_position_y"), 1e-9)
            assertEquals(0.0, extruder.getDouble("mesh_position_z"), 1e-9)
            assertEquals(0.0, extruder.getDouble("enderslicer_mesh_translation_x"), 1e-12)
            assertEquals(0.0, extruder.getDouble("enderslicer_mesh_translation_y"), 1e-12)
            assertEquals(0.0, extruder.getDouble("enderslicer_mesh_translation_z"), 1e-12)
            assertArrayEquals(originalBytes, modelFile.readBytes())
            assertEquals(10.0, firstVertex(modelFile)[0].toDouble(), 1e-6)
            assertEquals(20.0, firstVertex(modelFile)[1].toDouble(), 1e-6)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun overhangFillEnablesBridgeDetectionOnTheModelSectionOnlyWhenEnabled() {
        val directory = Files.createTempDirectory("enderslicer-resolved-bridge").toFile()
        try {
            val modelFile = File(directory, "current.stl")
            writeTriangle(modelFile, 100f, 100f, 1f)
            val destination = File(directory, "resolved-settings.json")

            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = modelFile.name,
                resolved = resolvedSettings(centerIsZero = false, waveEnabled = false),
            )
            assertFalse(
                "Bridge detection must stay at the definition default while overhang fill is off",
                JSONObject(destination.readText()).getJSONObject(modelFile.name).has("bridge_settings_enabled"),
            )

            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = modelFile.name,
                resolved = resolvedSettings(centerIsZero = false, waveEnabled = true),
            )
            assertTrue(
                "Overhang fill requires bridge detection on the model section",
                JSONObject(destination.readText()).getJSONObject(modelFile.name).getBoolean("bridge_settings_enabled"),
            )

            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = modelFile.name,
                resolved = resolvedSettings(centerIsZero = false, waveEnabled = false, brickEnabled = true),
            )
            assertTrue(
                "Brick walls require bridge detection on the model section",
                JSONObject(destination.readText()).getJSONObject(modelFile.name).getBoolean("bridge_settings_enabled"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * The resolved request is one mesh group, so it carries one key per plate object.
     * The engine reads the transform keys from the group's shared settings once per
     * key, so a key that omitted them would silently inherit the previous object's.
     */
    @Test
    fun writesOneKeyPerPlateObjectWithItsOwnTransform() {
        val directory = Files.createTempDirectory("enderslicer-resolved-plate").toFile()
        try {
            val first = File(directory, "model-1.stl")
            val second = File(directory, "model-2.stl")
            writeTriangle(first, 100f, 100f, 1f)
            writeTriangle(second, 20f, 30f, 1f)
            val destination = File(directory, "resolved-settings.json")
            val secondTransform = StlSliceTransform(
                linear = listOf(
                    0.0, 1.0, 0.0,
                    -1.0, 0.0, 0.0,
                    0.0, 0.0, 1.0,
                ),
                translationXmm = 11.5,
                translationYmm = 22.5,
                translationZmm = 0.0,
            )

            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = first.name,
                resolved = resolvedSettings(centerIsZero = false),
                additionalObjects = listOf(
                    CuraResolvedSettingsWriter.PlateObject(
                        fileName = second.name,
                        transform = secondTransform,
                    ),
                ),
            )

            val root = JSONObject(destination.readText())
            val firstKey = root.getJSONObject(first.name)
            val secondKey = root.getJSONObject(second.name)
            assertEquals(
                "[[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]",
                firstKey.getString("mesh_rotation_matrix"),
            )
            assertEquals(
                "[[0.0,1.0,0.0],[-1.0,0.0,0.0],[0.0,0.0,1.0]]",
                secondKey.getString("mesh_rotation_matrix"),
            )
            assertEquals(0.0, firstKey.getDouble("enderslicer_mesh_translation_x"), 1e-12)
            assertEquals(11.5, secondKey.getDouble("enderslicer_mesh_translation_x"), 1e-12)
            assertEquals(22.5, secondKey.getDouble("enderslicer_mesh_translation_y"), 1e-12)
            assertEquals(0.0, secondKey.getDouble("enderslicer_mesh_translation_z"), 1e-12)
            // Shared group settings as far as the engine is concerned: extruder_nr and
            // mesh_position must be on every key or the second model loads with the
            // first model's values.
            assertEquals(0, secondKey.getInt("extruder_nr"))
            assertEquals(-115.0, secondKey.getDouble("mesh_position_x"), 1e-9)
            assertEquals(-115.0, secondKey.getDouble("mesh_position_y"), 1e-9)
            assertTrue(secondKey.getBoolean("support_interface_enable"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun featuresBuiltForOneModelAreRefusedForAPlate() {
        val directory = Files.createTempDirectory("enderslicer-resolved-plate-refusals").toFile()
        try {
            val first = File(directory, "model-1.stl")
            val second = File(directory, "model-2.stl")
            writeTriangle(first, 100f, 100f, 1f)
            writeTriangle(second, 20f, 30f, 1f)
            val destination = File(directory, "resolved-settings.json")

            fun writePlate(additional: List<CuraResolvedSettingsWriter.PlateObject>) {
                CuraResolvedSettingsWriter.write(
                    destination = destination,
                    modelFileName = first.name,
                    resolved = resolvedSettings(centerIsZero = false),
                    additionalObjects = additional,
                )
            }

            SmartInfillRuntime.activate(null)
            NonPlanarRuntime.activate(NonPlanarSettings())
            ConicalRuntime.activate(ConicalSettings())
            val smartInfillError = runCatching {
                writePlate(
                    listOf(
                        CuraResolvedSettingsWriter.PlateObject(
                            fileName = second.name,
                            smartInfillModifiers = listOf(SmartInfillModifier(35, second)),
                        ),
                    ),
                )
            }.exceptionOrNull()
            assertTrue(smartInfillError is IllegalArgumentException)
            assertTrue(smartInfillError?.message.orEmpty().contains("Smart Infill"))

            NonPlanarRuntime.activate(NonPlanarSettings(enabled = true))
            val nonPlanarError = runCatching {
                writePlate(listOf(CuraResolvedSettingsWriter.PlateObject(fileName = second.name)))
            }.exceptionOrNull()
            NonPlanarRuntime.activate(NonPlanarSettings())
            assertTrue(nonPlanarError is IllegalArgumentException)
            assertTrue(nonPlanarError?.message.orEmpty().contains("Non-planar"))

            ConicalRuntime.activate(ConicalSettings(enabled = true))
            val conicalError = runCatching {
                writePlate(listOf(CuraResolvedSettingsWriter.PlateObject(fileName = second.name)))
            }.exceptionOrNull()
            ConicalRuntime.activate(ConicalSettings())
            assertTrue(conicalError is IllegalArgumentException)
            assertTrue(conicalError?.message.orEmpty().contains("Conical"))
        } finally {
            SmartInfillRuntime.activate(null)
            NonPlanarRuntime.activate(NonPlanarSettings())
            ConicalRuntime.activate(ConicalSettings())
            directory.deleteRecursively()
        }
    }

    private fun resolvedSettings(
        centerIsZero: Boolean,
        waveEnabled: Boolean = false,
        brickEnabled: Boolean = false,
    ): CuraSliceSettingsResolver.Result = CuraSliceSettingsResolver.Result(
        globalValues = mapOf(
            "machine_width" to "230",
            "machine_depth" to "230",
            "machine_height" to "250",
            "machine_shape" to "rectangular",
            "machine_center_is_zero" to centerIsZero.toString(),
        ),
        extruderValues = mapOf(
            "material_print_temperature" to "210",
            "support_infill_rate" to "0",
            "support_interface_density" to "33.333",
            "enderslicer_wave_overhang_enabled" to waveEnabled.toString(),
            "enderslicer_arc_overhang_enabled" to "false",
            "enderslicer_brick_wall_enabled" to brickEnabled.toString(),
        ),
        modelValues = mapOf(
            "support_enable" to "true",
            "support_interface_enable" to "true",
            "support_roof_enable" to "true",
            "support_z_distance" to "0.2",
            "support_xy_distance" to "0.8",
        ),
        expressionCount = 400,
        passes = 5,
    )

    private fun interleavedTriangle(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        x, y, z, 0f, 0f, 1f,
        x + 1f, y, z, 0f, 0f, 1f,
        x, y + 1f, z + 1f, 0f, 0f, 1f,
    )

    private fun writeTriangle(file: File, x: Float, y: Float, z: Float) {
        val buffer = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        buffer.putInt(1)
        buffer.putFloat(0f).putFloat(0f).putFloat(1f)
        buffer.putFloat(x).putFloat(y).putFloat(z)
        buffer.putFloat(x + 1f).putFloat(y).putFloat(z)
        buffer.putFloat(x).putFloat(y + 1f).putFloat(z + 1f)
        buffer.putShort(0)
        file.writeBytes(buffer.array())
    }

    private fun firstVertex(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes, 84 + 12, 12).order(ByteOrder.LITTLE_ENDIAN)
        return floatArrayOf(buffer.float, buffer.float, buffer.float)
    }
}
