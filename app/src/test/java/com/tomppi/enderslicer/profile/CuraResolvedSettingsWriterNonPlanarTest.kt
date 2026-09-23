package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import com.tomppi.enderslicer.nonplanar.NonPlanarSettings
import com.tomppi.enderslicer.viewer.StlSliceTransform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraResolvedSettingsWriterNonPlanarTest {
    @Test
    fun replacesPreStagedRequestModelAndSignalsThatTheCopyAlreadyHappened() {
        val root = Files.createTempDirectory("enderslicer-curvi-staging").toFile()
        try {
            val displayed = File(root, "transformed.stl").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
            }
            val requestDirectory = File(root, "request").apply { mkdirs() }
            val destination = File(requestDirectory, "model.stl").apply {
                writeBytes(byteArrayOf(9, 9, 9))
            }
            val displayedBytes = displayed.readBytes()
            var copyCount = 0
            NonPlanarRuntime.activate(NonPlanarSettings(enabled = true))

            val transform = CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(
                stagedDisplayedFile = displayed,
                destination = destination,
                copyFile = { source, target ->
                    copyCount++
                    check(!target.exists()) { "The destination file already exists." }
                    source.copyTo(target)
                },
            )

            // This mirrors CuraEngineRunner's caller contract: a non-null result
            // means the request-local model is already staged and must not be copied again.
            if (transform == null) {
                copyCount++
                check(!destination.exists()) { "The destination file already exists." }
                displayed.copyTo(destination)
            }

            assertNotNull(transform)
            assertTrue(copyCount == 1)
            assertArrayEquals(displayedBytes, destination.readBytes())
            assertArrayEquals(displayedBytes, displayed.readBytes())
            assertTrue(displayed.isFile)
        } finally {
            NonPlanarRuntime.activate(NonPlanarSettings(enabled = false))
            root.deleteRecursively()
        }
    }

    @Test
    fun ordinaryPlanarIdentityTransformIsNotTheNonPlanarStagingMarker() {
        val root = Files.createTempDirectory("enderslicer-planar-identity").toFile()
        try {
            NonPlanarRuntime.activate(NonPlanarSettings(enabled = false))
            val model = File(root, "model.stl")
            writeTriangle(model)
            val destination = File(root, "resolved-settings.json")
            val identity = StlSliceTransform(
                linear = listOf(
                    1.0, 0.0, 0.0,
                    0.0, 1.0, 0.0,
                    0.0, 0.0, 1.0,
                ),
                translationXmm = 0.0,
                translationYmm = 0.0,
                translationZmm = 0.0,
            )

            CuraResolvedSettingsWriter.write(
                destination = destination,
                modelFileName = model.name,
                resolved = CuraSliceSettingsResolver.Result(
                    globalValues = mapOf(
                        "machine_width" to "220",
                        "machine_depth" to "220",
                        "machine_height" to "250",
                        "machine_shape" to "rectangular",
                        "machine_center_is_zero" to "false",
                        "machine_gcode_flavor" to "marlin",
                    ),
                    extruderValues = emptyMap(),
                    modelValues = emptyMap(),
                    expressionCount = 0,
                    passes = 0,
                ),
                modelTransform = identity,
            )

            assertTrue(destination.isFile && destination.length() > 0L)
        } finally {
            NonPlanarRuntime.activate(NonPlanarSettings(enabled = false))
            root.deleteRecursively()
        }
    }

    private fun writeTriangle(file: File) {
        val buffer = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        buffer.putInt(1)
        buffer.putFloat(0f).putFloat(0f).putFloat(1f)
        buffer.putFloat(10f).putFloat(10f).putFloat(1f)
        buffer.putFloat(11f).putFloat(10f).putFloat(1f)
        buffer.putFloat(10f).putFloat(11f).putFloat(2f)
        buffer.putShort(0)
        file.writeBytes(buffer.array())
    }
}
