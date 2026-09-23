package com.tomppi.enderslicer.engine

import android.content.Context
import com.tomppi.enderslicer.conical.ConicalPreparations
import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.AllSettingsCatalogs
import com.tomppi.enderslicer.model.CuraMachineCatalog
import com.tomppi.enderslicer.model.ExtraSettingSpec
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import com.tomppi.enderslicer.nonplanar.NozzleCollisionAlert
import com.tomppi.enderslicer.profile.CuraEngineProfile
import com.tomppi.enderslicer.profile.CuraResolvedSettingsWriter
import com.tomppi.enderslicer.profile.CuraSliceSettingsResolver
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.smartinfill.SmartInfillSliceSnapshot
import com.tomppi.enderslicer.supportpaint.SupportPaintModifiers
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.StlParser
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

class CuraEngineRunner(private val context: Context) {
    data class SliceResult(
        val artifactId: String,
        val gcodeFile: File,
        val baseGcodeFile: File,
        val logFile: File,
        val elapsedMilliseconds: Long,
        val estimatedPrintSeconds: Int?,
        val layerPreview: GcodeLayerPreview?,
        val layerEvents: List<LayerEvent>,
        val nozzleCollisionAlert: NozzleCollisionAlert? = null,
        val collisionSweepFailure: String? = null,
    )

    data class LayerEventApplyResult(
        val artifactId: String,
        val gcodeFile: File,
        val baseGcodeFile: File,
        val estimatedPrintSeconds: Int?,
        val layerPreview: GcodeLayerPreview,
        val layerEvents: List<LayerEvent>,
    )

