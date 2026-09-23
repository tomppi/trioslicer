package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile
import com.tomppi.enderslicer.profile.CuraResolvedSettingsWriter
import com.tomppi.enderslicer.profile.CuraSliceSettingsResolver
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
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real CuraEngine guard for BUG-001: modifier meshes partition infill only. */
class SmartInfillModifierShellIntegrationTest {
    @After
    fun clearRuntime() {
        SmartInfillRuntime.activate(null)
    }

    @Test
    fun fallbackAndResolvedModifiersDoNotCreateInternalWallsOrSkins() {
        val engine = hostEngine()
        Transport.entries.forEach { transport ->
            Mode.entries.forEach { mode -> runFixture(engine, transport, mode) }
        }
    }

    private fun runFixture(engine: File, transport: Transport, mode: Mode) {
        val directory = Files.createTempDirectory("smart-infill-shell-${transport.name.lowercase()}-${mode.name.lowercase()}").toFile()
        try {
            val model = File(directory, "model.stl")
            writeBox(model, MAIN)
            val density = if (mode == Mode.BINARY) 100 else 35
            val modifierFile = File(directory, "modifier-${density}pct.stl")
            writeBox(modifierFile, MODIFIER)
            val modifier = SmartInfillModifier(density, modifierFile)
            val packageValue = SmartInfillPackage(
                id = "shell-${transport.name.lowercase()}-${mode.name.lowercase()}",
                directory = directory,
                sourceName = model.name,
                sourceSha256 = sha256(model),
                baseDensityPercent = 10.0,
                pattern = "cubic",
                mode = mode.name.lowercase(),
                perimeters = 3,
                lineWidthMm = 0.4,
                topBottomLayers = 5,
                layerHeightMm = 0.2,
                upstreamCommit = SmartInfillActivity.FILASIM_COMMIT,
                modifiers = listOf(modifier),
            )
            SmartInfillRuntime.activate(packageValue)
            val settings = packageValue.applyTo(SlicerSettings())
            val output = File(directory, "output.gcode")
            val command = when (transport) {
                Transport.FALLBACK -> CuraEngineCommand.build(
                    executablePath = engine.absolutePath,
                    definitionsDirectory = definitionsDirectory.absolutePath,
                    machineDefinitionPath = File(definitionsDirectory, MACHINE_DEFINITION).absolutePath,
                    extruderDefinitionPath = File(definitionsDirectory, EXTRUDER_DEFINITION).absolutePath,
                    modelPath = model.absolutePath,
                    outputPath = output.absolutePath,
                    printer = printer,
                    settings = settings,
                    startGcode = START_GCODE,
                    endGcode = END_GCODE,
                    smartInfillModifiers = listOf(modifier),
                    threadCount = 2,
                )
                Transport.RESOLVED -> {
                    val resolved = CuraSliceSettingsResolver.resolve(
                        profile = pinnedProfile(),
                        printer = printer,
                        settings = settings,
                        startGcode = START_GCODE,
                        endGcode = END_GCODE,
                    )
                    val request = File(directory, "resolved-settings.json")
                    CuraResolvedSettingsWriter.write(
                        destination = request,
                        modelFileName = model.name,
                        resolved = resolved,
                        smartInfillModifiers = listOf(modifier),
                    )
                    CuraEngineCommand.buildResolved(
                        executablePath = engine.absolutePath,
                        definitionsDirectory = definitionsDirectory.absolutePath,
                        resolvedSettingsPath = request.absolutePath,
                        outputPath = output.absolutePath,
                        threadCount = 2,
                    )
                }
            }

            val log = File(directory, "curaengine.log")
            val process = ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            val finished = process.waitFor(3, TimeUnit.MINUTES)
            if (!finished) process.destroyForcibly()
            assertTrue("CuraEngine timed out for $transport/$mode\n${log.readTextSafe()}", finished)
            assertTrue(
                "CuraEngine failed for $transport/$mode with ${process.exitValue()}\n${log.readTextSafe()}",
                process.exitValue() == 0,
            )

            val analysis = analyze(output)
            assertTrue("No outer shell for $transport/$mode", analysis.outerWallLength > 20.0)
            assertTrue(
                "Modifier generated internal walls for $transport/$mode: ${analysis.internalWallLength} mm",
                analysis.internalWallLength < 0.05,
            )
            assertTrue(
                "Modifier generated internal skin for $transport/$mode: ${analysis.internalSkinLength} mm",
                analysis.internalSkinLength < 0.05,
            )
            assertTrue("Regional infill disappeared for $transport/$mode", analysis.internalFillLength > 5.0)
        } finally {
            SmartInfillRuntime.activate(null)
            directory.deleteRecursively()
        }
    }

