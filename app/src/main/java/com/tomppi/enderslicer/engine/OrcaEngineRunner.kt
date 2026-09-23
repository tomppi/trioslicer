package com.tomppi.enderslicer.engine

import android.content.Context
import android.content.res.AssetManager
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.withSettings
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Runs the bundled OrcaSlicer 2.4.2 console for Android.
 *
 * The console is packaged as a native library ("liborca_console_exec.so" in the APK's
 * arm64-v8a jniLibs, executed directly). It selects presets by name from the bundled vendor
 * profile tree, layers the app's three ini buckets over them, and emits OrcaSlicer-dialect
 * G-code.
 *
 * The console is not the upstream executable: OrcaSlicer's own binary cannot be built without
 * the GUI, so the app ships a headless driver linked against libslic3r alone. It accepts the
 * same slice inputs and prints the same "NN => stage" progress the Cura and PrusaSlicer
 * runners already parse.
 */
class OrcaEngineRunner(private val context: Context) {

    data class SliceResult(
        val artifactId: String,
        val gcodeFile: File,
        val baseGcodeFile: File,
        val logFile: File,
        val elapsedMilliseconds: Long,
        val estimatedPrintSeconds: Int?,
        val layerPreview: GcodeLayerPreview?,
        val layerEvents: List<LayerEvent>,
    )