    class SliceException(
        message: String,
        val logFile: File,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private data class PreparedDefinitions(
        val directory: File,
        val machineDefinition: File,
        val extruderDefinition: File,
        val source: String,
    )

    private data class Workspace(
        val id: String,
        val directory: File,
        val output: File = File(directory, "output.gcode"),
        val base: File = File(directory, "base.gcode"),
        val model: File = File(directory, "model.stl"),
        val resolvedSettings: File = File(directory, "resolved-settings.json"),
    )

    private val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
    private val executable = File(nativeDirectory, ENGINE_LIBRARY_NAME)
    private val publisher = SliceArtifactPublisher(File(context.filesDir, SliceArtifactPublisher.RESULTS_DIRECTORY_NAME))

    /**
     * The bundled Cura definitions double as the "all settings" catalogue, so
     * parsing them once per process lets the command builder apply the catalogue
     * value types without stalling the UI thread that restores the persisted
     * extras. It is only touched from the slicing thread.
     */
    private val extraSettingsCatalog: List<ExtraSettingSpec> by lazy {
        AllSettingsCatalogs.cura(context.assets)
    }

    fun isAvailable(): Boolean = executable.isFile && executable.length() > 0L

    fun status(): String = when {
        !executable.exists() -> "CuraEngine 5.14.0-alpha.0 ARM64 is not packaged in this APK"
        !executable.isFile -> "CuraEngine package path is invalid"
        executable.length() == 0L -> "CuraEngine package is empty"
        else -> "CuraEngine 5.14.0-alpha.0 ARM64 ready"
    }

    fun releaseArtifact(id: String) = publisher.release(id)

    suspend fun slice(
        modelFile: File,
        printer: PrinterDefinition,
        settings: SlicerSettings,
        startGcode: String,
        endGcode: String,
        profile: CuraEngineProfile? = null,
        machineId: String = CuraMachineCatalog.DEFAULT_MACHINE_ID,
        layerEvents: List<LayerEvent> = emptyList(),
        supportPaint: SupportPaintState = SupportPaintState(),
        extraSettings: Map<String, String> = emptyMap(),
        onProgress: (Int) -> Unit = {},
    ): SliceResult = runInterruptible(Dispatchers.IO) {
        val smartInfillSnapshot = SmartInfillRuntime.snapshot()
        SmartInfillRuntime.withSnapshot(smartInfillSnapshot) {
            sliceBlocking(
                modelFile,
                printer,
                settings,
                startGcode,
                endGcode,
                profile,
                machineId,
                layerEvents,
                smartInfillSnapshot,
                supportPaint,
                extraSettings,
                onProgress,
            )
        }
    }

    private fun sliceBlocking(
        modelFile: File,
        printer: PrinterDefinition,
        settings: SlicerSettings,
        startGcode: String,
        endGcode: String,
        profile: CuraEngineProfile?,
        machineId: String,
        layerEvents: List<LayerEvent>,
        smartInfillSnapshot: SmartInfillSliceSnapshot?,
        supportPaint: SupportPaintState,
        extraSettings: Map<String, String>,
        onProgress: (Int) -> Unit,
    ): SliceResult {
        val nonPlanarRequestSnapshot = NonPlanarRuntime.snapshot()
        val conicalRequestSnapshot = ConicalRuntime.snapshot()
        val sliceSettings = when {
            nonPlanarRequestSnapshot != null -> NonPlanarPreparation.adjustSettings(settings)
            conicalRequestSnapshot != null -> ConicalPreparations.adjustSettings(settings)
            else -> settings
        }
        val sliceStartGcode = if (conicalRequestSnapshot != null) {
            ConicalPreparations.stripPrimeLines(startGcode)
        } else {
            startGcode
        }
        val workspace = createWorkspace("slice")
        val log = requestLog(workspace.id)
        val started = System.nanoTime()
        val effectiveSettings = smartInfillSnapshot?.effective(sliceSettings) ?: sliceSettings
        val printerEnvelope = PrinterEnvelope.from(printer.withSettings(effectiveSettings))
        writeInitialLog(
            log,
            workspace.id,
            modelFile,
            printer,
            effectiveSettings,
            profile,
            layerEvents,
            printerEnvelope,
            smartInfillSnapshot,
        )

        try {
            require(isAvailable()) { status() }
            require(modelFile.isFile && modelFile.length() > 0L) { "The imported STL is no longer available" }
            smartInfillSnapshot?.requireMatchesSource(modelFile)

            val resolutionProfile = profile?.let { completeDefinitionStack(it, machineId) }
            val modelTransform = if (resolutionProfile != null) {
                CuraResolvedSettingsWriter.copyResolvedSourceSnapshot(
                    stagedDisplayedFile = modelFile,
                    destination = workspace.model,
                    copyFile = { source, destination ->
                        copyStable(source, destination, "The original model changed while it was being staged")
                    },
                )
            } else {
                null
            }
            if (modelTransform == null) {
                copyStable(modelFile, workspace.model, "The model changed while it was being staged")
            }
            val smartInfillModifiers = smartInfillSnapshot
                ?.stageModifiers(workspace.directory, modelFile)
                .orEmpty()
            throwIfInterrupted()
            val adaptiveWallModifiers = if (effectiveSettings.thicknessAdaptiveWallsEnabled) {
                ThicknessAdaptiveWalls.generate(
                    modelFile = workspace.model,
                    settings = effectiveSettings,
                    destination = workspace.directory,
                    transform = modelTransform,
                )
            } else {
                emptyList()
            }
            throwIfInterrupted()
            val supportPaintModifiers = if (supportPaint.isEmpty) {
                emptyList()
            } else {
                SupportPaintModifiers.generate(
                    mesh = StlParser.parse(workspace.model, workspace.model.name, MeshTriangleLimits.current()),
                    paint = supportPaint,
                    destination = workspace.directory,
                    thicknessMm = effectiveSettings.lineWidthMm * 2.0,
                    transform = modelTransform,
                )
            }
            throwIfInterrupted()

            val definitions = prepareDefinitions(workspace.directory, log, resolutionProfile, machineId)
            throwIfInterrupted()

            var resolved: CuraSliceSettingsResolver.Result? = null
            val command = if (resolutionProfile != null) {
                resolved = CuraSliceSettingsResolver.resolve(
                    resolutionProfile,
                    printer,
                    sliceSettings,
                    sliceStartGcode,
                    endGcode,
                )
                CuraResolvedSettingsWriter.write(
                    destination = workspace.resolvedSettings,
                    modelFileName = workspace.model.name,
                    resolved = resolved,
                    modelTransform = modelTransform,
                    smartInfillModifiers = smartInfillModifiers,
                    adaptiveWallModifiers = adaptiveWallModifiers,
                    supportPaintModifiers = supportPaintModifiers,
                )
                CuraEngineCommand.buildResolved(
                    executable.absolutePath,
                    definitions.directory.absolutePath,
                    workspace.resolvedSettings.absolutePath,
                    workspace.output.absolutePath,
                    extraSettings = extraSettings,
                    catalog = extraSettingsCatalog,
                )
            } else {
                CuraEngineCommand.build(
                    executablePath = executable.absolutePath,
                    definitionsDirectory = definitions.directory.absolutePath,
                    machineDefinitionPath = definitions.machineDefinition.absolutePath,
                    extruderDefinitionPath = definitions.extruderDefinition.absolutePath,
                    modelPath = workspace.model.absolutePath,
                    outputPath = workspace.output.absolutePath,
                    printer = printer,
                    settings = sliceSettings,
                    startGcode = sliceStartGcode,
                    endGcode = endGcode,
                    profile = null,
                    smartInfillModifiers = smartInfillModifiers,
                    adaptiveWallModifiers = adaptiveWallModifiers,
                    supportPaintModifiers = supportPaintModifiers,
                    extraSettings = extraSettings,
                    catalog = extraSettingsCatalog,
                )
            }
            appendCommandLog(log, definitions, resolved, workspace.resolvedSettings, command)

            val processBuilder = java.lang.ProcessBuilder(command)
                .directory(workspace.directory)
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = nativeDirectory.absolutePath
                    environment()["TMPDIR"] = workspace.directory.absolutePath
                    environment()["HOME"] = context.filesDir.absolutePath
                    environment()["CURAENGINE_LOG_LEVEL"] = "info"
                }

            val exitCode = try {
                OwnedProcessRunner.runStreaming(
                    start = processBuilder::start,
                    log = log,
                    appendToLog = true,
                    parseProgress = { line -> curaProgressFrom(line) },
                    onProgress = onProgress,
                    timeout = SLICE_TIMEOUT_MINUTES,
                    unit = TimeUnit.MINUTES,
                ) ?: throw OwnedProcessRunner.ProcessTimeoutException()
            } catch (error: OwnedProcessRunner.ProcessTimeoutException) {
                throw SliceException(
                    "CuraEngine timed out after $SLICE_TIMEOUT_MINUTES minutes. Export the error log for details.",
                    log,
                    error,
                )
            }
            appendLog(log, "\n--- Process result ---\nExit code: $exitCode\n")
            if (exitCode != 0) {
                throw SliceException("CuraEngine failed with exit code $exitCode. Export the error log for full details.", log)
            }
            validateEngineOutput(workspace.output, log)
            throwIfInterrupted()

            val transport = if (resolved != null) "resolved-json" else "fallback-command"
            // The engine writes the resolved custom end script, so the post-processor
            // has to search for that script, not the raw template the caller passed.
            val machineEndGcode = CuraEngineCommand.machineEndGcodeFor(effectiveSettings, endGcode)
            val processed = CuraEnginePostProcessor.process(
                outputFile = workspace.output,
                baseGcodeFile = workspace.base,
                settingsTransport = transport,
                layerEvents = layerEvents,
                printerEnvelope = printerEnvelope,
                // CuraEngine writes this script before its ";End of Gcode" comment, so
                // the event processor needs to know where the script actually starts.
                machineEndGcode = machineEndGcode,
                amlEnabled = effectiveSettings.adaptiveMeshLevelingEnabled,
                amlMarginMm = effectiveSettings.amlMarginMm,
                amlGridPoints = effectiveSettings.amlGridPoints,
            )
            throwIfInterrupted()

            val currentNonPlanarSnapshot = NonPlanarRuntime.snapshot()
            val currentConicalSnapshot = ConicalRuntime.snapshot()
            if (
                nonPlanarRequestSnapshot?.generation != currentNonPlanarSnapshot?.generation ||
                conicalRequestSnapshot?.generation != currentConicalSnapshot?.generation
            ) {
                throw SliceException(
                    "Non-planar settings changed while slicing was in progress; " +
                        "the result was discarded so no stale G-code is published. Slice again.",
                    log,
                )
            }

            val artifact = publisher.publish(
                id = workspace.id,
                gcodeSource = workspace.output,
                baseGcodeSource = workspace.base,
                printerEnvelope = printerEnvelope,
            )
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            appendResultLog(log, artifact, processed, elapsed)
            updateLatestLog(log)
            return SliceResult(
                artifactId = artifact.id,
                gcodeFile = artifact.gcodeFile,
                baseGcodeFile = artifact.baseGcodeFile,
                logFile = log,
                elapsedMilliseconds = elapsed,
                estimatedPrintSeconds = processed.summary.estimatedSeconds,
                layerPreview = processed.layerPreview,
                layerEvents = processed.layerEvents,
                nozzleCollisionAlert = processed.nozzleCollisionAlert,
            collisionSweepFailure = processed.collisionSweepFailure,
            )
        } catch (error: InterruptedException) {
            appendLog(log, "\n--- TrioSlicer cancellation ---\nFinished: ${Instant.now()}\nThe CuraEngine request was cancelled and reaped.\n")
            updateLatestLog(log)
            throw error
        } catch (error: Throwable) {
            appendLog(log, "\n--- TrioSlicer failure ---\nFinished: ${Instant.now()}\n${error.stackTraceToString()}\n")
            updateLatestLog(log)
            if (error is SliceException) throw error
            throw SliceException(error.message ?: "CuraEngine failed before slicing started", log, error)
        } finally {
            workspace.directory.deleteRecursively()
        }
    }

