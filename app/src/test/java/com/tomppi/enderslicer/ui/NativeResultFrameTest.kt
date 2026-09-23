package com.tomppi.enderslicer.ui

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.engine.CuraEngineCommand
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.smartinfill.SmartInfillModifier
import com.tomppi.enderslicer.smartinfill.SmartInfillNativeExport
import com.tomppi.enderslicer.smartinfill.SmartInfillPackage
import com.tomppi.enderslicer.smartinfill.sha256
import com.tomppi.enderslicer.viewer.StlParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The frame contract of a native result, on the host: the Part Topo body has to
 * come back exactly where the analyzed model was, and a modifier volume has to
 * survive the store's staging in the place the engine put it. The second one is
 * the failure the device showed — a volume staged 67 mm off the plate and the
 * slice refused with "Model vertex 1 ... X=267.71844".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NativeResultFrameTest {

    @Test
    fun theExportedPartTopoBodyLandsExactlyWhereTheAnalyzedModelWas() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = createTempDirectory("part-topo-frame").toFile()
        val analyzedFile = File(directory, "analyzed.stl").apply { writeBytes(placedTriangle()) }
        // The engine returns its body in the analyzed model's own coordinates.
        val engineBody = placedTriangle()

        val exported = SmartInfillNativeExport.writeOptimizedShape(
            directory = directory,
            sourceName = "s-hook.STL",
            shape = engineBody,
            analyzedSource = analyzedFile,
        )
        val analyzed = StlParser.parse(analyzedFile, "s-hook.STL", MeshTriangleLimits.current())

        val prepared = PartTopoResultPreparer.prepare(
            context = context,
            uri = Uri.fromFile(exported),
            analyzedDisplayedMesh = analyzed,
            printer = printer(),
            settings = settings(),
        )

        assertEquals(analyzed.bounds.minX, prepared.transformed.bounds.minX, 1e-3f)
        assertEquals(analyzed.bounds.maxX, prepared.transformed.bounds.maxX, 1e-3f)
        assertEquals(analyzed.bounds.minY, prepared.transformed.bounds.minY, 1e-3f)
        assertEquals(analyzed.bounds.maxY, prepared.transformed.bounds.maxY, 1e-3f)
        assertEquals(analyzed.bounds.minZ, prepared.transformed.bounds.minZ, 1e-3f)
        assertEquals(analyzed.bounds.maxZ, prepared.transformed.bounds.maxZ, 1e-3f)
    }

    @Test
    fun aStagedModifierVolumeLandsWhereTheEnginePutIt() {
        val directory = createTempDirectory("modifier-frame").toFile()
        val analyzedFile = File(directory, "analyzed.stl").apply { writeBytes(placedTriangle()) }
        // The engine's own volume: already placed on the plate, as the device
        // showed (X[39.9, 172.8] for the 30 % region of the s-hook).
        val engineVolume = File(directory, "engine-volume.stl").apply { writeBytes(volumeTriangle()) }
        val archive = zipOf("modifier_30pct.stl", engineVolume.readBytes())

        val exported = SmartInfillNativeExport.writeModifierArchive(
            directory = directory,
            sourceName = "s-hook.STL",
            archive = archive,
            analyzedSource = analyzedFile,
        )
        val stored = File(directory, "modifier-30pct.stl").apply {
            writeBytes(readZipEntry(exported.readBytes(), "modifier_30pct.stl"))
        }
        val packageValue = packageWith(stored, analyzedFile, directory)

        // Exactly what CuraEngineRunner does before it builds the command.
        val staged = packageValue.stageModifiers(File(directory, "staged"), analyzedFile)

        val engine = boundsOf(engineVolume)
        val placed = boundsOf(staged.single().file)
        assertEquals("the volume keeps the engine's own X", engine[0], placed[0], 1e-3f)
        assertEquals("and its own Y", engine[2], placed[2], 1e-3f)
        assertEquals("and its own Z", engine[4], placed[4], 1e-3f)
        assertTrue("X stays on the 210 mm bed, was ${placed[1]}", placed[1] <= 210f)
        assertTrue("Y stays on the 220 mm bed, was ${placed[3]}", placed[3] <= 220f)
    }

    /** One triangle at X[36.5,173.5] Y[61,159] Z[0,12], a placed model. */
    private fun placedTriangle(): ByteArray = binaryStl(36.5f, 61f, 0f, 173.5f, 61f, 0f, 36.5f, 159f, 12f)

    /** One triangle where the engine puts a 30 % region: X[40,172] Y[90,130] Z[1,11]. */
    private fun volumeTriangle(): ByteArray = binaryStl(40f, 90f, 1f, 172f, 90f, 1f, 40f, 130f, 11f)

    private fun binaryStl(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
    ): ByteArray {
        val bytes = ByteArray(84 + 50)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        buffer.putInt(1)
        buffer.position(84 + 12)
        buffer.putFloat(x0).putFloat(y0).putFloat(z0)
        buffer.putFloat(x1).putFloat(y1).putFloat(z1)
        buffer.putFloat(x2).putFloat(y2).putFloat(z2)
        return bytes
    }

    private fun boundsOf(file: File): FloatArray {
        val data = file.readBytes()
        val triangles = ByteBuffer.wrap(data, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val xs = ArrayList<Float>(triangles * 3)
        val ys = ArrayList<Float>(triangles * 3)
        val zs = ArrayList<Float>(triangles * 3)
        for (triangle in 0 until triangles) {
            val base = 84 + triangle * 50
            for (vertex in 0 until 3) {
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                buffer.position(base + 12 + vertex * 12)
                xs += buffer.float
                ys += buffer.float
                zs += buffer.float
            }
        }
        return floatArrayOf(xs.min(), xs.max(), ys.min(), ys.max(), zs.min(), zs.max())
    }

    private fun zipOf(name: String, body: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(body)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun readZipEntry(archive: ByteArray, name: String): ByteArray {
        ZipInputStream(archive.inputStream()).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val bytes = input.readBytes()
                if (entry.name == name) return bytes
                entry = input.nextEntry
            }
        }
        error("$name is missing from the archive")
    }

    private fun printer() = PrinterDefinition(
        name = "Test printer",
        widthMm = 210.0,
        depthMm = 220.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = false,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -10.0,
        printheadYMinMm = -10.0,
        printheadXMaxMm = 10.0,
        printheadYMaxMm = 10.0,
        gantryHeightMm = 20.0,
    )

    private fun settings() = SlicerSettings(
        machineWidthMm = 210.0,
        machineDepthMm = 220.0,
        machineHeightMm = 250.0,
    )

    @Test
    fun theSliceCommandAcceptsTheVolumesItStaged() {
        val directory = createTempDirectory("slice-command-fixed").toFile()
        val analyzedFile = File(directory, "analyzed.stl").apply { writeBytes(placedTriangle()) }
        val archive = zipOf("modifier_30pct.stl", volumeTriangle())
        val exported = SmartInfillNativeExport.writeModifierArchive(
            directory = directory,
            sourceName = "s-hook.STL",
            archive = archive,
            analyzedSource = analyzedFile,
        )
        val stored = File(directory, "modifier-30pct.stl").apply {
            writeBytes(readZipEntry(exported.readBytes(), "modifier_30pct.stl"))
        }
        val staged = packageWith(stored, analyzedFile, directory)
            .stageModifiers(File(directory, "staged"), analyzedFile)

        val command = CuraEngineCommand.build(
            executablePath = "/native/libcuraengine_exec.so",
            definitionsDirectory = "/files/definitions",
            machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
            extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
            modelPath = analyzedFile.absolutePath,
            outputPath = File(directory, "out.gcode").absolutePath,
            printer = printer(),
            settings = settings(),
            startGcode = "G28",
            endGcode = "M104 S0",
            smartInfillModifiers = staged,
            threadCount = 2,
        )

        assertTrue("the command was assembled", command.isNotEmpty())
    }

    @Test
    fun theSliceCommandRefusesAVolumeThatWasNotRecentered() {
        val directory = createTempDirectory("slice-command-broken").toFile()
        val analyzedFile = File(directory, "analyzed.stl").apply { writeBytes(placedTriangle()) }
        // The engine-frame volume handed over without the export's recentering:
        // staging translates it by the model's centre and base a second time.
        val notRecentered = File(directory, "engine-frame.stl").apply { writeBytes(volumeTriangle()) }
        val staged = packageWith(notRecentered, analyzedFile, directory)
            .stageModifiers(File(directory, "staged"), analyzedFile)

        val failure = runCatching {
            CuraEngineCommand.build(
                executablePath = "/native/libcuraengine_exec.so",
                definitionsDirectory = "/files/definitions",
                machineDefinitionPath = "/files/definitions/creality_ender3.def.json",
                extruderDefinitionPath = "/files/definitions/creality_base_extruder_0.def.json",
                modelPath = analyzedFile.absolutePath,
                outputPath = File(directory, "out.gcode").absolutePath,
                printer = printer(),
                settings = settings(),
                startGcode = "G28",
                endGcode = "M104 S0",
                smartInfillModifiers = staged,
                threadCount = 2,
            )
        }.exceptionOrNull()

        assertTrue("expected the envelope check to refuse it, got $failure", failure is PrinterEnvelope.OutsideBuildVolumeException)
        assertTrue(
            "and now it names the volume: ${failure?.message}",
            failure?.message.orEmpty().contains("Smart Infill 30% modifier"),
        )
    }

    private fun packageWith(
        modifier: File,
        analyzedFile: File,
        directory: File,
    ) = SmartInfillPackage(
        id = "test-package",
        directory = directory,
        sourceName = "s-hook.STL",
        sourceSha256 = sha256(analyzedFile),
        baseDensityPercent = 10.0,
        pattern = "cubic",
        mode = "graded",
        perimeters = 2,
        lineWidthMm = 0.45,
        topBottomLayers = 3,
        layerHeightMm = 0.2,
        upstreamCommit = "e7485ec22d4ebe8baca04190404fbb877c90e031",
        modifiers = listOf(SmartInfillModifier(30, modifier)),
    )
}