    class SliceException(
        message: String,
        val logFile: File,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private data class Workspace(
        val id: String,
        val directory: File,
        val output: File = File(directory, "output.gcode"),
        val model: File = File(directory, "model.stl"),
        val printerConfig: File = File(directory, "orca-printer.ini"),
        val printConfig: File = File(directory, "orca-print.ini"),
        val filamentConfig: File = File(directory, "orca-filament.ini"),
    )

    private val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
    private val executable = File(nativeDirectory, ENGINE_LIBRARY_NAME)
    private val publisher = SliceArtifactPublisher(File(context.filesDir, SliceArtifactPublisher.RESULTS_DIRECTORY_NAME))
    private val datadir = File(context.filesDir, "orca/resources")

    fun isAvailable(): Boolean = executable.isFile && executable.length() > 0L

    fun status(): String = when {
        !executable.exists() -> "OrcaSlicer 2.4.2 is not packaged in this APK"
        !executable.isFile -> "OrcaSlicer package path is invalid"
        executable.length() == 0L -> "OrcaSlicer package is empty"
        !File(datadir, "profiles").isDirectory -> "OrcaSlicer profiles are not installed yet"
        else -> "OrcaSlicer 2.4.2 ready"
    }

    fun releaseArtifact(id: String) = publisher.release(id)

    /**
     * Extracts the bundled profile tree next to the cache on first use.
     *
     * Only the runtime directories ship (profiles, info, flush, printers): the GUI assets,
     * translations and the 63 MB HMS database are not read by a headless console. The console
     * itself links <datadir>/system to <datadir>/profiles, because it reads the installed
     * vendor bundles from the former and they live in the latter.
     */
    fun prepareResources(): Boolean {
        val marker = File(datadir, ".resources-version")
        if (marker.isFile && marker.readText().trim() == RESOURCES_VERSION) {
            return File(datadir, "profiles").isDirectory
        }
        if (datadir.exists()) datadir.deleteRecursively()
        datadir.mkdirs()
        copyAssetDir(context.assets, "orca/resources", datadir)
        marker.writeText(RESOURCES_VERSION)
        return File(datadir, "profiles").isDirectory
    }

    suspend fun slice(
        modelFile: File,
        printer: PrinterDefinition,
        settings: OrcaSliceSettings,
        machineSettings: SlicerSettings,
        startGcode: String,
        endGcode: String,
        onProgress: (Int) -> Unit = {},
    ): SliceResult = runInterruptible(Dispatchers.IO) {
        sliceBlocking(modelFile, printer, settings, machineSettings, startGcode, endGcode, onProgress)
    }

    private fun sliceBlocking(
        modelFile: File,
        printer: PrinterDefinition,
        settings: OrcaSliceSettings,
        machineSettings: SlicerSettings,
        startGcode: String,
        endGcode: String,
        onProgress: (Int) -> Unit,
    ): SliceResult {
        require(isAvailable()) { status() }
        require(modelFile.isFile && modelFile.length() > 0L) { "The imported STL is no longer available" }
        if (!prepareResources()) {
            throw IllegalStateException("OrcaSlicer profiles are not packaged in this APK")
        }

        val workspace = createWorkspace()
        val log = requestLog(workspace.id)
        val started = System.nanoTime()
        val effectivePrinter = effectiveMachine(printer, machineSettings)
        val printerEnvelope = PrinterEnvelope.from(printer.withSettings(machineSettings))

        try {
            writeInitialLog(log, workspace.id, modelFile, effectivePrinter, settings, startGcode, endGcode)

            modelFile.copyTo(workspace.model, overwrite = false)
            val configs = OrcaConfigWriter.write(
                directory = workspace.directory,
                settings = settings,
                printer = effectivePrinter,
                startGcode = startGcode,
                endGcode = endGcode,
            )

            val command = listOf(
                executable.absolutePath,
                "--datadir", datadir.absolutePath,
                "--printer-preset", settings.printerPreset,
                "--print-preset", settings.processPreset,
                "--filament-preset", settings.filamentPreset,
                "--printer-config", configs.printer.absolutePath,
                "--print-config", configs.print.absolutePath,
                "--filament-config", configs.filament.absolutePath,
                "-o", workspace.output.absolutePath,
                workspace.model.absolutePath,
            )
            appendLog(log, "\nOrcaSlicer command:\n" + command.joinToString(" ") + "\n")

            val processBuilder = ProcessBuilder(command)
                .directory(workspace.directory)
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = nativeDirectory.absolutePath
                    environment()["TMPDIR"] = workspace.directory.absolutePath
                    environment()["HOME"] = context.filesDir.absolutePath
                }

            val exitCode = OwnedProcessRunner.runStreaming(
                start = processBuilder::start,
                log = log,
                // Same as PrusaSlicer: the header written for this request must
                // survive the engine's own output streaming into the same file.
                appendToLog = true,
                parseProgress = { line -> progressFrom(line) },
                onProgress = onProgress,
                timeout = SLICE_TIMEOUT_MINUTES,
                unit = TimeUnit.MINUTES,
            ) ?: throw SliceException(
                "OrcaSlicer timed out after $SLICE_TIMEOUT_MINUTES minutes. Export the error log for details.",
                log,
            )

            if (exitCode != 0) {
                throw SliceException(
                    "OrcaSlicer failed with exit code " + exitCode + ". Export the error log for full details.",
                    log,
                )
            }
            validateOutput(workspace.output, log)
            throwIfInterrupted()

            val summary = GcodeSanitizer.validateAndRepair(
                file = workspace.output,
                settingsTransport = "orca-ini",
                printerEnvelope = printerEnvelope,
                dialect = GcodeDialect.ORCA,
                userGcode = listOf(startGcode, endGcode),
            )
            if (machineSettings.adaptiveMeshLevelingEnabled) {
                AdaptiveBedMeshInjector.inject(workspace.output, printerEnvelope, machineSettings.amlMarginMm, machineSettings.amlGridPoints)
            }
            val estimateSeconds = parseEstimateSeconds(workspace.output) ?: summary.estimatedSeconds
            val preview = runCatching {
                GcodeLayerPreviewParser.parse(workspace.output, GcodeDialect.ORCA)
            }.getOrNull()

            val artifact = publisher.publish(
                id = workspace.id,
                gcodeSource = workspace.output,
                baseGcodeSource = workspace.output,
                printerEnvelope = printerEnvelope,
            )
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            appendResultLog(log, artifact, summary, preview, elapsed)
            updateLatestLog(log)
            return SliceResult(
                artifactId = artifact.id,
                gcodeFile = artifact.gcodeFile,
                baseGcodeFile = artifact.baseGcodeFile,
                logFile = log,
                elapsedMilliseconds = elapsed,
                estimatedPrintSeconds = estimateSeconds,
                layerPreview = preview,
                layerEvents = emptyList(),
            )
        } catch (error: InterruptedException) {
            appendLog(log, "\n--- EnderSlicer cancellation ---\nFinished: " + Instant.now() + "\nThe OrcaSlicer request was cancelled and reaped.\n")
            updateLatestLog(log)
            throw error
        } catch (error: Throwable) {
            appendLog(log, "\n--- EnderSlicer failure ---\nFinished: " + Instant.now() + "\n" + error.stackTraceToString() + "\n")
            updateLatestLog(log)
            if (error is SliceException) throw error
            throw SliceException(error.message ?: "OrcaSlicer failed before slicing started", log, error)
        } finally {
            workspace.directory.deleteRecursively()
        }
    }