    fun applyLayerEvents(
        baseGcodeFile: File,
        events: List<LayerEvent>,
        machineEndGcode: String? = null,
    ): LayerEventApplyResult {
        require(baseGcodeFile.isFile && baseGcodeFile.length() > 0L) {
            "The original sliced G-code is unavailable; slice again"
        }
        val printerEnvelope = SliceArtifactPublisher.readPrinterEnvelope(baseGcodeFile)
        val workspace = createWorkspace("events")
        try {
            copyStable(baseGcodeFile, workspace.base, "The original sliced G-code changed while it was being read")
            val preview = GcodeLayerPreviewParser.parse(workspace.base)
            val layers = preview.layers.mapTo(hashSetOf()) { it.number }
            val validEvents = LayerEventOrdering.normalize(
                events.filter { it.layerNumber in layers },
            )
            val transport = workspace.base.bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith(";ENDERSLICER_SETTINGS_TRANSPORT:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.removeSuffix("+layer-events")
            } ?: "resolved-json"

            if (validEvents.isEmpty()) {
                workspace.base.copyTo(workspace.output)
            } else {
                GcodeLayerEventProcessor.materialize(
                    workspace.base,
                    workspace.output,
                    validEvents,
                    CalibrationFirmwareEncoder.fromFlavor(printerEnvelope.gcodeFlavor),
                    machineEndGcode = machineEndGcode,
                )
            }
            val summary = GcodeSanitizer.validateAndRepair(
                file = workspace.output,
                settingsTransport = if (validEvents.isEmpty()) transport else "$transport+layer-events",
                printerEnvelope = printerEnvelope,
            )
            val resultPreview = GcodeLayerPreviewParser.parse(workspace.output)
            val artifact = publisher.publish(
                id = workspace.id,
                gcodeSource = workspace.output,
                baseGcodeSource = workspace.base,
                printerEnvelope = printerEnvelope,
            )
            return LayerEventApplyResult(
                artifact.id,
                artifact.gcodeFile,
                artifact.baseGcodeFile,
                summary.estimatedSeconds,
                resultPreview,
                validEvents,
            )
        } finally {
            workspace.directory.deleteRecursively()
        }
    }

