package com.tomppi.enderslicer.smartinfill

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartInfillModifierPlacementTest {
    @Test
    fun localFilaSimModifierIsTranslatedBackToAnalyzedPrinterCoordinates() {
        val root = Files.createTempDirectory("smart-infill-placement").toFile()
        try {
            val source = File(root, "source.stl")
            val modifier = File(root, "modifier-40pct.stl")
            writeTriangle(
                source,
                listOf(
                    floatArrayOf(100f, 105f, 5f),
                    floatArrayOf(130f, 105f, 5f),
                    floatArrayOf(100f, 125f, 30f),
                ),
            )
            writeTriangle(
                modifier,
                listOf(
                    floatArrayOf(-10f, -5f, 1f),
                    floatArrayOf(10f, -5f, 1f),
                    floatArrayOf(-10f, 5f, 20f),
                ),
            )
            val packageValue = SmartInfillPackage(
                id = "filasim-test",
                directory = root,
                sourceName = "source.stl",
                sourceSha256 = sha256(source),
                baseDensityPercent = 10.0,
                pattern = "cubic",
                mode = "graded",
                perimeters = 2,
                lineWidthMm = 0.45,
                topBottomLayers = 5,
                layerHeightMm = 0.2,
                upstreamCommit = "e7485ec22d4ebe8baca04190404fbb877c90e031",
                modifiers = listOf(SmartInfillModifier(40, modifier)),
            )

            val staged = packageValue.stageModifiers(File(root, "request"), source).single().file
            val bounds = binaryStlBounds(staged, 10)
            assertEquals(105.0, bounds.minX, 0.0001)
            assertEquals(125.0, bounds.maxX, 0.0001)
            assertEquals(110.0, bounds.minY, 0.0001)
            assertEquals(120.0, bounds.maxY, 0.0001)
            assertEquals(6.0, bounds.minZ, 0.0001)
            assertEquals(25.0, bounds.maxZ, 0.0001)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeTriangle(file: File, vertices: List<FloatArray>) {
        val bytes = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(1)
        bytes.putFloat(0f)
        bytes.putFloat(0f)
        bytes.putFloat(1f)
        vertices.forEach { point -> point.forEach { value -> bytes.putFloat(value) } }
        bytes.putShort(0)
        file.writeBytes(bytes.array())
    }
}
