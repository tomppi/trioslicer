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
import com.tomppi.enderslicer.smartinfill.sha256
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the exact patched CuraEngine built from 5.14.0-alpha.0. Local unit runs
 * skip when CURAENGINE_HOST_BINARY is absent; CI supplies the pinned host build.
 */
class SmartInfillCuraEngineIntegrationTest {
    @After
    fun clearRuntime() {
        SmartInfillRuntime.activate(null)
    }

    @Test
    fun fallbackTransportProducesDistinctGradedAndBinaryRegions() {
        val engine = hostEngine()
        runFixture(engine, Transport.FALLBACK, Mode.GRADED)
        runFixture(engine, Transport.FALLBACK, Mode.BINARY)
    }

    @Test
    fun resolvedTransportProducesDistinctGradedAndBinaryRegions() {
        val engine = hostEngine()
        runFixture(engine, Transport.RESOLVED, Mode.GRADED)
        runFixture(engine, Transport.RESOLVED, Mode.BINARY)
    }

    private fun runFixture(engine: File, transport: Transport, mode: Mode) {
        val directory = Files.createTempDirectory("smart-infill-${transport.name.lowercase()}-${mode.name.lowercase()}").toFile()
        try {
            val model = File(directory, "model.stl")
            writeBox(model, MAIN)
            val modifierBoxes = when (mode) {
                Mode.GRADED -> listOf(
                    35 to Box(102.0, 123.0, 95.0, 135.0, 0.0, 8.0),
                    70 to Box(127.0, 148.0, 95.0, 135.0, 0.0, 8.0),
                )
                Mode.BINARY -> listOf(
                    100 to Box(118.0, 148.0, 95.0, 135.0, 0.0, 8.0),
                )
            }
            val modifiers = modifierBoxes.map { (density, box) ->
                val file = File(directory, "modifier-${density}pct.stl")
                writeBox(file, box)
                SmartInfillModifier(density, file)
            }
            val packageValue = SmartInfillPackage(
                id = "fixture-${transport.name.lowercase()}-${mode.name.lowercase()}",
                directory = directory,
                sourceName = model.name,
                sourceSha256 = sha256(model),
                baseDensityPercent = 10.0,
                pattern = "grid",
                mode = mode.name.lowercase(),
                perimeters = 2,
                lineWidthMm = 0.4,
                topBottomLayers = 4,
                layerHeightMm = 0.2,
                upstreamCommit = SmartInfillActivity.FILASIM_COMMIT,
                modifiers = modifiers,
            )
            SmartInfillRuntime.activate(packageValue)

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
                    settings = SlicerSettings(),
                    startGcode = START_GCODE,
                    endGcode = END_GCODE,
                    smartInfillModifiers = modifiers,
                    threadCount = 2,
                )
                Transport.RESOLVED -> {
                    val resolved = CuraSliceSettingsResolver.resolve(
                        profile = pinnedProfile(),
                        printer = printer,
                        settings = SlicerSettings(),
                        startGcode = START_GCODE,
                        endGcode = END_GCODE,
                    )
                    val request = File(directory, "resolved-settings.json")
                    CuraResolvedSettingsWriter.write(
                        destination = request,
                        modelFileName = model.name,
                        resolved = resolved,
                        smartInfillModifiers = modifiers,
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

            val engineLog = File(directory, "curaengine.log")
            val process = ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(engineLog)
                .start()
            val finished = process.waitFor(3, TimeUnit.MINUTES)
            if (!finished) process.destroyForcibly()
            assertTrue(
                "Pinned CuraEngine timed out for $transport/$mode\n${engineLog.readTextSafe()}",
                finished,
            )
            assertTrue(
                "Pinned CuraEngine failed for $transport/$mode with ${process.exitValue()}\n${engineLog.readTextSafe()}",
                process.exitValue() == 0,
            )
            assertTrue("CuraEngine produced no G-code for $transport/$mode", output.length() > 512L)

            val analysis = analyze(output)
            assertMainShell(analysis, transport, mode)
            when (mode) {
                Mode.GRADED -> {
                    val base = analysis.fillLength(Rect(83.0, 99.0, 99.0, 131.0))
                    val medium = analysis.fillLength(Rect(105.0, 120.0, 99.0, 131.0))
                    val high = analysis.fillLength(Rect(130.0, 145.0, 99.0, 131.0))
                    assertTrue("No base-region infill for $transport: $base", base > 5.0)
                    assertTrue(
                        "35% region did not exceed 10% base for $transport: base=$base medium=$medium",
                        medium > base * 1.45,
                    )
                    assertTrue(
                        "70% region did not exceed 35% region for $transport: medium=$medium high=$high",
                        high > medium * 1.25,
                    )
                }
                Mode.BINARY -> {
                    val base = analysis.fillLength(Rect(83.0, 108.0, 99.0, 131.0))
                    val solid = analysis.fillLength(Rect(123.0, 145.0, 99.0, 131.0))
                    assertTrue("No binary base-region infill for $transport: $base", base > 5.0)
                    assertTrue(
                        "100% region did not produce a materially denser toolpath for $transport: base=$base solid=$solid",
                        solid > base * 3.0,
                    )
                }
            }
        } finally {
            SmartInfillRuntime.activate(null)
            directory.deleteRecursively()
        }
    }

    private fun assertMainShell(analysis: Analysis, transport: Transport, mode: Mode) {
        val points = analysis.extrusionPoints
        assertTrue("No extrusion points for $transport/$mode", points.isNotEmpty())
        assertTrue(
            "Main left shell is missing for $transport/$mode",
            points.any { abs(it.x - MAIN.minX) <= 1.2 && it.y in (MAIN.minY - 1.0)..(MAIN.maxY + 1.0) },
        )
        assertTrue(
            "Main right shell is missing for $transport/$mode",
            points.any { abs(it.x - MAIN.maxX) <= 1.2 && it.y in (MAIN.minY - 1.0)..(MAIN.maxY + 1.0) },
        )
        assertTrue(
            "Main front shell is missing for $transport/$mode",
            points.any { abs(it.y - MAIN.minY) <= 1.2 && it.x in (MAIN.minX - 1.0)..(MAIN.maxX + 1.0) },
        )
        assertTrue(
            "Main rear shell is missing for $transport/$mode",
            points.any { abs(it.y - MAIN.maxY) <= 1.2 && it.x in (MAIN.minX - 1.0)..(MAIN.maxX + 1.0) },
        )
    }

    private fun analyze(file: File): Analysis {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var e = 0.0
        var absolutePosition = true
        var absoluteExtrusion = true
        var feature = ""
        val fillSegments = mutableListOf<Segment>()
        val extrusionPoints = mutableListOf<Point>()

        file.forEachLine { rawLine ->
            val line = rawLine.substringBefore(';').trim()
            when {
                rawLine.startsWith(";TYPE:") -> feature = rawLine.substringAfter(";TYPE:").trim()
                line == "G90" -> absolutePosition = true
                line == "G91" -> absolutePosition = false
                line == "M82" -> absoluteExtrusion = true
                line == "M83" -> absoluteExtrusion = false
                line.startsWith("G92") -> {
                    tokenValue(line, 'X')?.let { x = it }
                    tokenValue(line, 'Y')?.let { y = it }
                    tokenValue(line, 'Z')?.let { z = it }
                    tokenValue(line, 'E')?.let { e = it }
                }
                line.startsWith("G0 ") || line.startsWith("G1 ") -> {
                    val oldX = x
                    val oldY = y
                    val oldE = e
                    tokenValue(line, 'X')?.let { value -> x = if (absolutePosition) value else x + value }
                    tokenValue(line, 'Y')?.let { value -> y = if (absolutePosition) value else y + value }
                    tokenValue(line, 'Z')?.let { value -> z = if (absolutePosition) value else z + value }
                    val eToken = tokenValue(line, 'E')
                    if (eToken != null) e = if (absoluteExtrusion) eToken else e + eToken
                    val extrusion = eToken != null && e > oldE + 1e-8
                    val length = distance(oldX, oldY, x, y)
                    if (extrusion && length > 1e-6) {
                        extrusionPoints += Point(oldX, oldY)
                        extrusionPoints += Point(x, y)
                        if (feature == "FILL" && z in 1.2..6.8) {
                            fillSegments += Segment(oldX, oldY, x, y)
                        }
                    }
                }
            }
        }
        return Analysis(fillSegments, extrusionPoints)
    }

    private fun tokenValue(line: String, letter: Char): Double? = line
        .split(' ')
        .firstOrNull { token -> token.length > 1 && token[0] == letter }
        ?.substring(1)
        ?.toDoubleOrNull()

    private fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun clippedLength(segment: Segment, rect: Rect): Double {
        val dx = segment.x2 - segment.x1
        val dy = segment.y2 - segment.y1
        var lower = 0.0
        var upper = 1.0
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(
            segment.x1 - rect.minX,
            rect.maxX - segment.x1,
            segment.y1 - rect.minY,
            rect.maxY - segment.y1,
        )
        for (index in p.indices) {
            val pi = p[index]
            val qi = q[index]
            if (abs(pi) < 1e-12) {
                if (qi < 0.0) return 0.0
            } else {
                val ratio = qi / pi
                if (pi < 0.0) lower = max(lower, ratio) else upper = min(upper, ratio)
                if (lower > upper) return 0.0
            }
        }
        return distance(segment.x1, segment.y1, segment.x2, segment.y2) * (upper - lower).coerceAtLeast(0.0)
    }

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
        listOf(
            File("src/main/assets/cura/definitions"),
            File("app/src/main/assets/cura/definitions"),
        ).firstOrNull { directory -> File(directory, MACHINE_DEFINITION).isFile }
            ?: error("Pinned Cura definitions are unavailable; run scripts/fetch-cura-resources.sh")
    }