    /**
     * Parses "; estimated printing time (normal mode) = 22m 46s" from the engine's own header.
     *
     * OrcaSlicer writes the same comment as PrusaSlicer, so the fields are read the same way:
     * clamped rather than parsed as Int, because this runs after the engine already succeeded
     * and an absurd digit run must not turn a finished slice into a NumberFormatException.
     */
    private fun parseEstimateSeconds(file: File): Int? {
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("; estimated printing time")) continue
                val value = line.substringAfter('=').trim()
                return parseEnginePrintTimeEstimate(value)
            }
        }
        return null
    }

    private fun validateOutput(file: File, log: File) {
        if (!file.isFile || file.length() < MINIMUM_GCODE_BYTES) {
            throw SliceException("OrcaSlicer finished without producing a valid G-code file. Export the error log for full details.", log)
        }
        val header = file.bufferedReader().use { reader ->
            buildString {
                repeat(20) {
                    val line = reader.readLine() ?: return@repeat
                    appendLine(line)
                }
            }
        }
        if (header.isBlank() || !header.contains("OrcaSlicer")) {
            throw SliceException("The engine output did not contain an OrcaSlicer G-code header. Export the error log for full details.", log)
        }
    }

    /** Overlays the user-edited machine values (the same ones the Cura and Prusa paths honour). */
    private fun effectiveMachine(printer: PrinterDefinition, settings: SlicerSettings): PrinterDefinition =
        printer.copy(
            widthMm = settings.machineWidthMm,
            depthMm = settings.machineDepthMm,
            heightMm = settings.machineHeightMm,
            originAtCenter = settings.originAtCenter,
            heatedBed = settings.heatedBed,
            gcodeFlavor = settings.gcodeFlavor,
            nozzleSizeMm = settings.nozzleSizeMm,
            filamentDiameterMm = settings.filamentDiameterMm,
            extruders = settings.enabledExtruderCount,
        )

    private fun createWorkspace(): Workspace {
        val id = "orca-" + System.currentTimeMillis() + "-" + UUID.randomUUID()
        val root = File(context.cacheDir, "orcaengine/requests").apply {
            check(mkdirs() || isDirectory) { "Unable to create the OrcaSlicer request directory" }
        }
        val cutoff = System.currentTimeMillis() - STALE_WORKSPACE_AGE_MILLIS
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.lastModified() in 1 until cutoff }
            .forEach(File::deleteRecursively)
        cleanupStaleRequestLogs(File(context.filesDir, "logs"), "orcaengine-")
        val directory = File(root, id)
        check(directory.mkdir()) { "Unable to create an isolated OrcaSlicer workspace" }
        return Workspace(id, directory)
    }

    private fun requestLog(id: String): File = File(context.filesDir, "logs/orcaengine-" + id + ".log").apply {
        parentFile?.mkdirs()
    }

    private fun writeInitialLog(
        log: File,
        id: String,
        model: File,
        printer: PrinterDefinition,
        settings: OrcaSliceSettings,
        startGcode: String,
        endGcode: String,
    ) {
        log.writeText(
            buildString {
                appendLine("EnderSlicer OrcaSlicer diagnostic log")
                appendLine("Executable: " + executable.absolutePath)
                appendLine("Datadir: " + datadir.absolutePath)
                appendLine("Model: " + model.absolutePath)
                appendLine("Printer: " + printer.name + " (" + printer.widthMm + "x" + printer.depthMm + "mm, nozzle " + printer.nozzleSizeMm + "mm)")
                appendLine("Presets: " + settings.printerPreset + " / " + settings.processPreset + " / " + settings.filamentPreset)
                appendLine("Layer height: " + settings.layerHeightMm + "mm")
                appendLine("Walls: " + settings.wallLoops + " · Infill: " + settings.sparseInfillDensityPercent + "% " + settings.sparseInfillPattern)
                appendLine("Start G-code (" + startGcode.length + " bytes)")
                appendLine("End G-code (" + endGcode.length + " bytes)")
                appendLine("Started: " + Instant.now())
            },
        )
    }

    private fun appendResultLog(
        log: File,
        artifact: SliceArtifactPublisher.PublishedArtifact,
        summary: GcodeSanitizer.Summary,
        preview: GcodeLayerPreview?,
        elapsedMillis: Long,
    ) {
        appendLog(
            log,
            buildString {
                appendLine("\n--- OrcaSlicer result ---")
                appendLine("Artifact: " + artifact.id)
                appendLine("G-code: " + artifact.gcodeFile.absolutePath + " (" + artifact.gcodeFile.length() + " bytes)")
                appendLine("Estimated print time (s): " + summary.estimatedSeconds)
                appendLine("Layer preview: " + if (preview != null) preview.layers.size.toString() + " layers" else "unavailable")
                appendLine("Elapsed: " + elapsedMillis + "ms")
            },
        )
    }

    private fun appendLog(log: File, text: String) {
        runCatching { log.appendText(text) }
    }

    private fun updateLatestLog(log: File) = updateLatestLog(log, "latest-orcaengine.log")

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("OrcaSlicer slicing was cancelled")
    }

    private fun copyAssetDir(assets: AssetManager, from: String, destination: File) {
        AssetTreeExtractor.copyTree(assets, from, destination)
    }

    companion object {
        /** Accepts "10 => Processing triangulated mesh" style lines. */
        internal fun progressFrom(line: String): Int? {
            val trimmed = line.trimStart()
            val marker = trimmed.indexOf("=>")
            if (marker <= 0) return null
            return trimmed.substring(0, marker).trim().toIntOrNull()
        }

        const val ENGINE_LIBRARY_NAME = "liborca_console_exec.so"
        private const val RESOURCES_VERSION = "orcaslicer-2.4.2-resources-v1"
        private const val SLICE_TIMEOUT_MINUTES = 60L
        private const val STALE_WORKSPACE_AGE_MILLIS = 24L * 60 * 60 * 1000
        private const val MINIMUM_GCODE_BYTES = 256L
    }
}