    private fun validateEngineOutput(file: File, log: File) {
        if (!file.isFile || file.length() < MINIMUM_GCODE_BYTES) {
            throw SliceException("CuraEngine finished without producing a valid G-code file. Export the error log for details.", log)
        }
        val header = file.bufferedReader().use { reader ->
            buildString {
                repeat(20) {
                    val line = reader.readLine() ?: return@repeat
                    appendLine(line)
                }
            }
        }
        if (!header.contains(";FLAVOR:") && !header.contains(";Generated with Cura")) {
            throw SliceException("The engine output did not contain a Cura G-code header. Export the error log for details.", log)
        }
    }

    private fun createWorkspace(prefix: String): Workspace {
        val id = "$prefix-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val root = File(context.cacheDir, "curaengine/requests").apply {
            check(mkdirs() || isDirectory) { "Unable to create the CuraEngine request directory" }
        }
        cleanupOldWorkspaces(root)
        cleanupStaleRequestLogs(File(context.filesDir, "logs"), "curaengine-")
        val directory = File(root, id)
        check(directory.mkdir()) { "Unable to create an isolated CuraEngine workspace" }
        return Workspace(id, directory)
    }

    private fun cleanupOldWorkspaces(root: File) {
        val cutoff = System.currentTimeMillis() - STALE_WORKSPACE_AGE_MILLIS
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.lastModified() in 1 until cutoff }
            .forEach(File::deleteRecursively)
    }

    private fun requestLog(id: String): File = File(context.filesDir, "logs/curaengine-$id.log").apply {
        parentFile?.mkdirs()
    }

    private fun writeInitialLog(
        log: File,
        id: String,
        model: File,
        printer: PrinterDefinition,
        settings: SlicerSettings,
        profile: CuraEngineProfile?,
        layerEvents: List<LayerEvent>,
        printerEnvelope: PrinterEnvelope,
        smartInfillSnapshot: SmartInfillSliceSnapshot?,
    ) {
        log.writeText(
            buildString {
                appendLine("TrioSlicer CuraEngine diagnostic log")
                appendLine("Request: $id")
                appendLine("Started: ${Instant.now()}")
                appendLine("Engine: ${executable.absolutePath}")
                appendLine("Model: ${model.name} (${model.length()} bytes)")
                appendLine("Printer: ${printer.name}")
                appendLine("Build volume: ${printerEnvelope.widthMm} x ${printerEnvelope.depthMm} x ${printerEnvelope.heightMm} mm")
                appendLine("Build plate: ${printerEnvelope.buildPlateShape}, origin at center: ${printerEnvelope.originAtCenter}")
                appendLine("Nozzle: ${printer.nozzleSizeMm} mm")
                appendLine("Layer height: ${settings.layerHeightMm} mm")
                appendLine("Smart Infill package/generation: ${smartInfillSnapshot?.packageId ?: "none"}/${smartInfillSnapshot?.generation ?: 0L}")
                appendLine("Layer events: ${layerEvents.size}")
                appendLine("Imported Cura values: ${profile?.globalValues?.size ?: 0}/${profile?.extruderValues?.size ?: 0}")
                appendLine()
            },
        )
    }

    private fun appendCommandLog(
        log: File,
        definitions: PreparedDefinitions,
        resolved: CuraSliceSettingsResolver.Result?,
        resolvedSettings: File,
        command: List<String>,
    ) {
        appendLog(
            log,
            buildString {
                appendLine("Definition source: ${definitions.source}")
                appendLine("Machine definition: ${definitions.machineDefinition.name}")
                appendLine("Extruder definition: ${definitions.extruderDefinition.name}")
                if (resolved != null) {
                    appendLine("Settings transport: CuraEngine resolved JSON (-r)")
                    appendLine("Resolved expressions/passes: ${resolved.expressionCount}/${resolved.passes}")
                    appendLine("Resolved global/extruder/model settings: ${resolved.globalValues.size}/${resolved.extruderValues.size}/${resolved.modelValues.size}")
                    appendLine("Resolved Smart Infill densities: ${resolved.smartInfillModelValues.keys.sorted().joinToString()}")
                    appendLine("Resolved settings JSON: ${resolvedSettings.length()} bytes")
                } else {
                    appendLine("Settings transport: standalone fallback command-line values")
                }
                appendLine()
                appendLine("--- Command ---")
                command.forEachIndexed { index, argument -> appendLine("[$index] $argument")
                }
                appendLine()
                appendLine("--- CuraEngine output ---")
            },
        )
    }

    private fun appendResultLog(
        log: File,
        artifact: SliceArtifactPublisher.PublishedArtifact,
        result: CuraEnginePostProcessor.Result,
        elapsed: Long,
    ) {
        val summary = result.summary
        appendLog(
            log,
            buildString {
                appendLine("--- Validated G-code ---")
                appendLine("Layers: ${summary.layerCount}")
                appendLine("Estimated seconds: ${summary.estimatedSeconds ?: "unknown"}")
                appendLine("Model/total filament mm: ${summary.filamentMillimeters}/${summary.totalFilamentMillimeters}")
                appendLine("Extrusion bounds: X ${summary.minX}..${summary.maxX}, Y ${summary.minY}..${summary.maxY}, Z ${summary.minZ}..${summary.maxZ}")
                result.layerPreview?.let {
                    appendLine("Layer preview layers/segments: ${it.layers.size}/${it.totalSegmentCount}")
                    appendLine("Layer preview truncated: ${it.truncated}")
                } ?: appendLine("Layer preview unavailable: ${result.previewFailure?.message ?: "unknown parse error"}")
                appendLine("Applied layer events: ${result.layerEvents.size}")
                appendLine("Zero-event fast path: ${result.usedZeroEventFastPath}")
                appendLine("Published artifact: ${artifact.id}")
                appendLine("Published G-code: ${artifact.gcodeFile.absolutePath} (${artifact.gcodeFile.length()} bytes)")
                appendLine("Elapsed milliseconds: $elapsed")
                appendLine("Completed: ${Instant.now()}")
                appendLine("Result: success")
            },
        )
    }

    private fun copyStable(source: File, destination: File, message: String) {
        val length = source.length()
        val modified = source.lastModified()
        source.copyTo(destination, overwrite = false)
        check(
            source.isFile && source.length() == length && source.lastModified() == modified &&
                destination.isFile && destination.length() == length,
        ) { message }
    }

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("CuraEngine request was cancelled")
    }

    private fun updateLatestLog(log: File) = updateLatestLog(log, "curaengine-last.log")

    private fun completeDefinitionStack(profile: CuraEngineProfile, machineId: String): CuraEngineProfile {
        if (profile.usesProjectDefinitions) return profile
        val closure = closureFor(machineId)
        val combined = linkedMapOf<String, String>().apply {
            putAll(loadBundledDefinitions(closure))
            putAll(profile.definitionFiles)
        }
        return profile.copy(
            definitionFiles = combined,
            machineDefinitionFileName = profile.machineDefinitionFileName
                ?.takeIf(combined::containsKey) ?: closure.machineFile,
            extruderDefinitionFileName = profile.extruderDefinitionFileName
                ?.takeIf(combined::containsKey) ?: closure.extruderFile,
        )
    }

    /**
     * The definition stack of one catalogue machine, or the Ender 3 chain the app shipped with
     * when the stored machine is no longer in the tree: a stale choice must not fail a slice.
     */
    private fun closureFor(machineId: String): CuraMachineCatalog.Closure =
        CuraMachineCatalog.closure(context.assets, machineId)
            ?: requireNotNull(CuraMachineCatalog.closure(context.assets, BUNDLED_MACHINE_ID)) {
                "The bundled Cura definition tree has no " + BUNDLED_MACHINE_ID + " chain"
            }

    private fun loadBundledDefinitions(closure: CuraMachineCatalog.Closure): Map<String, String> =
        closure.files.associateWith { name ->
            context.assets.open("cura/definitions/$name").bufferedReader().use { it.readText() }
        }

    private fun prepareDefinitions(
        workDirectory: File,
        log: File,
        profile: CuraEngineProfile?,
        machineId: String,
    ): PreparedDefinitions {
        val destination = File(workDirectory, "definitions").apply {
            deleteRecursively()
            check(mkdirs()) { "Unable to create the Cura definition directory" }
        }
        if (profile?.usesProjectDefinitions == true) {
            profile.definitionFiles.forEach { (rawName, content) ->
                File(destination, safeDefinitionName(rawName)).writeText(content)
            }
            val machine = File(destination, safeDefinitionName(requireNotNull(profile.machineDefinitionFileName)))
            val extruder = File(destination, safeDefinitionName(requireNotNull(profile.extruderDefinitionFileName)))
            check(machine.isFile && machine.length() > 0L) { "Imported machine definition is missing: ${machine.name}" }
            check(extruder.isFile && extruder.length() > 0L) { "Imported extruder definition is missing: ${extruder.name}" }
            val source = if (profile.definitionFiles.keys.containsAll(BUNDLED_DEFINITION_FILES)) {
                "Cura baseline completed with pinned definitions"
            } else {
                "imported Cura project definitions"
            }
            logDefinitions(log, source, destination)
            return PreparedDefinitions(destination, machine, extruder, source)
        }
        val closure = closureFor(machineId)
        closure.files.forEach { name ->
            val target = File(destination, name)
            context.assets.open("cura/definitions/$name").use { input ->
                target.outputStream().buffered().use(input::copyTo)
            }
            check(target.length() > 0L) { "Bundled Cura definition is empty: $name" }
        }
        val source = if (closure.machineFile == BUNDLED_MACHINE_DEFINITION) {
            "bundled Cura 5.14.0-alpha.0 standalone fallback"
        } else {
            "bundled Cura 5.14.0-alpha.0 machine " + closure.machineFile
        }
        logDefinitions(log, source, destination)
        return PreparedDefinitions(
            destination,
            File(destination, closure.machineFile),
            File(destination, closure.extruderFile),
            source,
        )
    }

    private fun logDefinitions(log: File, heading: String, directory: File) {
        appendLog(
            log,
            buildString {
                appendLine("--- $heading ---")
                directory.listFiles().orEmpty().sortedBy(File::getName)
                    .forEach { appendLine("${it.name} (${it.length()} bytes)") }
                appendLine()
            },
        )
    }

    private fun safeDefinitionName(rawName: String): String {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\')
        require(name.endsWith(".def.json")) { "Invalid Cura definition filename: $rawName" }
        require(name.matches(Regex("[A-Za-z0-9._ #+%()-]+"))) { "Unsafe Cura definition filename: $rawName" }
        return name
    }

    private fun appendLog(file: File, text: String) {
        runCatching { file.appendText(text) }
    }

    private companion object {
        const val ENGINE_LIBRARY_NAME = "libcuraengine_exec.so"

        const val SLICE_TIMEOUT_MINUTES = 30L
        const val MINIMUM_GCODE_BYTES = 128L
        const val STALE_WORKSPACE_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        const val BUNDLED_MACHINE_ID = CuraMachineCatalog.DEFAULT_MACHINE_ID
        const val BUNDLED_MACHINE_DEFINITION = BUNDLED_MACHINE_ID + ".def.json"
        const val BUNDLED_EXTRUDER_DEFINITION = "creality_base_extruder_0.def.json"
        val BUNDLED_DEFINITION_FILES = listOf(
            "fdmprinter.def.json",
            "fdmextruder.def.json",
            "creality_base.def.json",
            BUNDLED_EXTRUDER_DEFINITION,
            BUNDLED_MACHINE_DEFINITION,
        )
    }
}
/**
 * Accepts the engine's own progress line, `Slice progress: 42%`, as printed under
 * '-p'. Anything else in the engine log - including its `Progress: inset+skin
 * accomplished in 12.3s` stage lines - parses to null and is only logged.
 */
internal fun curaProgressFrom(line: String): Int? {
    val start = line.indexOf(CURA_PROGRESS_MARKER)
    if (start < 0) return null
    return line.substring(start + CURA_PROGRESS_MARKER.length)
        .trim()
        .removeSuffix("%")
        .trim()
        .toIntOrNull()
        ?.coerceIn(0, 100)
}

/** What CuraEngine prints under '-p': one line per whole percent. */
internal const val CURA_PROGRESS_MARKER = "Slice progress: "