    private fun File.readTextSafe(): String = runCatching { readText() }.getOrDefault("")

    private inner class Analysis(
        val fillSegments: List<Segment>,
        val extrusionPoints: List<Point>,
    ) {
        fun fillLength(rect: Rect): Double = fillSegments.sumOf { segment -> clippedLength(segment, rect) }
    }

    private data class Box(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double,
        val minZ: Double,
        val maxZ: Double,
    )

    private data class Rect(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double)
    private data class Point(val x: Double, val y: Double)
    private data class Vertex(val x: Double, val y: Double, val z: Double)
    private data class Triangle(val a: Vertex, val b: Vertex, val c: Vertex)
    private data class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double)
    private enum class Transport { FALLBACK, RESOLVED }
    private enum class Mode { GRADED, BINARY }

    private companion object {
        val MAIN = Box(80.0, 150.0, 95.0, 135.0, 0.0, 8.0)
        const val MACHINE_DEFINITION = "creality_ender3.def.json"
        const val EXTRUDER_DEFINITION = "creality_base_extruder_0.def.json"
        val DEFINITION_FILES = listOf(
            "fdmprinter.def.json",
            "fdmextruder.def.json",
            "creality_base.def.json",
            EXTRUDER_DEFINITION,
            MACHINE_DEFINITION,
        )
        const val START_GCODE = "G28\nM104 S200\nM109 S200"
        const val END_GCODE = "M104 S0\nM140 S0\nM84"

        val printer = PrinterDefinition(
            name = "Smart Infill Fixture",
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
}