    private fun analyze(file: File): Analysis {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var e = 0.0
        var absolutePosition = true
        var absoluteExtrusion = true
        var feature = ""
        var outerWallLength = 0.0
        var internalWallLength = 0.0
        var internalSkinLength = 0.0
        var internalFillLength = 0.0

        file.forEachLine { raw ->
            val line = raw.substringBefore(';').trim()
            when {
                raw.startsWith(";TYPE:") -> feature = raw.substringAfter(";TYPE:").trim()
                line == "G90" -> absolutePosition = true
                line == "G91" -> absolutePosition = false
                line == "M82" -> absoluteExtrusion = true
                line == "M83" -> absoluteExtrusion = false
                line.startsWith("G92") -> tokenValue(line, 'E')?.let { e = it }
                line.startsWith("G0 ") || line.startsWith("G1 ") -> {
                    val oldX = x
                    val oldY = y
                    val oldE = e
                    tokenValue(line, 'X')?.let { x = if (absolutePosition) it else x + it }
                    tokenValue(line, 'Y')?.let { y = if (absolutePosition) it else y + it }
                    tokenValue(line, 'Z')?.let { z = if (absolutePosition) it else z + it }
                    tokenValue(line, 'E')?.let { e = if (absoluteExtrusion) it else e + it }
                    val length = hypot(x - oldX, y - oldY)
                    if (length <= 1e-6 || e <= oldE + 1e-8) return@forEachLine
                    val mx = (oldX + x) * 0.5
                    val my = (oldY + y) * 0.5
                    val wall = feature == "WALL-INNER" || feature == "WALL-OUTER"
                    if (wall && nearOuterBoundary(mx, my)) outerWallLength += length
                    if (wall && z in 1.5..6.5 && nearModifierBoundary(mx, my)) internalWallLength += length
                    if (feature == "SKIN" && z in 1.5..6.5 && insideModifier(mx, my)) internalSkinLength += length
                    if (feature == "FILL" && z in 2.2..5.8 && insideModifier(mx, my)) internalFillLength += length
                }
            }
        }
        return Analysis(outerWallLength, internalWallLength, internalSkinLength, internalFillLength)
    }

    private fun nearOuterBoundary(x: Double, y: Double): Boolean =
        abs(x - MAIN.minX) < 1.5 || abs(x - MAIN.maxX) < 1.5 ||
            abs(y - MAIN.minY) < 1.5 || abs(y - MAIN.maxY) < 1.5

    private fun nearModifierBoundary(x: Double, y: Double): Boolean =
        (y in MODIFIER.minY..MODIFIER.maxY &&
            (abs(x - MODIFIER.minX) < 1.5 || abs(x - MODIFIER.maxX) < 1.5)) ||
            (x in MODIFIER.minX..MODIFIER.maxX &&
                (abs(y - MODIFIER.minY) < 1.5 || abs(y - MODIFIER.maxY) < 1.5))

    private fun insideModifier(x: Double, y: Double): Boolean =
        x in (MODIFIER.minX + 1.0)..(MODIFIER.maxX - 1.0) &&
            y in (MODIFIER.minY + 1.0)..(MODIFIER.maxY - 1.0)

    private fun tokenValue(line: String, letter: Char): Double? = line
        .split(' ')
        .firstOrNull { it.length > 1 && it[0] == letter }
        ?.substring(1)
        ?.toDoubleOrNull()

    private fun writeBox(file: File, box: Box) {
        val v000 = Vertex(box.minX, box.minY, box.minZ)
        val v001 = Vertex(box.minX, box.minY, box.maxZ)
        val v010 = Vertex(box.minX, box.maxY, box.minZ)
        val v011 = Vertex(box.minX, box.maxY, box.maxZ)
        val v100 = Vertex(box.maxX, box.minY, box.minZ)
        val v101 = Vertex(box.maxX, box.minY, box.maxZ)
        val v110 = Vertex(box.maxX, box.maxY, box.minZ)
        val v111 = Vertex(box.maxX, box.maxY, box.maxZ)
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

    private fun pinnedProfile(): CuraEngineProfile {
        val files = DEFINITION_FILES.associateWith { name -> File(definitionsDirectory, name).readText() }
        return CuraEngineProfile(
            definitionFiles = files,
            machineDefinitionFileName = MACHINE_DEFINITION,
            extruderDefinitionFileName = EXTRUDER_DEFINITION,
        )
    }

    private fun hostEngine(): File {
        val raw = System.getenv("CURAENGINE_HOST_BINARY").orEmpty()
        assumeTrue("CURAENGINE_HOST_BINARY is not configured", raw.isNotBlank())
        val file = File(raw)
        assertTrue("Host CuraEngine is missing or not executable: $raw", file.isFile && file.canExecute())
        return file
    }

    private val definitionsDirectory: File by lazy {
        listOf(File("src/main/assets/cura/definitions"), File("app/src/main/assets/cura/definitions"))
            .firstOrNull { File(it, MACHINE_DEFINITION).isFile }
            ?: error("Pinned Cura definitions are unavailable; run scripts/fetch-cura-resources.sh")
    }

    private fun File.readTextSafe(): String = runCatching { readText() }.getOrDefault("")

    private enum class Transport { FALLBACK, RESOLVED }
    private enum class Mode { GRADED, BINARY }
    private data class Analysis(
        val outerWallLength: Double,
        val internalWallLength: Double,
        val internalSkinLength: Double,
        val internalFillLength: Double,
    )
    private data class Box(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double,
        val minZ: Double,
        val maxZ: Double,
    )
    private data class Vertex(val x: Double, val y: Double, val z: Double)
    private data class Triangle(val a: Vertex, val b: Vertex, val c: Vertex)

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

    companion object {
        private val MAIN = Box(80.0, 150.0, 90.0, 140.0, 0.0, 8.0)
        private val MODIFIER = Box(105.0, 125.0, 102.0, 128.0, 2.0, 6.0)
        private const val MACHINE_DEFINITION = "creality_ender3.def.json"
        private const val EXTRUDER_DEFINITION = "creality_base_extruder_0.def.json"
        private val DEFINITION_FILES = listOf(
            "fdmprinter.def.json",
            "fdmextruder.def.json",
            "creality_base.def.json",
            EXTRUDER_DEFINITION,
            MACHINE_DEFINITION,
        )
        private const val START_GCODE = "G28\nG92 E0"
        private const val END_GCODE = "M104 S0\nM140 S0"
    }
}
