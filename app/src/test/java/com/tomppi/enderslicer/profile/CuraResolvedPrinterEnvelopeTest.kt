package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.engine.PrinterEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CuraResolvedPrinterEnvelopeTest {
    @Test
    fun resolvedWriterPersistsTheDependencyResolvedMachineEnvelope() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-resolved-envelope").toFile()
        val model = File(directory, "model.stl").also(::writeTriangle)
        val destination = File(directory, "resolved-settings.json")
        val resolved = CuraSliceSettingsResolver.Result(
            globalValues = mapOf(
                "machine_width" to "180",
                "machine_depth" to "160",
                "machine_height" to "220",
                "machine_shape" to "elliptic",
                "machine_center_is_zero" to "true",
            ),
            extruderValues = emptyMap(),
            modelValues = emptyMap(),
            expressionCount = 0,
            passes = 1,
        )

        CuraResolvedSettingsWriter.write(destination, model.name, resolved)

        assertEquals(
            PrinterEnvelope(180.0, 160.0, 220.0, "elliptic", true),
            PrinterEnvelope.readFrom(File(directory, PrinterEnvelope.METADATA_FILE_NAME)),
        )
    }

    private fun writeTriangle(file: File) {
        val buffer = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        buffer.putInt(1)
        buffer.putFloat(0f).putFloat(0f).putFloat(1f)
        buffer.putFloat(0f).putFloat(0f).putFloat(0f)
        buffer.putFloat(1f).putFloat(0f).putFloat(0f)
        buffer.putFloat(0f).putFloat(1f).putFloat(1f)
        buffer.putShort(0)
        file.writeBytes(buffer.array())
    }
}
