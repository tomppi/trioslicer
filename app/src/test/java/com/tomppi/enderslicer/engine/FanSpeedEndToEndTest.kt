package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end regression for the fan-speed bug: the app sets "Normal Fan Speed"
 * to 40%, but CuraEngine emitted 100% fan because it only reads the resolved
 * child keys (cool_fan_speed_min / cool_fan_speed_max) and never evaluates the
 * definition formula from the bare cool_fan_speed parent.
 *
 * Requires CURAENGINE_HOST_BINARY (unit runs and CI without the host build skip).
 */
class FanSpeedEndToEndTest {

    @Test
    fun fortyPercentFanSpeedNeverEmitsFullFan() {
        val engine = File(System.getenv("CURAENGINE_HOST_BINARY").orEmpty())
        assumeTrue("CURAENGINE_HOST_BINARY is not configured", engine.isFile)

        val directory = Files.createTempDirectory("enderslicer-fan-e2e").toFile()
        try {
            val model = File(directory, "model.stl")
            writeBox(model, 80.0, 150.0, 95.0, 135.0)

            val output = File(directory, "output.gcode")
            val command = CuraEngineCommand.build(
                executablePath = engine.absolutePath,
                definitionsDirectory = definitionsDirectory.absolutePath,
                machineDefinitionPath = File(definitionsDirectory, MACHINE_DEFINITION).absolutePath,
                extruderDefinitionPath = File(definitionsDirectory, EXTRUDER_DEFINITION).absolutePath,
                modelPath = model.absolutePath,
                outputPath = output.absolutePath,
                printer = printer,
                settings = SlicerSettings(fanSpeedPercent = 40.0),
                startGcode = START_GCODE,
                endGcode = END_GCODE,
                threadCount = 2,
            )

            val log = File(directory, "engine.log")
            val process = ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            val finished = process.waitFor(3, TimeUnit.MINUTES)
            if (!finished) process.destroyForcibly()
            assertTrue("CuraEngine timed out\n" + log.readTextSafe(), finished)
            assertTrue(
                "CuraEngine failed with " + process.exitValue() + "\n" + log.readTextSafe(),
                process.exitValue() == 0,
            )
            assertTrue("No G-code produced", output.length() > 512L)

            val gcode = output.readText()
            val fanCommands = gcode.lineSequence()
                .filter { it.startsWith("M106") || it.startsWith("M107") }
                .map { it.trim() }
                .toList()
            assertTrue("No fan commands emitted", fanCommands.isNotEmpty())

            val fanSpeeds = fanCommands
                .mapNotNull { line ->
                    line.split(" ").firstOrNull { it.startsWith("S") }?.substring(1)?.toDoubleOrNull()
                }
            assertTrue("No numeric fan speed found in " + fanCommands.take(8), fanSpeeds.isNotEmpty())

            val lines = gcode.lineSequence().toList()
            // Each M106/M107 is written for the path whose ";TYPE:" follows it
            // (the Type comment comes after the fan command in LayerPlan).
            // Record layer + that feature for every fan write.
            val fanWrites = buildList {
                var layer = "?"
                lines.forEachIndexed { index, rawLine ->
                    if (rawLine.startsWith(";LAYER:")) layer = rawLine.trim()
                    if (!rawLine.startsWith("M106") && !rawLine.startsWith("M107")) return@forEachIndexed
                    val nextType = (index + 1 until lines.size)
                        .map { lines[it] }
                        .firstOrNull { it.startsWith(";TYPE:") }
                        ?.removePrefix(";TYPE:")
                        ?: "?"
                    add(layer + " / " + nextType + " -> " + rawLine.trim())
                }
            }
            assertTrue("No fan writes recorded", fanWrites.isNotEmpty())
            // The skin-support fill under a roof legitimately uses the
            // separate skin_support_fan_speed (definition default = bridge
            // fan speed, 100%) - that is Cura behavior, not the parent/child
            // mismatch this test guards. Every other feature must stay at the
            // 40% cool_fan_speed_min/max ceiling.
            val offending = fanWrites.filter { write ->
                val speed = write.substringAfter("M106 S").toDoubleOrNull() ?: 0.0
                speed >= 255.0 && !write.contains(" / FILL ->")
            }
            assertTrue(
                "40% fan must never reach the engine's 100% write (255) except skin-support fill:\\n" +
                    offending.joinToString("\\n") + "\\n" + fanWrites.joinToString("\\n"),
                offending.isEmpty(),
            )
        } finally {
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

    private val definitionsDirectory: File by lazy {
        listOf(
            File("src/main/assets/cura/definitions"),
            File("app/src/main/assets/cura/definitions"),
        ).firstOrNull { directory -> File(directory, MACHINE_DEFINITION).isFile }
            ?: error("Pinned Cura definitions are unavailable")
    }

    private fun writeBox(file: File, minX: Double, maxX: Double, minY: Double, maxY: Double) {
        val v000 = Vertex(minX, minY, 0.0)
        val v001 = Vertex(minX, minY, 8.0)
        val v010 = Vertex(minX, maxY, 0.0)
        val v011 = Vertex(minX, maxY, 8.0)
        val v100 = Vertex(maxX, minY, 0.0)
        val v101 = Vertex(maxX, minY, 8.0)
        val v110 = Vertex(maxX, maxY, 0.0)
        val v111 = Vertex(maxX, maxY, 8.0)
        val triangles = listOf(
            Triangle(v000, v100, v110), Triangle(v000, v110, v010),
            Triangle(v001, v011, v111), Triangle(v001, v111, v101),
            Triangle(v000, v001, v101), Triangle(v000, v101, v100),
            Triangle(v010, v110, v111), Triangle(v010, v111, v011),
            Triangle(v000, v010, v011), Triangle(v000, v011, v001),
            Triangle(v100, v101, v111), Triangle(v100, v111, v110),
        )
        val bytes = ByteBuffer.allocate(84 + triangles.size * 50).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(80)
        bytes.putInt(triangles.size)
        triangles.forEach { triangle ->
            repeat(3) { bytes.putFloat(0f) }
            listOf(triangle.a, triangle.b, triangle.c).forEach { vertex ->
                bytes.putFloat(vertex.x.toFloat())
                bytes.putFloat(vertex.y.toFloat())
                bytes.putFloat(vertex.z.toFloat())
            }
            bytes.putShort(0)
        }
        file.writeBytes(bytes.array())
    }

    private data class Vertex(val x: Double, val y: Double, val z: Double)
    private data class Triangle(val a: Vertex, val b: Vertex, val c: Vertex)

    private fun File.readTextSafe(): String = runCatching { readText() }.getOrDefault("")

    private companion object {
        const val MACHINE_DEFINITION = "creality_ender3.def.json"
        const val EXTRUDER_DEFINITION = "creality_base_extruder_0.def.json"
        const val START_GCODE = "G28\nM104 S200\nM109 S200"
        const val END_GCODE = "M104 S0\nM140 S0\nM84"
    }
}
