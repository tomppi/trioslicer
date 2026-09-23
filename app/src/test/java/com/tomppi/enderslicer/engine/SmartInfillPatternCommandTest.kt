package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.smartinfill.SmartInfillActivity
import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import com.tomppi.enderslicer.smartinfill.SmartInfillPackage
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.smartinfill.applyTo
import com.tomppi.enderslicer.smartinfill.sha256
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartInfillPatternCommandTest {
    @After
    fun clearRuntime() {
        SmartInfillRuntime.activate(null)
    }

    @Test
    fun binaryCommandKeepsCubicBaseAndConcentricSolidPattern() {
        val fixture = fixture(
            mode = "binary",
            basePattern = "cubic",
            densities = listOf(100),
            binarySolidPattern = "concentric",
        )
        fixture.use { (_, packageValue, command, model, modifiers) ->
            val modelSettings = settingsAfter(command, model, modifiers.first())
            val modifierSettings = settingsAfter(command, modifiers.first(), null)
            assertEquals("cubic", setting(modelSettings, "infill_pattern"))
            assertEquals("12.0", setting(modelSettings, "infill_line_distance"))
            assertEquals("concentric", setting(modifierSettings, "infill_pattern"))
            assertEquals("0.4", setting(modifierSettings, "infill_line_distance"))
            assertEquals("0", setting(modifierSettings, "wall_line_count"))
            assertEquals("concentric", packageValue.binarySolidPattern)
        }
    }

    @Test
    fun gradedHundredPercentCommandUsesRectilinearRule() {
        val fixture = fixture(
            mode = "graded",
            basePattern = "gyroid",
            densities = listOf(35, 100),
        )
        fixture.use { (_, _, command, model, modifiers) ->
            val baseSettings = settingsAfter(command, model, modifiers[0])
            val mediumSettings = settingsAfter(command, modifiers[0], modifiers[1])
            val fullSettings = settingsAfter(command, modifiers[1], null)
            assertEquals("gyroid", setting(baseSettings, "infill_pattern"))
            assertEquals("gyroid", setting(mediumSettings, "infill_pattern"))
            assertEquals("zigzag", setting(fullSettings, "infill_pattern"))
            assertEquals("0.4", setting(fullSettings, "infill_line_distance"))
        }
    }

    private fun fixture(
        mode: String,
        basePattern: String,
        densities: List<Int>,
        binarySolidPattern: String? = null,
    ): Fixture {
        val directory = Files.createTempDirectory("smart-infill-pattern-command").toFile()
        val model = File(directory, "model.stl")
        writeTriangle(model, 100f, 100f, 0.2f)
        val modifierFiles = densities.mapIndexed { index, density ->
            File(directory, "modifier-${density}pct.stl").also {
                writeTriangle(it, 101f + index, 101f + index, 0.4f)
            }
        }
        val modifiers = densities.zip(modifierFiles) { density, file -> SmartInfillModifier(density, file) }
        val packageValue = SmartInfillPackage(
            id = "command-pattern",
            directory = directory,
            sourceName = model.name,
            sourceSha256 = sha256(model),
            baseDensityPercent = 10.0,
            pattern = basePattern,
            mode = mode,
            perimeters = 2,
            lineWidthMm = 0.4,
            topBottomLayers = 4,
            layerHeightMm = 0.2,
            upstreamCommit = SmartInfillActivity.FILASIM_COMMIT,
            modifiers = modifiers,
            binarySolidPattern = binarySolidPattern,
        )
        SmartInfillRuntime.activate(packageValue)
        val command = CuraEngineCommand.build(
            executablePath = "/native/libcuraengine_exec.so",
            definitionsDirectory = "/files/definitions",
            machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
            extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
            modelPath = model.absolutePath,
            outputPath = File(directory, "output.gcode").absolutePath,
            printer = printer,
            settings = packageValue.applyTo(SlicerSettings()),
            startGcode = "G28",
            endGcode = "M104 S0",
            smartInfillModifiers = modifiers,
            threadCount = 2,
        )
        return Fixture(directory, packageValue, command, model, modifierFiles)
    }

    private fun settingsAfter(command: List<String>, mesh: File, nextMesh: File?): List<String> {
        val start = command.indexOf(mesh.absolutePath)
        assertTrue("Mesh was not loaded: ${mesh.name}", start >= 0)
        val end = nextMesh?.let { command.indexOf(it.absolutePath) } ?: command.indexOf("-o")
        assertTrue("Mesh settings boundary is invalid", end > start)
        return command.subList(start + 1, end)
    }

    private fun setting(settings: List<String>, key: String): String = settings
        .lastOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?: error("Missing command setting: $key")

    private fun writeTriangle(file: File, x: Float, y: Float, z: Float) {
        val bytes = ByteBuffer.allocate(134).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(1)
        repeat(3) { bytes.putFloat(0f) }
        bytes.putFloat(x)
        bytes.putFloat(y)
        bytes.putFloat(z)
        bytes.putFloat(x + 1f)
        bytes.putFloat(y)
        bytes.putFloat(z)
        bytes.putFloat(x)
        bytes.putFloat(y + 1f)
        bytes.putFloat(z)
        bytes.putShort(0)
        file.writeBytes(bytes.array())
    }

    private data class Fixture(
        val directory: File,
        val packageValue: SmartInfillPackage,
        val command: List<String>,
        val model: File,
        val modifiers: List<File>,
    ) : AutoCloseable {
        override fun close() {
            SmartInfillRuntime.activate(null)
            directory.deleteRecursively()
        }
    }

    private val printer = PrinterDefinition(
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
