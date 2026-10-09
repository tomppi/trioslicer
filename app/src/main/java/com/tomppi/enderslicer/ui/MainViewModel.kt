package com.tomppi.enderslicer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tomppi.enderslicer.BuildConfig
import com.tomppi.enderslicer.annotation.AnnotationAnchor
import com.tomppi.enderslicer.annotation.AnnotationCodec
import com.tomppi.enderslicer.annotation.AnnotationGesture
import com.tomppi.enderslicer.annotation.AnnotationKind
import com.tomppi.enderslicer.annotation.AnnotationState
import com.tomppi.enderslicer.annotation.SegmentEnd
import com.tomppi.enderslicer.annotation.WorkPlane
import com.tomppi.enderslicer.conical.ConicalRuntime
import com.tomppi.enderslicer.data.AppStateStore
import com.tomppi.enderslicer.data.BuiltInGcode
import com.tomppi.enderslicer.data.OrcaSliceSettingsJson
import com.tomppi.enderslicer.data.PendingDocumentExportStore
import com.tomppi.enderslicer.data.PlatePreferences
import com.tomppi.enderslicer.data.PlatePreferencesStore
import com.tomppi.enderslicer.data.PrinterDefinitionLoader
import com.tomppi.enderslicer.data.PrusaSliceSettingsJson
import com.tomppi.enderslicer.data.SlicerSettingsJson
import com.tomppi.enderslicer.data.WorkspaceStateStore
import com.tomppi.enderslicer.engine.BlenderModelHandoff
import com.tomppi.enderslicer.engine.CuraEngineRunner
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.engine.LayerEvent
import com.tomppi.enderslicer.engine.LayerEventSource
import com.tomppi.enderslicer.engine.SequentialPrintCheck
import com.tomppi.enderslicer.engine.SliceModel
import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.OcctEngineRunner
import com.tomppi.enderslicer.engine.OrcaEngineRunner
import com.tomppi.enderslicer.engine.PrinterEnvelope
import com.tomppi.enderslicer.engine.PrusaEngineRunner
import com.tomppi.enderslicer.engine.SliceArtifactPublisher
import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import com.tomppi.enderslicer.model.AllSettingsCatalogs
import com.tomppi.enderslicer.model.CuraMachineCatalog
import com.tomppi.enderslicer.model.ExtraSettingSpec
import com.tomppi.enderslicer.model.ExtraSettingValidation
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.model.OrcaBasePreset
import com.tomppi.enderslicer.model.PlacementHistory
import com.tomppi.enderslicer.model.PlateArranger
import com.tomppi.enderslicer.model.PlateFootprint
import com.tomppi.enderslicer.model.OrcaPresetCatalog
import com.tomppi.enderslicer.model.OrcaProfileImporter
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrusaConfigImporter
import com.tomppi.enderslicer.model.PrusaPresetCatalog
import com.tomppi.enderslicer.model.PrusaPresetOption
import com.tomppi.enderslicer.model.PrusaPresetRepository
import com.tomppi.enderslicer.model.PrusaPresetSelection
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.model.withSettings
import com.tomppi.enderslicer.modelling.EnginePreviewClient
import com.tomppi.enderslicer.nativebridge.BlenderEngine
import com.tomppi.enderslicer.nativebridge.BlenderEngineService
import com.tomppi.enderslicer.nativebridge.CadEngine
import com.tomppi.enderslicer.nonplanar.NonPlanarRuntime
import com.tomppi.enderslicer.nonplanar.NozzleCollisionAlert
import com.tomppi.enderslicer.nonplanar.SmartOverhangStrategy
import com.tomppi.enderslicer.profile.CuraImportedSettingsResolver
import com.tomppi.enderslicer.profile.CuraProfileParser
import com.tomppi.enderslicer.profile.CuraProjectAudit
import com.tomppi.enderslicer.profile.CuraProjectParser
import com.tomppi.enderslicer.profile.CuraProjectScene
import com.tomppi.enderslicer.profile.CuraProjectSceneParser
import com.tomppi.enderslicer.profile.ImportedCuraConfig
import com.tomppi.enderslicer.smartinfill.SmartInfillOverlay
import com.tomppi.enderslicer.smartinfill.SmartInfillRuntime
import com.tomppi.enderslicer.storage.readPickedBytes
import com.tomppi.enderslicer.storage.readPickedText
import com.tomppi.enderslicer.storage.readPickedTextTruncated
import com.tomppi.enderslicer.supportpaint.SupportPaintBrush
import com.tomppi.enderslicer.supportpaint.SupportPaintMode
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import java.util.Locale
import kotlin.math.sqrt
import com.tomppi.enderslicer.viewer.AnnotationOverlayBuilder
import com.tomppi.enderslicer.viewer.MeshPicker
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.PaintedMeshWriter
import com.tomppi.enderslicer.viewer.PlateThreeMfWriter
import com.tomppi.enderslicer.viewer.StlMeshWriter
import com.tomppi.enderslicer.viewer.StlParser
import com.tomppi.enderslicer.viewer.ThreeMfModelParser
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val CONFIG_SNAPSHOT_FORMAT = "enderslicer-config-snapshot"
private const val CONFIG_SNAPSHOT_VERSION = 1
private const val PAINT_PERSIST_DEBOUNCE_MILLIS = 400L
private const val EXTRAS_PERSIST_DEBOUNCE_MILLIS = 250L

// A PrusaSlicer config is a few hundred kilobytes of INI text; the cap only
// exists so a mistyped import cannot read a gigabyte into a String.
private const val MAX_PRUSA_CONFIG_BYTES = 8L * 1024L * 1024L

/** Same cap, and the same reason, for a TrioSlicer configuration snapshot. */
private const val MAX_CONFIG_SNAPSHOT_BYTES = 8L * 1024L * 1024L

/** The engine's request log is text; the export stops here rather than reading a runaway log whole. */
private const val MAX_DIAGNOSTIC_LOG_BYTES = 8L * 1024L * 1024L

/** Engine-agnostic slice result shared by the Cura and Prusa runners. */
private data class EngineSliceOutcome(
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
    /** Verified on the publishing thread; see [MainUiState.gcodeComplete]. */
    val gcodeComplete: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private data class PendingImport(
        val config: ImportedCuraConfig,
        val stagedFile: File,
        val kind: String,
        val displayName: String,
        val scene: CuraProjectScene? = null,
    )

    private data class SnapshotImport(
        val settings: SlicerSettings,
        val startGcode: String,
        val endGcode: String,
        val sourceName: String,
        val prusaSettings: PrusaSliceSettings?,
        val prusaStartGcode: String?,
        val prusaEndGcode: String?,
        val extraPrusaSettings: Map<String, String>?,
        val extraCuraSettings: Map<String, String>?,
        val orcaSettings: OrcaSliceSettings?,
        val extraOrcaSettings: Map<String, String>?,
    )
    private data class RestoredImport(
        val config: ImportedCuraConfig?,
        val settings: SlicerSettings,
        val scene: CuraProjectScene?,
        val workspace: RestoredWorkspace?,
        val startGcode: String,
        val endGcode: String,
        val profileName: String,
        val profileSource: String,
        val baselineSettings: SlicerSettings?,
        /** Display name of an export a previous process interrupted, if there was one. */
        val interruptedExportName: String?,
        /** Display name of a workspace whose configuration fingerprint no longer matches. */
        val skippedWorkspaceName: String?,
        /**
         * True when the saved plate could not be read at all.
         *
         * A descriptor naming a file that is gone fails validation, and the load path used to
         * swallow that and carry on: the whole plate came back empty with nothing said.
         */
        val workspaceUnreadable: Boolean = false,
    )

    /** One model parsed back out of the saved workspace. */
    private data class RestoredPlateObject(
        val source: StlMesh,
        val transformed: StlMesh,
        val placement: ModelPlacement,
        val supportPaint: SupportPaintState,
        val path: String,
        val name: String,
    ) {
        fun toPlateObject(): PlateObject = PlateObject(
            id = PlateObject.newId(),
            name = name,
            sourceMesh = source,
            mesh = transformed,
            sourcePath = path,
            placement = placement,
            supportPaint = supportPaint.clippedToMesh(transformed.triangleCount),
        )
    }

    private data class RestoredWorkspace(
        val snapshot: WorkspaceStateStore.Snapshot,
        /** Every model the descriptor held; never empty - a failed parse skips the workspace. */
        val objects: List<RestoredPlateObject>,
    )

    private data class PreparedModelImport(
        val source: StlMesh,
        val transformed: StlMesh,
        val modelFile: File,
        val placement: ModelPlacement,
        val automaticImportedPlacement: Boolean,
        val mismatchWarning: String?,
    )

    private val app = application
    private val printer = PrinterDefinitionLoader.loadModifiedEnder3V2(app.assets)
    private val engine = CuraEngineRunner(app)
    private val prusaEngine = PrusaEngineRunner(app)
    private val orcaEngine = OrcaEngineRunner(app)
    private val occtEngine = OcctEngineRunner(app)
    private val engineStore = SlicerEngineStore(app)
    private val platePreferencesStore = PlatePreferencesStore(app)
    private val activeEngine: SlicerEngine get() = engineStore.load()

    /** The engine the UI is driving. The named preset library is scoped to it. */
    internal val currentEngine: SlicerEngine get() = activeEngine
    private val stateStore = AppStateStore(app)
    private val workspaceStore = WorkspaceStateStore(app)
    private val pendingExportStore = PendingDocumentExportStore(app)
    // The default for a profile that declares no dialect is Marlin's, which is what this
    // app shipped before there was a Klipper host in it; the choice moves to the Klipper
    // text the moment a profile says it is for Klipper.
    private val initialStartGcode = BuiltInGcode.defaultStartGcode
    private val initialEndGcode = BuiltInGcode.defaultEndGcode
    private var importedSettingsBaseline: SlicerSettings? = null

    /**
     * The selected object's untransformed mesh.
     *
     * Placements are computed from the source mesh, not from the transformed one: re-applying a
     * placement to an already-placed mesh would compound it. It is derived rather than stored
     * because the plate holds several objects now and the selected one is the one being edited.
     */
    private val sourceMesh: StlMesh? get() = _uiState.value.selectedModel?.sourceMesh

    private var importedScene: CuraProjectScene? = null
    private var settingsPersistenceJob: Job? = null
    private var prusaSettingsPersistenceJob: Job? = null
    private var orcaSettingsPersistenceJob: Job? = null
    private var paintPersistenceJob: Job? = null
    private var extraSettingsPersistenceJob: Job? = null
    private val workspaceMutationGeneration = AtomicLong(0L)
    private val layerEventSequence = AtomicLong(0L)
    private val deferredRestoreActions = ArrayDeque<() -> Unit>()
    /** Guards [beginOperation]'s check-then-set against the Blender IO callback. */
    private val operationLock = Any()
    @Volatile private var restoringPersistedState = true

    private val _uiState = MutableStateFlow(
        MainUiState(
            printer = printer,
            startGcode = initialStartGcode,
            endGcode = initialEndGcode,
            engineStatus = activeEngineStatus(),
            engineAvailable = activeEngineAvailable(),
            prusaSettings = stateStore.restorePrusaSettings(),
            prusaPreset = stateStore.restorePrusaPreset(),
            extraPrusaSettings = stateStore.restoreExtraPrusaSettings(),
            orcaSettings = stateStore.restoreOrcaSettings(),
            extraOrcaSettings = stateStore.restoreExtraOrcaSettings(),
            prusaStartGcode = stateStore.restorePrusaGcode().first,
            prusaEndGcode = stateStore.restorePrusaGcode().second,
            extraCuraSettings = stateStore.restoreExtraCuraSettings(),
            curaMachineId = stateStore.restoreCuraMachine(),
            statusMessage = "Restoring saved configuration…",
            isBusy = true,
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private fun activeEngineStatus(): String = when (activeEngine) {
        SlicerEngine.CURA -> engine.status()
        SlicerEngine.PRUSA -> prusaEngine.status()
        SlicerEngine.ORCA -> orcaEngine.status()
    }

    private fun activeEngineAvailable(): Boolean = when (activeEngine) {
        SlicerEngine.CURA -> engine.isAvailable()
        SlicerEngine.PRUSA -> prusaEngine.isAvailable()
        SlicerEngine.ORCA -> orcaEngine.isAvailable()
    }

    /**
     * Called by the UI after the user switches the engine. The previous engine's slice
     * result no longer matches the engine that would export it, so it is discarded the
     * way a machine or settings change discards it.
     */
    fun onEngineChanged() {
        _uiState.update {
            it.withoutPublishedSlice("Engine changed; slice again to export G-code").copy(
                engineStatus = activeEngineStatus(),
                engineAvailable = activeEngineAvailable(),
            )
        }
    }

    init {
        _uiState.update { it.copy(platePreferences = platePreferencesStore.load()) }
        restorePersistedState()
        BlenderEngine.onStlExported = { file -> importBlenderStl(file) }
        // The same contract for the CAD engine. It writes STEP and STL to
        // <filesDir>/cad/exports/; the STL is the one the slicer can take, and without
        // this the part the agent built stopped at the exports directory.
        CadEngine.onExport = { file ->
            // STL only. onExport fires for every model format the engine writes, and STEP
            // and BREP are not meshes - handing one to the STL parser fails, and it fails
            // on the first open of the menu because every file already in the directory
            // counts as new. STEP is the format to *edit*; STL is the one to print.
            if (file.extension.equals("stl", ignoreCase = true)) importCadStl(file)
        }
        // An export the engine claimed while the app was busy is taken the moment
        // whatever was running finishes, wherever in this class it finished.
        viewModelScope.launch {
            _uiState.map { it.isBusy }.distinctUntilChanged().collect { busy ->
                if (!busy) drainPendingEngineImport()
            }
        }
        // The picker caches the mesh it last built a hierarchy for, and that cache
        // holds off-heap vertex and BVH arrays. Watching the displayed mesh here -
        // rather than at each place that swaps it - covers every import, restore
        // and clear with one trigger, and only fires when the mesh actually changes.
        viewModelScope.launch {
            _uiState.map { it.mesh }.distinctUntilChanged().collect { MeshPicker.invalidate() }
        }
    }

    /**
     * The Blender engine deliberately outlives the UI. Tearing down the UI used
     * to shut the engine down and stop its keeper service, which left the two in
     * states they could not recover from together: the engine kept running (it
     * lives on a detached thread inside the process) while the keeper - the
     * foreground service that pins the process so Android cannot cull it - was
     * gone, and the notification with it. Now both survive, so the engine, its
     * MCP socket and the loaded scene are still there when the app comes back.
     * Use [stopBlenderEngine] to end it deliberately.
     */
    override fun onCleared() {
        // The engine outlives this view model on purpose, but this view model's
        // listener must not: it belongs to a scope that is cancelled here, so an
        // export arriving after the UI went away was claimed and then dropped. With
        // no listener the engine queues it, and the next view model's setter replays
        // the newest one.
        if (BlenderEngine.onStlExported != null) BlenderEngine.onStlExported = null
        if (CadEngine.onExport != null) CadEngine.onExport = null
        super.onCleared()
    }

    /**
     * Boots the Blender engine and its keeper service.
     *
     * Nothing starts the engine at app launch any more, so every path that
     * needs it asks here first. Both calls are idempotent.
     */
    fun startBlenderEngine() {
        BlenderEngine.ensureStarted(app)
        BlenderEngineService.start(app)
    }

    /**
     * Boots the CAD engine, from the CAD menu, the way [startBlenderEngine] boots Blender's.
     *
     * Off the main thread, and this one is not idempotent-cheap the way the Blender call is:
     * it extracts the engine script, writes its token and then waits up to a minute for the
     * engine to report a listening port, and the engine imports build123d at startup. On the
     * main thread that is a frozen menu for as long as it takes.
     */
    fun startCadEngine() {
        if (cadEngineStarting) return
        cadEngineStarting = true
        viewModelScope.launch {
            val started = withContext(Dispatchers.IO) { CadEngine.start(app) }
            cadEngineStarting = false
            _uiState.update {
                it.copy(statusMessage = if (started) "CAD engine ready" else CadEngine.status(app))
            }
        }
    }

    /** Ends the CAD engine. Safe when it is not running. */
    fun stopCadEngine() {
        viewModelScope.launch { withContext(Dispatchers.IO) { CadEngine.stop() } }
    }

    /** Guards against a second start landing while the first is still waiting for the port. */
    @Volatile private var cadEngineStarting = false

    /** Explicitly ends the Blender engine and its keeper service. */
    fun stopBlenderEngine() {
        // Asked for exactly when the engine is busy - which is when it takes the
        // longest to answer: two seconds to connect and five to read the reply.
        // On the main thread that froze the menu for as long as the engine took,
        // so the click only records the intent and the socket work happens here.
        _uiState.update { it.copy(statusMessage = "Stopping the Blender engine…") }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    BlenderEngine.shutdown()
                    BlenderEngineService.stop(app)
                }
            }.onSuccess {
                _uiState.update { it.copy(statusMessage = "Blender engine stopped") }
            }.onFailure(::showOperationFailure)
        }
    }

    /**
     * Puts an imported model on the plate and selects it.
     *
     * Appending is what makes the plate hold several objects. An import used to replace whatever
     * was there and delete the file behind it, which with a plate of parts would take one of the
     * user's models away without asking.
     */
    private fun MainUiState.withImportedModel(
        source: StlMesh,
        transformed: StlMesh,
        modelFile: File,
        placement: ModelPlacement,
        paint: SupportPaintState,
        automaticScene: CuraProjectScene?,
    ): MainUiState {
        val added = withoutPublishedSlice()
            .withModelAdded(
                PlateObject(
                    id = PlateObject.newId(),
                    // Two copies of one file are two parts, and the engines label by name.
                    name = uniqueModelName(source.displayName),
                    sourceMesh = source,
                    mesh = transformed,
                    sourcePath = modelFile.absolutePath,
                    placement = placement,
                    supportPaint = paint,
                ),
            )
            .copy(
                importedSceneTransformAvailable = automaticScene?.affine != null,
                importedSceneModelName = automaticScene?.modelName,
            )
        if (added.platePreferences.placement != PlatePreferences.Placement.AUTO) return added
        // The importer drops every model on the bed centre, which is fine for one and useless
        // for several: the packer finds each object a free spot as it arrives.
        val placed = arranged(added.models, added.platePreferences, added.settings)
            ?: return added.copy(
                warnings = (
                    added.warnings + "The parts do not fit on the bed at " +
                        "${added.platePreferences.sanitized().spacingMm} mm apart; " +
                        "${source.displayName} landed on the bed centre"
                    ).distinct(),
            )
        return added.copy(models = placed)
    }

    /** " · N models on the plate", once there is more than one, so an append is visible. */
    private fun MainUiState.plateSuffix(): String =
        if (models.size > 1) " · ${models.size} models on the plate" else ""

    fun importModel(uri: Uri) {
        if (deferUntilRestoreCompletes { importModel(uri) }) return
        if (!beginOperation("Reading model…")) return
        val sceneSnapshot = importedScene
        val stateSnapshot = _uiState.value
        viewModelScope.launch {
            // Hoisted: the paint is read by the onSuccess lambda, which is outside
            // the runCatching block the destructuring lives in.
            var importedPaint = SupportPaintState()
            runCatching {
                val (mesh, modelFile, paint) = withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val triangleLimit = MeshTriangleLimits.current()
                    val name = displayName(uri)
                    if (name.endsWith(".3mf", ignoreCase = true)) {
                        // A 3MF brings its own build placement and paint. The mesh is
                        // staged as STL so every downstream path - resolved Cura
                        // profiles, the texturizer, Smart Infill - keeps working with
                        // one model format, while the paint is kept in the session.
                        val source = materializeModel(uri, triangleLimit, "3mf")
                        val staged = stagedModelFile()
                        try {
                            val parsed = ThreeMfModelParser.parse(source, name, triangleLimit)
                            StlMeshWriter.writeBinary(parsed.mesh, staged)
                            ImportedModel(parsed.mesh, staged, parsed.paint)
                        } catch (error: Throwable) {
                            staged.delete()
                            throw error
                        } finally {
                            source.delete()
                        }
                    } else if (OcctEngineRunner.isCadFile(name)) {
                        // STEP and IGES carry analytic geometry - real circles, real planes -
                        // that none of the three slicers can read. OpenCASCADE tessellates it
                        // here so that, exactly as with 3MF above, one model format reaches
                        // every path downstream.
                        val extension = OcctEngineRunner.extensionOf(name)
                        val source = materializeModel(uri, triangleLimit, extension)
                        val staged = stagedModelFile()
                        try {
                            val log = File(source.parentFile, "occt-${source.name}.log")
                            if (!occtEngine.convertToStl(source, staged, log)) {
                                throw IllegalStateException(
                                    "OpenCASCADE could not convert " + name + " to a mesh; see " + log.name
                                )
                            }
                            ImportedModel(StlParser.parse(staged, name, triangleLimit), staged, SupportPaintState())
                        } catch (error: Throwable) {
                            staged.delete()
                            throw error
                        } finally {
                            source.delete()
                        }
                    } else {
                        val file = materializeModel(uri, triangleLimit, "stl")
                        try {
                            ImportedModel(StlParser.parse(file, name, triangleLimit), file, SupportPaintState())
                        } catch (error: Throwable) {
                            file.delete()
                            throw error
                        }
                    }
                }
                importedPaint = paint
                val prepared = withContext(Dispatchers.Default) {
                    val automaticPlacement = sceneSnapshot
                        ?.takeIf { scene -> scene.affine != null && modelNamesMatch(scene.modelName, mesh.displayName) }
                        ?.let { scene -> ModelPlacement.from3mf(mesh, requireNotNull(scene.affine), scene.dropToBuildPlate) }
                    val placement = automaticPlacement
                        ?: ModelPlacement.centeredOnBed(
                            mesh = mesh,
                            bedWidthMm = stateSnapshot.settings.machineWidthMm,
                            bedDepthMm = stateSnapshot.settings.machineDepthMm,
                            originAtCenter = stateSnapshot.settings.originAtCenter,
                        )
                    val transformed = placement.transformed(mesh)
                    val mismatchWarning = sceneSnapshot
                        ?.takeIf { it.affine != null && !modelNamesMatch(it.modelName, mesh.displayName) }
                        ?.let { scene ->
                            "Imported Cura transform is for ${scene.modelName ?: "another model"}; it was not applied automatically to ${mesh.displayName}"
                        }
                    PreparedModelImport(
                        source = mesh,
                        transformed = transformed,
                        modelFile = modelFile,
                        placement = placement,
                        automaticImportedPlacement = automaticPlacement != null,
                        mismatchWarning = mismatchWarning,
                    )
                }
                withContext(Dispatchers.IO) {
                    workspaceStore.save(
                        workspaceSnapshot(prepared.source, prepared.modelFile, prepared.placement, paint, stateSnapshot),
                    )
                }
                prepared
            }.onSuccess { prepared ->
                _uiState.update { current ->
                    val next = current.withImportedModel(
                        source = prepared.source,
                        transformed = prepared.transformed,
                        modelFile = prepared.modelFile,
                        placement = prepared.placement,
                        paint = importedPaint,
                        automaticScene = sceneSnapshot,
                    )
                    next.copy(
                        paintMode = SupportPaintMode.NONE,
                        warnings = (current.warnings.filterNot { it.startsWith("Imported Cura transform is for") } + listOfNotNull(prepared.mismatchWarning)).distinct(),
                        isBusy = false,
                        statusMessage = buildString {
                            append("Loaded ${prepared.source.displayName}: ${prepared.source.triangleCount} triangles")
                            if (prepared.automaticImportedPlacement) append(" · imported Cura scene transform applied")
                            if (!importedPaint.isEmpty) {
                                append(" · ${importedPaint.enforcerTriangles.size + importedPaint.blockerTriangles.size} painted facets")
                            }
                            append(next.plateSuffix())
                        },
                    )
                }
                onPlateImported()
            }.onFailure(::showOperationFailure)
        }
    }

    /**
     * Transactionally replaces the active model with a filaSim Part Topo solid.
     * The generic STL importer is intentionally bypassed: a previous 3MF affine
     * must never be inferred for geometry already derived from the displayed STL.
     */
    fun importPartTopoResult(uri: Uri) {
        if (deferUntilRestoreCompletes { importPartTopoResult(uri) }) return
        val stateSnapshot = _uiState.value
        val analyzedDisplayedMesh = stateSnapshot.mesh
        if (analyzedDisplayedMesh == null || stateSnapshot.modelPath == null) {
            showOperationFailure(IllegalStateException("The analyzed model is no longer available"))
            return
        }
        if (!beginOperation("Importing filaSim Part Topo result…")) return
        viewModelScope.launch {
            runCatching {
                val prepared = withContext(Dispatchers.IO) {
                    PartTopoResultPreparer.prepare(
                        context = app,
                        uri = uri,
                        analyzedDisplayedMesh = analyzedDisplayedMesh,
                        printer = stateSnapshot.printer,
                        settings = stateSnapshot.settings,
                    )
                }
                try {
                    withContext(Dispatchers.IO) {
                        workspaceStore.save(
                            workspaceSnapshot(
                                prepared.source,
                                prepared.modelFile,
                                prepared.placement,
                                SupportPaintState(),
                                stateSnapshot,
                            ),
                        )
                    }
                } catch (error: Throwable) {
                    prepared.modelFile.delete()
                    throw error
                }
                prepared
            }.onSuccess { prepared ->
                importedScene = null
                _uiState.update { current ->
                    val next = current.withImportedModel(
                        source = prepared.source,
                        transformed = prepared.transformed,
                        modelFile = prepared.modelFile,
                        placement = prepared.placement,
                        paint = SupportPaintState(),
                        automaticScene = null,
                    )
                    next.copy(
                        paintMode = SupportPaintMode.NONE,
                        warnings = current.warnings.filterNot {
                            it.startsWith("Imported Cura transform is for")
                        },
                        isBusy = false,
                        statusMessage = "Imported ${prepared.source.displayName} as a standalone Part Topo model; inspect and slice it" +
                            next.plateSuffix(),
                    )
                }
                onPlateImported()
            }.onFailure(::showOperationFailure)
        }
    }

    /**
     * Imports the latest Blender MCP handoff STL (written by the embedded
     * engine to <filesDir>/blender/exports/). Keeps the previous model on
     * screen until this import succeeds, matching the generation-loop contract.
     */
    fun importBlenderStl(file: File) = importEngineStl(file, "Blender")

    /**
     * Imports the latest CAD handoff STL, written by the engine to <filesDir>/cad/exports/.
     *
     * The same path as Blender's, deliberately: the engine writes a file, the app stages a
     * private copy, parses it and centres it on the bed. The two differ only in which engine
     * produced it, which is all the messages need to say.
     */
    fun importCadStl(file: File) = importEngineStl(file, "CAD")

    private fun importEngineStl(file: File, engine: String) {
        if (deferUntilRestoreCompletes { importEngineStl(file, engine) }) return
        if (!beginOperation("Importing $engine model…")) {
            // The engine has already claimed this export, so dropping it here loses
            // the model for good. Keep the newest one and take it as soon as the
            // running operation finishes.
            queueEngineImport(file, engine)
            return
        }
        val stateSnapshot = _uiState.value
        // Stage a private copy: the engine overwrites the export file on the next
        // iteration, and the workspace snapshot must keep pointing at a stable
        // model file. It is named outside the launch so a failed parse or save can
        // delete it - the engine re-exports on every iteration, so a broken export
        // would otherwise leak one staged model per loop.
        val staged = File(File(app.filesDir, "models"), engine.lowercase() + "-" + System.nanoTime() + ".stl")
        viewModelScope.launch {
            runCatching {
                val prepared = withContext(Dispatchers.IO) {
                    val triangleLimit = MeshTriangleLimits.current()
                    staged.parentFile?.mkdirs()
                    file.copyTo(staged, overwrite = true)
                    val mesh = StlParser.parse(staged, file.name, triangleLimit)
                    val placement = ModelPlacement.centeredOnBed(
                        mesh = mesh,
                        bedWidthMm = stateSnapshot.settings.machineWidthMm,
                        bedDepthMm = stateSnapshot.settings.machineDepthMm,
                        originAtCenter = stateSnapshot.settings.originAtCenter,
                    )
                    PreparedModelImport(
                        source = mesh,
                        transformed = placement.transformed(mesh),
                        modelFile = staged,
                        placement = placement,
                        automaticImportedPlacement = false,
                        mismatchWarning = null,
                    )
                }
                withContext(Dispatchers.IO) {
                    workspaceStore.save(
                        workspaceSnapshot(prepared.source, prepared.modelFile, prepared.placement, SupportPaintState(), stateSnapshot),
                    )
                }
                prepared
            }.onSuccess { prepared ->
                importedScene = null
                _uiState.update { current ->
                    val next = current.withImportedModel(
                        source = prepared.source,
                        transformed = prepared.transformed,
                        modelFile = prepared.modelFile,
                        placement = prepared.placement,
                        paint = SupportPaintState(),
                        automaticScene = null,
                    )
                    next.copy(
                        paintMode = SupportPaintMode.NONE,
                        warnings = current.warnings.filterNot { it.startsWith("Imported Cura transform is for") },
                        isBusy = false,
                        statusMessage = "Imported ${prepared.source.displayName} from the $engine engine" +
                            next.plateSuffix(),
                    )
                }
                onPlateImported()
            }.onFailure { error ->
                staged.delete()
                showOperationFailure(error)
            }
        }
    }

    fun importCuraProfile(uri: Uri) {
        if (deferUntilRestoreCompletes { importCuraProfile(uri) }) return
        if (!beginOperation("Importing Cura profile…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val sourceName = displayName(uri)
                    stageAndParseImport(uri, AppStateStore.KIND_PROFILE, sourceName) { file ->
                        file.inputStream().use { input ->
                            CuraProfileParser.parse(input, sourceName, SlicerSettings())
                        }
                    }
                }
            }.onSuccess { pending -> commitImportedConfig(pending) }
                .onFailure(::showOperationFailure)
        }
    }

    fun importCuraProject(uri: Uri) {
        if (deferUntilRestoreCompletes { importCuraProject(uri) }) return
        if (!beginOperation("Importing Cura project…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val sourceName = displayName(uri)
                    stageAndParseImport(
                        uri = uri,
                        kind = AppStateStore.KIND_PROJECT,
                        sourceName = sourceName,
                        parseScene = { file -> file.inputStream().use(CuraProjectSceneParser::parse) },
                    ) { file ->
                        file.inputStream().use { input ->
                            CuraProjectParser.parse(input, sourceName, SlicerSettings())
                        }
                    }
                }
            }.onSuccess { pending -> commitImportedConfig(pending) }
                .onFailure(::showOperationFailure)
        }
    }

    fun updateSettings(
        key: String,
        transform: (SlicerSettings) -> SlicerSettings,
    ) {
        val current = _uiState.value
        if (current.isBusy) return
        val changed = transform(current.settings)
            .copy(overriddenSettingKeys = current.settings.overriddenSettingKeys + key)
            .withRecomputedDerived()
        _uiState.update { state ->
            state.withoutPublishedSlice("Settings changed; slice again to export G-code").copy(
                settings = changed,
            )
        }
        persistSettings(changed, workspaceMutationGeneration.incrementAndGet())
    }

    fun importPrusaConfig(uri: Uri) {
        if (deferUntilRestoreCompletes { importPrusaConfig(uri) }) return
        if (!beginOperation("Importing PrusaSlicer config…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = app.contentResolver.openInputStream(uri)?.use { input ->
                        readPickedText(input, MAX_PRUSA_CONFIG_BYTES, "PrusaSlicer config")
                    } ?: error("Unable to open the selected PrusaSlicer config")
                    PrusaConfigImporter.parse(text)
                }
            }.onSuccess { imported ->
                _uiState.update { state ->
                    val machine = state.settings
                    val mappedFlavor = mapPrusaFlavorToApp(imported.gcodeFlavor)
                    val machineKeySet = buildSet {
                        if (imported.widthMm != null) { add(SlicerSettings.Keys.MACHINE_WIDTH); add(SlicerSettings.Keys.MACHINE_DEPTH) }
                        if (imported.nozzleSizeMm != null) add(SlicerSettings.Keys.NOZZLE_SIZE)
                        if (imported.filamentDiameterMm != null) add(SlicerSettings.Keys.FILAMENT_DIAMETER)
                        if (imported.extruders != null) add(SlicerSettings.Keys.ENABLED_EXTRUDER_COUNT)
                        if (mappedFlavor != null) add(SlicerSettings.Keys.GCODE_FLAVOR)
                    }
                    state.withoutPublishedSlice().copy(
                        prusaSettings = imported.settings,
                        settings = machine.copy(
                            machineWidthMm = imported.widthMm ?: machine.machineWidthMm,
                            machineDepthMm = imported.depthMm ?: machine.machineDepthMm,
                            originAtCenter = imported.originAtCenter.takeIf { imported.widthMm != null }
                                ?: machine.originAtCenter,
                            nozzleSizeMm = imported.nozzleSizeMm ?: machine.nozzleSizeMm,
                            filamentDiameterMm = imported.filamentDiameterMm ?: machine.filamentDiameterMm,
                            enabledExtruderCount = imported.extruders ?: machine.enabledExtruderCount,
                            gcodeFlavor = mappedFlavor ?: machine.gcodeFlavor,
                            overriddenSettingKeys = machine.overriddenSettingKeys + machineKeySet,
                        ),
                        // A profile without G-code templates leaves the stored ones
                        // alone: overwriting them with "" silently deleted the user's
                        // custom start/end G-code on every import.
                        prusaStartGcode = imported.startGcode ?: state.prusaStartGcode,
                        prusaEndGcode = imported.endGcode ?: state.prusaEndGcode,
                        isBusy = false,
                        statusMessage = "Imported PrusaSlicer config (" +
                            imported.settings.layerHeightMm + "mm layers, " +
                            imported.settings.perimeters + " perimeters, " +
                            imported.settings.fillDensityPercent + "% fill)",
                    )
                }
                persistPrusaSettings(imported.settings)
                if (imported.startGcode != null || imported.endGcode != null) {
                    val stored = _uiState.value
                    stateStore.savePrusaGcode(stored.prusaStartGcode, stored.prusaEndGcode)
                }
                persistSettings(_uiState.value.settings, workspaceMutationGeneration.incrementAndGet())
            }.onFailure(::showOperationFailure)
        }
    }

    /**
     * Imports an OrcaSlicer profile: a preset exported from the desktop app, or one of the preset
     * bundles its own import dialog accepts (.orca_printer, .orca_filament, .orca_bundle, .zip).
     *
     * Values land where the Orca sheet already edits them - a key the app has a field for sets that
     * field and every other key this engine declares is carried as an override - and a printer
     * preset also sets the machine envelope. When the profile names the preset it inherits from,
     * that base is selected as well: the sheet offers the process and filament a machine
     * preselects, not the vendor's whole list, so an imported delta would otherwise land on an
     * unrelated process and produce G-code the profile never described.
     */
    fun importOrcaProfile(uri: Uri) {
        if (deferUntilRestoreCompletes { importOrcaProfile(uri) }) return
        if (!beginOperation("Importing OrcaSlicer profile…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val sourceName = displayName(uri)
                    val result = OrcaProfileImporter.parse(sourceName, readOrcaProfileBytes(uri), orcaExtraSpecs)
                    val bases = mutableListOf<Pair<OrcaProfileImporter.Kind, OrcaBasePreset>>()
                    val missing = mutableListOf<Pair<OrcaProfileImporter.Kind, String>>()
                    for (preset in result.presets) {
                        val inherited = preset.inherits ?: continue
                        val base = OrcaPresetCatalog.findInstantiableBase(
                            app.assets,
                            preset.kind.catalogKind,
                            inherited,
                        )
                        if (base == null) missing += preset.kind to inherited else bases += preset.kind to base
                    }
                    ImportedOrcaProfile(sourceName, result, bases, missing)
                }
            }.onSuccess(::commitImportedOrcaProfile).onFailure(::showOperationFailure)
        }
    }

    private fun readOrcaProfileBytes(uri: Uri): ByteArray =
        app.contentResolver.openInputStream(uri)?.use { input ->
            readPickedBytes(input, OrcaProfileImporter.MAX_INPUT_BYTES.toLong(), "OrcaSlicer profile")
        } ?: error("Unable to open the selected OrcaSlicer profile")

    /** One import, once the base presets it names have been looked up in the bundled tree. */
    private data class ImportedOrcaProfile(
        val sourceName: String,
        val result: OrcaProfileImporter.Result,
        val bases: List<Pair<OrcaProfileImporter.Kind, OrcaBasePreset>>,
        val missingBases: List<Pair<OrcaProfileImporter.Kind, String>>,
    )

    private fun commitImportedOrcaProfile(imported: ImportedOrcaProfile) {
        val result = imported.result
        _uiState.update { state ->
            var orca = result.applyTo(state.orcaSettings)
            imported.bases.forEach { (kind, base) ->
                orca = when (kind) {
                    OrcaProfileImporter.Kind.PRINTER -> orca.copy(printerPreset = base.name)
                    OrcaProfileImporter.Kind.PROCESS -> orca.copy(processPreset = base.name)
                    OrcaProfileImporter.Kind.FILAMENT -> orca.copy(filamentPreset = base.name)
                }
            }
            val machine = result.machine
            val machineKeys = buildSet {
                if (machine.widthMm != null || machine.depthMm != null || machine.originAtCenter != null) {
                    add(SlicerSettings.Keys.MACHINE_WIDTH)
                    add(SlicerSettings.Keys.MACHINE_DEPTH)
                    add(SlicerSettings.Keys.ORIGIN_AT_CENTER)
                }
                if (machine.heightMm != null) add(SlicerSettings.Keys.MACHINE_HEIGHT)
                if (machine.nozzleSizeMm != null) add(SlicerSettings.Keys.NOZZLE_SIZE)
                if (machine.filamentDiameterMm != null) add(SlicerSettings.Keys.FILAMENT_DIAMETER)
                if (machine.gcodeFlavor != null) add(SlicerSettings.Keys.GCODE_FLAVOR)
            }
            state.withoutPublishedSlice().copy(
                orcaSettings = orca,
                extraOrcaSettings = state.extraOrcaSettings + result.extraKeys,
                settings = state.settings.copy(
                    machineWidthMm = machine.widthMm ?: state.settings.machineWidthMm,
                    machineDepthMm = machine.depthMm ?: state.settings.machineDepthMm,
                    machineHeightMm = machine.heightMm ?: state.settings.machineHeightMm,
                    originAtCenter = machine.originAtCenter ?: state.settings.originAtCenter,
                    nozzleSizeMm = machine.nozzleSizeMm ?: state.settings.nozzleSizeMm,
                    filamentDiameterMm = machine.filamentDiameterMm ?: state.settings.filamentDiameterMm,
                    gcodeFlavor = machine.gcodeFlavor ?: state.settings.gcodeFlavor,
                    overriddenSettingKeys = state.settings.overriddenSettingKeys + machineKeys,
                ),
                statusMessage = orcaImportSummary(imported, orca.printerPreset),
                // Every other importer releases the gate here; this one did not, so a successful
                // OrcaSlicer profile import left the app busy for good - Slice, Export, Import and
                // the model tools all gated, and only a restart to get out.
                isBusy = false,
            )
        }
        persistOrcaSettings(_uiState.value.orcaSettings)
        persistExtraSettings(SlicerEngine.ORCA)
        persistSettings(_uiState.value.settings, workspaceMutationGeneration.incrementAndGet())
    }

    /** What was imported, what was not, and which base preset the engine will layer it over. */
    private fun orcaImportSummary(imported: ImportedOrcaProfile, printerPreset: String): String {
        val result = imported.result
        val parts = mutableListOf<String>()
        parts += result.presets.joinToString(", ") { preset ->
            "\"" + (preset.name ?: imported.sourceName) + "\" (" + preset.kind.label + ")"
        }
        if (result.values.isNotEmpty()) parts += result.values.size.toString() + " settings"
        if (!result.machine.isEmpty) parts += "machine envelope"
        if (result.extraKeys.isNotEmpty()) parts += result.extraKeys.size.toString() + " engine overrides"
        imported.bases.forEach { (_, base) ->
            parts += "base preset \"" + base.name + "\" selected"
            if (base.compatiblePrinters.isNotEmpty() && printerPreset !in base.compatiblePrinters) {
                parts += "it is listed for " + base.compatiblePrinters.joinToString(", ")
            }
        }
        imported.missingBases.forEach { (kind, name) ->
            parts += "the " + kind.label + " preset it inherits (\"" + name + "\") is not in the bundled tree"
        }
        if (result.skippedPresetCount > 0) parts += result.skippedPresetCount.toString() + " further presets skipped"
        if (result.ignoredKeyCount > 0) parts += result.ignoredKeyCount.toString() + " keys ignored"
        if (result.refusedKeys.isNotEmpty()) parts += "refused " + result.refusedKeys.joinToString(", ")
        parts += result.notes
        return ("Imported OrcaSlicer profile " + parts.joinToString("; ")).take(600)
    }

    /**
     * Adds an extra setting, or returns why it was refused.
     *
     * The reason is returned as well as put in the status line: the editor that
     * calls this is in the all-settings sheet on the Settings tab, while the
     * status line is only drawn on the Plate tab, so a refusal was invisible
     * exactly where the entry was made.
     */
    fun setExtraSetting(engine: SlicerEngine, key: String, value: String): String? {
        val normalized = key.trim().lowercase()
        if (engine != SlicerEngine.CURA && engine != SlicerEngine.PRUSA && engine != SlicerEngine.ORCA) return null
        if (normalized.isEmpty() || !normalized.matches(Regex("[a-z][a-z0-9_]*"))) {
            return "the key is not a valid setting name"
        }
        if (normalized in blockedExtraKeys(engine)) return "the app manages this setting"
        // Refused here rather than at the slice: an unusable value was persisted and
        // re-sent on every later slice, so one typo failed every print with a generic
        // engine error and no clue which entry caused it.
        ExtraSettingValidation.rejectReason(normalized, value, extraSettingSpec(engine, normalized))?.let { reason ->
            _uiState.update { it.copy(statusMessage = reason) }
            return reason
        }
        _uiState.update { state ->
            when (engine) {
                SlicerEngine.CURA -> state.copy(extraCuraSettings = state.extraCuraSettings + (normalized to value))
                SlicerEngine.PRUSA -> state.copy(extraPrusaSettings = state.extraPrusaSettings + (normalized to value))
                SlicerEngine.ORCA -> state.copy(extraOrcaSettings = state.extraOrcaSettings + (normalized to value))
            }
        }
        persistExtraSettings(engine)
        return null
    }

    // Catalogues carry the value types (Cura declares "type": float/int); they are
    // parsed once per engine and cached, because this runs on the settings UI path.
    private val curaExtraSpecs: List<ExtraSettingSpec> by lazy { AllSettingsCatalogs.cura(app.assets) }
    private val prusaExtraSpecs: List<ExtraSettingSpec> by lazy { AllSettingsCatalogs.prusa(app.assets) }
    private val orcaExtraSpecs: List<ExtraSettingSpec> by lazy { AllSettingsCatalogs.orca(app.assets) }

    private fun extraSettingSpec(engine: SlicerEngine, key: String): ExtraSettingSpec? {
        val specs = when (engine) {
            SlicerEngine.CURA -> curaExtraSpecs
            SlicerEngine.PRUSA -> prusaExtraSpecs
            SlicerEngine.ORCA -> orcaExtraSpecs
        }
        // The key arrives lowercased (that is how it is stored); the catalogue keeps
        // the engine's own spelling, and Orca has one mixed-case key.
        return specs.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }

    /** Keys with dedicated editors or machine-envelope semantics must not be shadowed. */
    private fun blockedExtraKeys(engine: SlicerEngine): Set<String> = when (engine) {
        SlicerEngine.CURA -> AllSettingsCatalogs.CURA_BLOCKED_KEYS
        SlicerEngine.PRUSA -> AllSettingsCatalogs.PRUSA_BLOCKED_KEYS
        SlicerEngine.ORCA -> AllSettingsCatalogs.ORCA_BLOCKED_KEYS
    }

    fun removeExtraSetting(engine: SlicerEngine, key: String) {
        _uiState.update { state ->
            when (engine) {
                SlicerEngine.CURA -> state.copy(extraCuraSettings = state.extraCuraSettings - key)
                SlicerEngine.PRUSA -> state.copy(extraPrusaSettings = state.extraPrusaSettings - key)
                SlicerEngine.ORCA -> state.copy(extraOrcaSettings = state.extraOrcaSettings - key)
            }
        }
        persistExtraSettings(engine)
    }

    /** Prusa flavors into the app's Cura-side vocabulary (inverse of PrusaConfigWriter). */
    private fun mapPrusaFlavorToApp(flavor: String?): String? = when (flavor?.trim()?.lowercase()) {
        "marlin", "marlin2" -> "Marlin"
        "klipper" -> "Klipper"
        "reprap" -> "RepRap"
        "repetier" -> "Repetier"
        else -> null
    }

    private fun persistExtraSettings(engine: SlicerEngine) {
        extraSettingsPersistenceJob?.cancel()
        extraSettingsPersistenceJob = viewModelScope.launch(Dispatchers.IO) {
            delay(EXTRAS_PERSIST_DEBOUNCE_MILLIS)
            val snapshot = _uiState.value
            val saved = when (engine) {
                SlicerEngine.CURA -> stateStore.saveExtraCuraSettings(snapshot.extraCuraSettings)
                SlicerEngine.PRUSA -> stateStore.saveExtraPrusaSettings(snapshot.extraPrusaSettings)
                SlicerEngine.ORCA -> stateStore.saveExtraOrcaSettings(snapshot.extraOrcaSettings)
            }
            // The store persists only what the engine can take, so an entry it
            // refuses - or a write that did not go through at all - would otherwise
            // look saved and then vanish on the next launch.
            val complaint = when {
                saved.rejectedKeys.isNotEmpty() -> "Not saved - the engine cannot take " +
                    saved.rejectedKeys.sorted().joinToString(", ")
                !saved.persisted -> "Settings could not be saved; they will not survive a restart"
                else -> null
            }
            complaint?.let { message -> _uiState.update { it.copy(statusMessage = message) } }
        }
    }

    fun updatePrusaSettings(
        key: String,
        transform: (PrusaSliceSettings) -> PrusaSliceSettings,
    ) {
        val current = _uiState.value
        if (current.isBusy) return
        val changed = transform(current.prusaSettings)
        _uiState.update { state ->
            state.withoutPublishedSlice("Settings changed; slice again to export G-code").copy(
                prusaSettings = changed,
            )
        }
        persistPrusaSettings(changed)
    }

    private fun persistPrusaSettings(settings: PrusaSliceSettings) {
        // The Prusa editors commit on every keystroke, so these writes overlap.
        // Chaining each one onto the previous keeps the last snapshot written last
        // on disk: on a shared IO dispatcher an older keystroke could otherwise
        // finish after a newer one and silently revert the field.
        val previousWrite = prusaSettingsPersistenceJob
        prusaSettingsPersistenceJob = viewModelScope.launch(Dispatchers.IO) {
            previousWrite?.join()
            stateStore.savePrusaSettings(settings)
        }
    }

    /**
     * Applies an edit to the Orca settings, clearing the previous slice the way the Cura and
     * Prusa editors do: the exported G-code no longer matches what is on screen.
     */
    fun updateOrcaSettings(
        key: String,
        transform: (OrcaSliceSettings) -> OrcaSliceSettings,
    ) {
        val current = _uiState.value
        if (current.isBusy) return
        val changed = transform(current.orcaSettings)
        _uiState.update { state ->
            state.withoutPublishedSlice("Settings changed; slice again to export G-code").copy(
                orcaSettings = changed,
            )
        }
        persistOrcaSettings(changed)
    }

    private var prusaCatalogue: PrusaPresetCatalog? = null
    private var prusaCatalogueRequested = false

    /**
     * Loads the PrusaSlicer preset bundle the first time the Prusa sheet needs it. It is a 2 MB
     * JSON view of the vendor repository, read once and then resolved per chosen machine.
     */
    fun loadPrusaPresets() {
        if (prusaCatalogueRequested) return
        prusaCatalogueRequested = true
        viewModelScope.launch {
            val catalog = withContext(Dispatchers.IO) {
                PrusaPresetRepository.read(app.assets)?.let(::PrusaPresetCatalog)
            } ?: return@launch
            prusaCatalogue = catalog
            val printers = withContext(Dispatchers.IO) { catalog.printerOptions() }
            _uiState.update { it.copy(prusaPresetPrinters = printers) }
            refreshPrusaPresetLists()
        }
    }

    /** The quality profiles and materials of one machine, resolved off the main thread. */
    private fun refreshPrusaPresetLists() {
        val catalog = prusaCatalogue ?: return
        val printerId = _uiState.value.prusaPreset.printerId
        viewModelScope.launch {
            val lists = withContext(Dispatchers.IO) {
                val printer = printerId?.let(catalog::printer)
                if (printer == null) {
                    emptyList<PrusaPresetOption>() to emptyList<PrusaPresetOption>()
                } else {
                    catalog.printOptions(printer) to catalog.filamentOptions(printer)
                }
            }
            _uiState.update {
                it.copy(prusaPresetPrints = lists.first, prusaPresetFilaments = lists.second)
            }
        }
    }

    /**
     * Applies an edit to the preset selection and clears the previous slice, which no longer
     * matches the machine, quality profile or material it was built for. Changing the machine
     * also drops the profile and material chosen for the old one, since they do not carry over.
     */
    private fun updatePrusaPreset(transform: (PrusaPresetSelection) -> PrusaPresetSelection) {
        val current = _uiState.value
        if (current.isBusy) return
        val changed = transform(current.prusaPreset)
        if (changed == current.prusaPreset) return
        _uiState.update { state ->
            state.withoutPublishedSlice("Printer presets changed; slice again to export G-code").copy(
                prusaPreset = changed,
            )
        }
        viewModelScope.launch(Dispatchers.IO) { stateStore.savePrusaPreset(changed) }
        if (changed.printerId != current.prusaPreset.printerId) {
            // The lists are read from the bundle on a worker and both pickers are
            // active the moment a printer is chosen, so until the read returns they
            // still describe the previous printer: a quality profile or material
            // picked in that window would be stored for a printer that does not
            // offer it. Empty is what the rows render as their hint.
            _uiState.update {
                it.copy(prusaPresetPrints = emptyList(), prusaPresetFilaments = emptyList())
            }
            refreshPrusaPresetLists()
        }
    }

    fun selectPrusaPrinter(printerId: String) = updatePrusaPreset {
        it.copy(printerId = printerId, printName = null, filamentName = null)
    }

    fun selectPrusaPrint(printName: String) = updatePrusaPreset { it.copy(printName = printName) }

    fun selectPrusaFilament(filamentName: String) = updatePrusaPreset {
        it.copy(filamentName = filamentName)
    }

    private var curaCatalogueRequested = false

    /**
     * Loads the Cura machine catalogue the first time the Cura sheet needs it: it is 1250
     * definition files, and a Prusa or Orca slice never has a use for them.
     */
    fun loadCuraMachines() {
        if (curaCatalogueRequested || _uiState.value.curaMachines.isNotEmpty()) return
        curaCatalogueRequested = true
        viewModelScope.launch {
            val machines = withContext(Dispatchers.IO) { CuraMachineCatalog.machines(app.assets) }
            if (machines.isNotEmpty()) _uiState.update { it.copy(curaMachines = machines) }
        }
    }

    /**
     * Switches the Cura definition chain and clears the previous slice, which no longer matches
     * the machine the G-code was built for. The choice is stored, so it survives a restart.
     */
    fun selectCuraMachine(machineId: String) {
        val current = _uiState.value
        if (current.isBusy || current.curaMachineId == machineId) return
        _uiState.update { state ->
            state.withoutPublishedSlice("Printer changed; slice again to export G-code").copy(
                curaMachineId = machineId,
            )
        }
        viewModelScope.launch(Dispatchers.IO) { stateStore.saveCuraMachine(machineId) }
    }

    private fun persistOrcaSettings(settings: OrcaSliceSettings) {
        // Same chaining as the Prusa editor: these writes overlap on every keystroke and the
        // last snapshot has to be the last one written.
        val previousWrite = orcaSettingsPersistenceJob
        orcaSettingsPersistenceJob = viewModelScope.launch(Dispatchers.IO) {
            previousWrite?.join()
            stateStore.saveOrcaSettings(settings)
        }
    }

    fun resetAllSettingOverrides() {
        if (_uiState.value.isBusy) return
        val baseline = importedSettingsBaseline ?: SlicerSettings()
        val restored = baseline.copy(overriddenSettingKeys = emptySet()).withRecomputedDerived()
        persistSettings(restored, workspaceMutationGeneration.incrementAndGet())
        _uiState.update {
            it.withoutPublishedSlice().copy(
                settings = restored,
                statusMessage = if (importedSettingsBaseline != null) {
                    "App overrides cleared; imported Cura values are active"
                } else {
                    "App overrides cleared; built-in defaults are active"
                },
            )
        }
    }

    fun clearBuildPlate() {
        val snapshot = _uiState.value
        if (!beginOperation("Clearing build plate…")) return
        val pendingSettingsWrite = settingsPersistenceJob
        val artifactId = snapshot.gcodePath?.let(::File)?.parentFile?.name
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    pendingSettingsWrite?.join()
                    workspaceStore.clear()
                    File(app.filesDir, "models").listFiles().orEmpty().forEach { it.delete() }
                    artifactId?.let(engine::releaseArtifact)
                }
            }.onSuccess {
                importedScene = null
                _uiState.update { current ->
                    current.withoutPublishedSlice().copy(
                        models = emptyList(),
                        selectedModelId = null,
                        importedSceneTransformAvailable = false,
                        importedSceneModelName = null,
                        warnings = current.warnings.filterNot {
                            it.startsWith("Imported Cura transform is for")
                        },
                        isBusy = false,
                        statusMessage = "Build plate cleared; import an STL to begin",
                    )
                }
            }.onFailure(::showOperationFailure)
        }
    }

    /**
     * Lays the plate out again: largest first, in rows, with the gap the preferences ask for.
     *
     * This is the app's own packer because none of the three engines arranges for us: CuraEngine
     * has no arrangement code at all, and the Prusa and Orca consoles never call theirs. Both
     * Slic3r forks will happily slice overlapping objects, so nothing downstream would catch a
     * collision either.
     *
     * Returns null when the parts do not fit, which the caller reports instead of stacking them.
     */
    private fun arranged(
        models: List<PlateObject>,
        preferences: PlatePreferences,
        settings: SlicerSettings,
    ): List<PlateObject>? {
        if (models.size < 2) return models
        // A round bed holds less than its bounding box. Packing into the box put parts off the
        // bed, and CuraEngine then refused the whole slice; the packer is given the largest
        // rectangle the shape holds instead. The envelope check below is what actually decides,
        // so a shape nobody has thought about yet fails loudly rather than silently.
        val shape = settings.buildPlateShape.trim().lowercase(Locale.US)
        val round = shape == "elliptic" || shape == "ellipse" || shape == "circular" || shape == "circle"
        val usableWidth = if (round) settings.machineWidthMm / sqrt(2.0) else settings.machineWidthMm
        val usableDepth = if (round) settings.machineDepthMm / sqrt(2.0) else settings.machineDepthMm
        val slots = PlateArranger.arrange(
            footprints = models.map { model ->
                PlateFootprint(model.bounds.width.toDouble(), model.bounds.depth.toDouble())
            },
            bedWidthMm = usableWidth,
            bedDepthMm = usableDepth,
            spacingMm = preferences.sanitized().spacingMm,
        ) ?: return null
        // The packer works in bed coordinates with (0,0) at the front-left corner of what it was
        // given: a round bed insets that corner, and a centred origin measures from the middle.
        val insetX = (settings.machineWidthMm - usableWidth) / 2.0
        val insetY = (settings.machineDepthMm - usableDepth) / 2.0
        val shiftX = (if (settings.originAtCenter) settings.machineWidthMm / 2.0 else 0.0) - insetX
        val shiftY = (if (settings.originAtCenter) settings.machineDepthMm / 2.0 else 0.0) - insetY
        val placed = models.mapIndexed { index, model ->
            model.withCenter(slots[index].centerXmm - shiftX, slots[index].centerYmm - shiftY)
        }
        // The envelope refuses a machine shape or size it cannot model, and this runs from an
        // import's success handler, where a throw would leave the launch and take the process
        // with it. An unbuildable envelope means the check cannot answer, not that the plate is
        // wrong: the packer already keeps every part inside the bed it was given.
        val onTheBed = runCatching {
            val envelope = PrinterEnvelope.from(printer.withSettings(settings))
            placed.all { model ->
                val bounds = model.bounds
                listOf(
                    bounds.minX to bounds.minY,
                    bounds.maxX to bounds.minY,
                    bounds.minX to bounds.maxY,
                    bounds.maxX to bounds.maxY,
                ).all { (x, y) -> envelope.contains(x.toDouble(), y.toDouble(), 0.0) }
            }
        }.getOrDefault(true)
        return if (onTheBed) placed else null
    }

    /** Arranges the plate on demand, whatever the placement mode says. */
    fun arrangePlate() {
        val state = _uiState.value
        if (state.models.size < 2) return
        // Arranging is a plate change like any other, and it goes through the same gate: a plate
        // that changed while an engine was working would let a finished slice publish G-code for
        // a plate that no longer exists.
        if (!beginOperation("Arranging the plate…")) return
        viewModelScope.launch {
            runCatching {
                // The packer re-transforms every object's mesh, so a plate of large models is
                // seconds of work - off the main thread.
                val next = withContext(Dispatchers.Default) {
                    arranged(state.models, state.platePreferences, state.settings)
                } ?: throw IllegalStateException(
                    "The parts do not fit on the bed at " +
                        "${state.platePreferences.sanitized().spacingMm} mm apart",
                )
                placementHistory.clear()
                val commit = { current: MainUiState ->
                    // The packer worked from the plate as it was. If anything moved on while it
                    // ran, its layout is stale and must not be committed over the new plate.
                    val unchanged = current.models.size == state.models.size &&
                        current.models.indices.all { current.models[it] === state.models[it] }
                    if (!unchanged) {
                        // The packer ran against the plate as it was. Saying so is the whole
                        // point: a silent no-op looks like a broken button.
                        current.copy(
                            isBusy = false,
                            statusMessage = "The plate changed while it was being arranged; " +
                                "tap Arrange now again",
                        )
                    } else {
                        current.withoutPublishedSlice(
                            "Arranged ${next.size} objects; slice again to export G-code",
                        ).copy(
                            models = next,
                            canUndoPlacement = false,
                            undoPlacementLabel = null,
                            isBusy = false,
                        )
                    }
                }
                val snapshot = workspaceSnapshot(commit(_uiState.value))
                    ?: throw IllegalStateException("The arranged plate could not be saved")
                withContext(Dispatchers.IO) { workspaceStore.save(snapshot) }
                _uiState.update { commit(it) }
            }.onFailure(::showOperationFailure)
        }
    }

    /** Persists the multi-object preferences; moving into auto arrange packs the plate at once. */
    fun setPlatePreferences(preferences: PlatePreferences) {
        val sanitized = preferences.sanitized()
        val previous = _uiState.value.platePreferences
        // Sequential printing and object labels are slice inputs like any other, so changing
        // either has to drop the published G-code: exporting last slice's file while the sheet
        // shows the new choice is the same stale-artifact bug the rest of the app guards against.
        val sliceInputsChanged = sanitized.sequential != previous.sequential ||
            sanitized.objectLabels != previous.objectLabels
        _uiState.update { current ->
            if (sliceInputsChanged) {
                current.withoutPublishedSlice(
                    "Plate settings changed; slice again to export G-code",
                ).copy(platePreferences = sanitized)
            } else {
                current.copy(platePreferences = sanitized)
            }
        }
        viewModelScope.launch { withContext(Dispatchers.IO) { platePreferencesStore.save(sanitized) } }
        // Only a move *into* auto arrange repacks: the gap field reports every keystroke, and
        // repacking on each one threw away the placements the user had made by hand.
        if (sanitized.placement == PlatePreferences.Placement.AUTO &&
            previous.placement != PlatePreferences.Placement.AUTO
        ) {
            arrangePlate()
        }
    }

    /** The object the tools act on. Placement undo belongs to one object, so it is dropped. */
    fun selectModel(id: String) {
        val state = _uiState.value
        if (state.selectedModel?.id == id) return
        placementHistory.clear()
        // The brush size is one tool setting; it lives in the paint state, which is per object,
        // so it is carried to the object being selected instead of snapping to that object's own.
        val brushRadiusMm = state.supportPaint.brushRadiusMm
        _uiState.update { current ->
            current.copy(selectedModelId = id, canUndoPlacement = false, undoPlacementLabel = null)
                .withSelectedModel { model ->
                    model.withPaint(model.supportPaint.copy(brushRadiusMm = brushRadiusMm))
                }
        }
    }

    /** Takes an object off the plate and deletes the staged copy it was imported into. */
    fun removeModel(id: String) {
        val state = _uiState.value
        val removed = state.models.firstOrNull { it.id == id } ?: return
        if (!beginOperation("Removing ${removed.name}…")) return
        placementHistory.clear()
        // The state is built from whatever is current when it is committed, not from a snapshot
        // taken before the save: a slice finishing during that save used to have its result
        // overwritten by the snapshot.
        val commit = { current: MainUiState ->
            // withoutModel is what takes the object off the plate. Rebuilding the state from
            // [current] and forgetting this call left the object in place while its file was
            // deleted underneath it.
            current.withoutModel(id)
                .withoutPublishedSlice("Removed ${removed.name}; slice again to export G-code")
                .copy(canUndoPlacement = false, undoPlacementLabel = null, isBusy = false)
        }
        viewModelScope.launch {
            runCatching {
                val next = commit(_uiState.value)
                if (next.models.isEmpty()) {
                    // The last object is gone, so there is no plate left to describe. The
                    // descriptor goes with it rather than being saved or left behind: one naming
                    // a deleted file fails validation on the next launch and takes every model
                    // with it. This is clearBuildPlate's work, done without its busy gate,
                    // because the gate is what is already held here.
                    val artifactId = state.gcodePath?.let(::File)?.parentFile?.name
                    withContext(Dispatchers.IO) {
                        workspaceStore.clear()
                        File(app.filesDir, "models").listFiles().orEmpty().forEach { it.delete() }
                        artifactId?.let(engine::releaseArtifact)
                    }
                    importedScene = null
                    // clearBuildPlate's whole job: leaving the imported-scene banner up over an
                    // empty plate says a Cura transform is in force when nothing is there.
                    _uiState.update { current ->
                        commit(current).copy(
                            importedSceneTransformAvailable = false,
                            importedSceneModelName = null,
                            warnings = current.warnings.filterNot {
                                it.startsWith("Imported Cura transform is for")
                            },
                        )
                    }
                    return@runCatching
                }
                val snapshot = workspaceSnapshot(next)
                    ?: throw IllegalStateException("The plate could not be saved")
                withContext(Dispatchers.IO) {
                    workspaceStore.save(snapshot)
                    // The staged copy goes with the object - unless another object on the plate
                    // was imported from the same file, which a hand-written descriptor or a
                    // duplicated part can do. Deleting a shared file leaves that one unreadable.
                    val stillUsed = next.models.any { it.sourcePath == removed.sourcePath }
                    if (!stillUsed) {
                        removed.sourcePath?.let(::File)?.takeIf { it.isFile }?.delete()
                    }
                }
                _uiState.update { commit(it) }
            }.onFailure(::showOperationFailure)
        }
    }

    fun moveModel(centerXmm: Double, centerYmm: Double, baseZmm: Double) {
        changePlacement("Model position changed") { placement, _ ->
            placement.moved(centerXmm, centerYmm, baseZmm)
        }
    }

    /**
     * Moves the model by a plate-space delta, the way a finger drag on the plate
     * asks for it. One call per finished drag rather than per frame: the drag
     * previews itself in the viewer, while a real move re-transforms the mesh,
     * checks the build volume and writes the workspace snapshot.
     */
    fun nudgeModel(deltaXmm: Double, deltaYmm: Double) {
        if (!deltaXmm.isFinite() || !deltaYmm.isFinite()) return
        if (deltaXmm == 0.0 && deltaYmm == 0.0) return
        changePlacement("Model moved") { placement, _ ->
            placement.moved(
                centerXmm = placement.centerXmm + deltaXmm,
                centerYmm = placement.centerYmm + deltaYmm,
            )
        }
    }

    /**
     * Lifts or lowers the model, from a drag on the gizmo's vertical arrow. The
     * build-volume check refuses a lift that would leave the printer, exactly as
     * a typed value does.
     */
    fun liftModel(deltaZmm: Double) {
        if (!deltaZmm.isFinite() || deltaZmm == 0.0) return
        changePlacement("Model lifted") { placement, _ ->
            placement.moved(baseZmm = placement.baseZmm + deltaZmm)
        }
    }

    fun rotateModel(axis: ModelPlacement.Axis, degrees: Double) {
        changePlacement("Model rotated ${degrees.toInt()}° around ${axis.name}") { placement, _ ->
            placement.rotated(axis, degrees)
        }
    }

    /** The pinch gizmo's step: [percent] is a factor applied to the size it is now. */
    fun scaleModel(percent: Double) {
        changePlacement("Model scaled by ${scaleLabel(percent)}%") { placement, _ ->
            placement.scaled(percent)
        }
    }

    /** The Scale field's target: [percent] is the model's size, not a step. */
    fun scaleModelTo(percent: Double) {
        changePlacement("Model scaled to ${scaleLabel(percent)}%") { placement, _ ->
            placement.scaledTo(percent)
        }
    }

    private fun scaleLabel(percent: Double): String = if (percent == percent.toLong().toDouble()) {
        percent.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", percent).trimEnd('0').trimEnd('.')
    }

    fun dropModelToBed() {
        changePlacement("Model dropped to the build plate") { placement, _ -> placement.droppedToBed() }
    }

    fun layModelFlat() {
        changePlacement("Model laid flat on its largest face") { placement, mesh -> placement.layFlat(mesh) }
    }

    fun resetModelTransform() {
        val settings = _uiState.value.settings
        changePlacement("Model transform reset and centered") { _, mesh ->
            ModelPlacement.centeredOnBed(
                mesh = mesh,
                bedWidthMm = settings.machineWidthMm,
                bedDepthMm = settings.machineDepthMm,
                originAtCenter = settings.originAtCenter,
            )
        }
    }

    fun applyImportedSceneTransform() {
        val scene = importedScene
        val affine = scene?.affine
        if (scene == null || affine == null) {
            showOperationFailure(IllegalStateException("The imported Cura project has no object transform"))
            return
        }
        changePlacement("Imported Cura scene transform applied") { _, mesh ->
            ModelPlacement.from3mf(mesh, affine, scene.dropToBuildPlate)
        }
    }

    fun setPaintMode(mode: SupportPaintMode) {
        _uiState.update { it.copy(paintMode = mode) }
    }

    // ---- Smart Infill surface picking -------------------------------------
    //
    // The Smart Infill sheet assigns a boundary condition to the surface patch
    // under the user's finger. The tap arrives through the model view's pick
    // path, but it is the sheet's session that knows what the tap means, so the
    // hit is offered to a handler it installs while picking is armed.

    /**
     * The brush a stroke on the model paints with while a condition is armed.
     * The host owns it: it has the mesh to expand the brush over and the
     * controller that holds the radius and the add/erase mode.
     */
    private var smartInfillBrushHandler: ((MeshPicker.Hit) -> Unit)? = null

    /** Runs once when the finger comes up, to commit the finished stroke. */
    private var smartInfillStrokeEndHandler: (() -> Unit)? = null

    /** Arms or disarms tap-to-pick for the Smart Infill sheet. */
    fun setSmartInfillPicking(active: Boolean) {
        _uiState.update { it.copy(smartInfillPicking = active) }
    }

    /** Installs (or clears with null) the brush a surface stroke is offered to. */
    fun setSmartInfillBrushHandler(handler: ((MeshPicker.Hit) -> Unit)?) {
        smartInfillBrushHandler = handler
    }

    /** Installs (or clears with null) what a finished stroke commits. */
    fun setSmartInfillStrokeEndHandler(handler: (() -> Unit)?) {
        smartInfillStrokeEndHandler = handler
    }

    /**
     * The boundary conditions to tint on the model. Pushed by the plate screen
     * from the Smart Infill session, cleared when its panel closes.
     */
    fun setSmartInfillOverlay(overlay: SmartInfillOverlay?) {
        if (_uiState.value.smartInfillOverlay == overlay) return
        _uiState.update { it.copy(smartInfillOverlay = overlay) }
    }

    /**
     * Drops the published slice because an input the engine reads outside the
     * settings changed - the Smart Infill modifier package. The package is a
     * modifier on the engine command, so G-code sliced before it changed no
     * longer matches the model and must not stay exportable.
     */
    fun invalidatePublishedSlice(reason: String) {
        _uiState.update { current ->
            if (current.gcodePath == null && current.sliceResultId == null) {
                current
            } else {
                current.withoutPublishedSlice(reason)
            }
        }
    }

    /** One sample of a stroke while Smart Infill is armed. */
    fun brushSurfaceAt(hit: MeshPicker.Hit) {
        smartInfillBrushHandler?.invoke(hit)
    }

    /** The stroke ended: hand the finished selection to the engine, once. */
    fun endSmartInfillStroke() {
        smartInfillStrokeEndHandler?.invoke()
    }

    // ---- Annotation -------------------------------------------------------
    //
    // Annotation editing is transient: unlike support paint it is not part of
    // the saved workspace, because it describes intent for a modelling step
    // rather than a slicing input.

    private val annotation = AnnotationState()

    /**
     * Turns the annotation tool on or off.
     *
     * Enabling it starts from a clean slate. Points are indexed against the
     * current model, so keeping them across a model change would leave geometry
     * pointing at triangles that no longer exist.
     */
    fun setAnnotationActive(active: Boolean) {
        if (_uiState.value.annotationActive == active) return
        if (active) {
            annotation.clear()
            annotation.kind = AnnotationKind.PATH
            // Seed the working height at the middle of the model, so the first
            // tap lands somewhere sensible rather than on the build plate.
            _uiState.value.mesh?.bounds?.let { bounds ->
                annotation.workPlaneZ = bounds.centerZ
            }
        }
        _uiState.update { it.copy(annotationActive = active, annotationSavedPath = null) }
        publishAnnotation()
    }

    fun setAnnotationKind(kind: AnnotationKind) {
        annotation.kind = kind
        _uiState.update { it.copy(statusMessage = "Annotation: " + kind.name.lowercase()) }
    }

    /**
     * Applies one resolved gesture.
     *
     * Over the model the point follows the surface, which is what the user sees
     * under their finger. In empty space there is no surface to follow, so the
     * point keeps the depth it already had - that is what makes orbiting the
     * camera a usable way to judge depth without the point drifting nearer or
     * further.
     */
    /**
     * A tap places one end of the line being drawn.
     *
     * The first tap of a series sets the start; later ones set the end, and
     * once a segment has both ends it has to be locked before another can
     * begin. That is what keeps the lock meaningful rather than decorative.
     */
    fun onAnnotationTap(gesture: AnnotationGesture) {
        // Placed on the working plane, not wherever the ray happened to hit.
        // A ray gives a direction but no depth; the plane supplies the missing
        // one, so the point lands exactly under the finger at a known height.
        val point = WorkPlane.intersectHorizontal(
            origin = gesture.rayOrigin,
            direction = gesture.rayDirection,
            planeZ = annotation.workPlaneZ,
        )
        if (point == null) {
            _uiState.update {
                it.copy(statusMessage = "Tilt the view to place a point on this plane")
            }
            return
        }
        if (!annotation.tap(point, AnnotationAnchor.PLANE)) {
            _uiState.update { it.copy(statusMessage = "Lock the line before starting the next") }
            return
        }
        publishAnnotation()
    }

    /**
     * A drag that began on a handle moves that end across the working plane.
     *
     * Constrained to the plane rather than the view ray, so the end tracks the
     * finger instead of drifting nearer or further as the camera moves.
     */
    fun onAnnotationAdjust(end: SegmentEnd, gesture: AnnotationGesture) {
        val point = WorkPlane.intersectHorizontal(
            origin = gesture.rayOrigin,
            direction = gesture.rayDirection,
            planeZ = annotation.workPlaneZ,
        ) ?: return
        if (!annotation.moveHandleTo(end, point)) return
        publishAnnotation()
    }

    /** Begins a height-only adjustment of [end]; X and Y are left alone. */
    fun beginAnnotationZAdjust(end: SegmentEnd) {
        if (!annotation.beginZAdjust(end)) return
        publishAnnotation()
    }

    /**
     * A drag during a height adjustment changes Z and nothing else.
     *
     * The ray is crossed with a vertical plane through the point that faces the
     * camera, so dragging up raises the point rather than sliding it around.
     */
    fun onAnnotationZAdjust(gesture: AnnotationGesture) {
        val end = annotation.zAdjusting ?: return
        val current = annotation.handles().firstOrNull { it.first == end }?.second ?: return
        val z = WorkPlane.intersectVerticalForZ(
            origin = gesture.rayOrigin,
            direction = gesture.rayDirection,
            anchor = current.position,
        ) ?: return
        if (!annotation.setHandleZ(end, z.coerceIn(zRangeMin(), zRangeMax()))) return
        publishAnnotation()
    }

    fun endAnnotationZAdjust() {
        annotation.endZAdjust()
        publishAnnotation()
    }

    fun setAnnotationWorkPlaneZ(z: Float) {
        if (!z.isFinite()) return
        annotation.workPlaneZ = z.coerceIn(zRangeMin(), zRangeMax())
        publishAnnotation()
    }

    private fun zRangeMin(): Float = _uiState.value.mesh?.bounds?.minZ ?: 0f

    private fun zRangeMax(): Float = _uiState.value.mesh?.bounds?.maxZ ?: 100f

    /**
     * A drag that began on a handle moves that end.
     *
     * Over the model the end follows the surface; in empty space it keeps the
     * depth the drag started at, so orbiting to judge a point does not drag it
     * nearer or further.
     */
    /** Commits the line being drawn; the next one starts where this one ended. */
    fun lockAnnotationSegment() {
        if (!annotation.lockSegment()) return
        publishAnnotation()
    }

    /** Ends the series, so the next point placed starts a new one from scratch. */
    fun lockAnnotationSeries() {
        if (annotation.lockSeries() == null) {
            _uiState.update { it.copy(statusMessage = "A line needs two points") }
            return
        }
        publishAnnotation()
    }

    fun setAnnotationThickness(px: Float) {
        if (!px.isFinite()) return
        annotation.thicknessPx = px
        publishAnnotation()
    }

    fun undoAnnotation() {
        if (!annotation.undo()) return
        publishAnnotation()
    }

    fun clearAnnotation() {
        annotation.clear()
        publishAnnotation()
    }

    /** Writes the locked geometry to the app's files directory as JSON. */
    fun saveAnnotation() {
        val mesh = _uiState.value.mesh ?: return
        if (annotation.chains.none { it.isComplete() }) {
            _uiState.update { it.copy(statusMessage = "Nothing to save yet") }
            return
        }
        val document = AnnotationCodec.encode(
            state = annotation,
            model = AnnotationCodec.ModelSummary(
                name = mesh.displayName,
                triangleCount = mesh.triangleCount,
                sizeXMm = mesh.bounds.width,
                sizeYMm = mesh.bounds.depth,
                sizeZMm = mesh.bounds.height,
            ),
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val directory = File(getApplication<Application>().filesDir, "annotations")
                directory.mkdirs()
                val file = File(directory, "annotation-" + System.currentTimeMillis() + ".json")
                file.writeText(document.toString(2))
                file.absolutePath
            }.onSuccess { path ->
                _uiState.update {
                    it.copy(annotationSavedPath = path, statusMessage = "Annotation saved")
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(statusMessage = "Annotation save failed: " + error.message)
                }
            }
        }
    }

    private fun publishAnnotation() {
        val mesh = _uiState.value.mesh
        val start = annotation.segmentStart
        val end = annotation.endHandle
        _uiState.update {
            it.copy(
                annotationOverlay = AnnotationOverlayBuilder.build(
                    state = annotation,
                    markerSizeMm = AnnotationOverlayBuilder.markerSizeMm(mesh?.bounds),
                ),
                annotationCanLockSegment = annotation.canLockSegment,
                annotationCanLockSeries = annotation.canLockSeries,
                annotationAwaitingSecondPoint = start != null && end == null,
                annotationAnchor = end?.anchor,
                annotationMeasureMm = if (start != null && end != null) {
                    start.position.distanceTo(end.position)
                } else {
                    null
                },
                annotationChainMm = liveSeriesLengthMm(),
                annotationChainCount = annotation.chains.size,
                annotationSeriesPoints = annotation.currentSeries.size,
                annotationThicknessPx = annotation.thicknessPx,
                annotationWorkPlaneZ = annotation.workPlaneZ,
                annotationZAdjusting = annotation.zAdjusting != null,
                annotationZMin = mesh?.bounds?.minZ ?: 0f,
                annotationZMax = mesh?.bounds?.maxZ ?: 100f,
            )
        }
    }

    /** Length of the series being drawn, including the segment in progress. */
    private fun liveSeriesLengthMm(): Float {
        val series = annotation.currentSeries
        var total = 0f
        for (i in 0 until series.size - 1) {
            total += series[i].position.distanceTo(series[i + 1].position)
        }
        val start = annotation.segmentStart
        val end = annotation.endHandle
        if (start != null && end != null) {
            total += start.position.distanceTo(end.position)
        }
        return total
    }

    fun setBrushRadius(mm: Double) {
        if (!mm.isFinite()) return
        val radius = mm.coerceIn(SupportPaintState.MIN_BRUSH_RADIUS_MM, SupportPaintState.MAX_BRUSH_RADIUS_MM)
        _uiState.update { current ->
            current.withSelectedModel { it.withPaint(it.supportPaint.copy(brushRadiusMm = radius)) }
        }
        persistPaintSoon()
    }

    private fun persistPaintSoon() {
        paintPersistenceJob?.cancel()
        paintPersistenceJob = viewModelScope.launch(Dispatchers.IO) {
            delay(PAINT_PERSIST_DEBOUNCE_MILLIS)
            // IO, and guarded. The descriptor has a hard size limit and a painted
            // mesh can reach it (every painted triangle index is written out), so an
            // unguarded launch on the main dispatcher turned a big paint job into an
            // uncaught require() - and a dead process - rather than a message.
            //
            // The state is read after the debounce, not when the stroke landed: the
            // workspace record's fingerprint comes from the active profile and
            // settings, and a settings edit inside the window would otherwise be
            // saved under the fingerprint that was current when the brush touched
            // the model - which the next launch reads as "settings changed", and
            // drops the model.
            runCatching { persistCurrentWorkspace(_uiState.value) }.onFailure(::showOperationFailure)
        }
    }

    /**
     * Paints the object the stroke is on.
     *
     * The brush is expanded against THAT object's mesh, and the stroke is kept on it - the
     * triangle indices a brush produces mean nothing outside the mesh they came from, so
     * expanding against the selected object while the finger was on a neighbour painted the
     * wrong triangles on the wrong part. The object painted becomes the selected one, which is
     * what lets a stroke move from part to part without leaving the brush.
     */
    fun paintAt(hit: MeshPicker.Hit) {
        val snapshot = _uiState.value
        if (snapshot.paintMode == SupportPaintMode.NONE) return
        if (snapshot.isBusy) return
        val target = snapshot.models.getOrNull(hit.objectIndex) ?: return
        val radiusMm = snapshot.supportPaint.brushRadiusMm.toFloat()
        viewModelScope.launch(Dispatchers.Default) {
            val triangles = SupportPaintBrush.expand(
                mesh = target.mesh,
                hitX = hit.x,
                hitY = hit.y,
                hitZ = hit.z,
                radiusMm = radiusMm,
            )
            if (triangles.isEmpty()) return@launch
            _uiState.update { current ->
                // Look the object up again: the plate can have changed while the brush ran.
                val model = current.models.firstOrNull { it.id == target.id } ?: return@update current
                val updated = when (current.paintMode) {
                    SupportPaintMode.ENFORCER -> model.supportPaint.withEnforcer(triangles)
                    SupportPaintMode.BLOCKER -> model.supportPaint.withBlocker(triangles)
                    SupportPaintMode.ERASE -> model.supportPaint.erased(triangles)
                    SupportPaintMode.NONE -> model.supportPaint
                }
                // The painted triangles become support modifiers in the engine
                // command, so the published G-code stops matching the model here.
                if (updated == model.supportPaint) {
                    current
                } else {
                    current.withoutPublishedSlice("Support paint changed; slice again to export G-code")
                        .withModel(model.id) { it.withPaint(updated) }
                        .copy(selectedModelId = model.id)
                }
            }
            persistPaintSoon()
        }
    }

    fun clearSupportPaint() {
        _uiState.update { current ->
            if (current.supportPaint.isEmpty) {
                current
            } else {
                current.withoutPublishedSlice("Support paint cleared; slice again to export G-code")
                    .withSelectedModel { model -> model.withPaint(SupportPaintState()) }
            }
        }
        persistPaintSoon()
    }

    fun sliceModel() {
        val snapshot = _uiState.value
        val originalPath = snapshot.modelPath
        val transformedMesh = snapshot.mesh
        if (originalPath == null || transformedMesh == null) {
            showOperationFailure(IllegalStateException("Import an STL before slicing"))
            return
        }
        val sliceEngine = activeEngine
        val activeEngineAvailable = when (sliceEngine) {
            SlicerEngine.CURA -> engine.isAvailable()
            SlicerEngine.PRUSA -> prusaEngine.isAvailable()
            SlicerEngine.ORCA -> orcaEngine.isAvailable()
        }
        if (!activeEngineAvailable) {
            showOperationFailure(
                IllegalStateException(
                    when (sliceEngine) {
                        SlicerEngine.CURA -> engine.status()
                        SlicerEngine.PRUSA -> prusaEngine.status()
                        SlicerEngine.ORCA -> orcaEngine.status()
                    },
                ),
            )
            return
        }
        // The Smart Infill package is a Cura modifier and a settings overlay. The
        // other engines would slice without it and silently produce the wrong part.
        if (sliceEngine != SlicerEngine.CURA && SmartInfillRuntime.current() != null) {
            showOperationFailure(
                IllegalStateException(
                    "Smart Infill needs the Cura engine; switch to CuraEngine or remove the Smart Infill package before slicing",
                ),
            )
            return
        }
        // Layer events go into the file the Cura pipeline writes. The other engines slice
        // without them and report none, which used to replace the user's list with nothing:
        // editing them off Cura was refused with this same reasoning, and slicing was not.
        if (sliceEngine != SlicerEngine.CURA &&
            snapshot.layerEvents.any { it.source == LayerEventSource.USER }
        ) {
            showOperationFailure(
                IllegalStateException(
                    "Layer events are a Cura feature; switch to CuraEngine to keep them, or " +
                        "remove them before slicing with another engine",
                ),
            )
            return
        }
        // Engine capabilities, checked before the slice rather than discovered after
        // it. OrcaSlicer has its own non-planar implementation (Z-layer contouring),
        // PrusaSlicer has none, and conical slicing is the app's own G-code
        // transform, wired into the CuraEngine pipeline only. Each of these used to
        // slice flat while the UI still said the mode was on.
        val nonPlanarActive = NonPlanarRuntime.snapshot() != null
        val conicalActive = ConicalRuntime.snapshot() != null
        if (sliceEngine == SlicerEngine.PRUSA && nonPlanarActive) {
            showOperationFailure(
                IllegalStateException(
                    "PrusaSlicer has no non-planar slicing; switch to OrcaSlicer or CuraEngine, or disable it",
                ),
            )
            return
        }
        if (sliceEngine != SlicerEngine.CURA && conicalActive) {
            showOperationFailure(
                IllegalStateException(
                    "Conical slicing runs in the CuraEngine pipeline only; switch to CuraEngine or disable it",
                ),
            )
            return
        }
        val slicingMessage = when (sliceEngine) {
            SlicerEngine.CURA -> "CuraEngine is slicing…"
            SlicerEngine.PRUSA -> "PrusaSlicer is slicing…"
            SlicerEngine.ORCA -> "OrcaSlicer is slicing…"
        }
        if (!beginOperation(slicingMessage)) return
        Diagnostics.info("slice", "$slicingMessage ($sliceEngine)")
        if (NonPlanarRuntime.snapshot() != null && ConicalRuntime.snapshot() != null) {
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusMessage = "Non-planar and conical slicing are mutually exclusive; disable one before slicing",
                    sliceResultId = null,
                    gcodePath = null,
                    baseGcodePath = null,
                    layerPreview = null,
                    estimatedPrintSeconds = null,
                )
            }
            return
        }

        viewModelScope.launch {
            var strategyMessage: String? = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val stagingRoot = File(app.cacheDir, "model-placement").apply {
                        check(mkdirs() || isDirectory) { "Unable to create the model staging directory" }
                    }
                    val stagingDirectory = File(stagingRoot, "slice-${UUID.randomUUID()}").apply {
                        check(mkdir()) { "Unable to create an isolated model staging directory" }
                    }
                    val outcome = try {
                        // Every object on the plate is staged with its placement already baked into
                        // its vertices, so no engine needs a per-object transform and all three
                        // see world coordinates.
                        //
                        // One file per object, except that a Slic3r fork slicing several gets one
                        // 3MF: neither console takes more than one positional model (the last
                        // would silently win), so a multi-object plate has to arrive as one file.
                        val slicerFork = sliceEngine != SlicerEngine.CURA
                        val plate: List<SliceModel> = if (slicerFork && snapshot.models.size > 1) {
                            val staging = File(stagingDirectory, "plate.3mf")
                            PlateThreeMfWriter.write(
                                file = staging,
                                entries = snapshot.models.map { model ->
                                    PlateThreeMfWriter.Entry(
                                        name = model.name,
                                        mesh = model.mesh,
                                        paint = model.supportPaint,
                                    )
                                },
                                dialect = if (sliceEngine == SlicerEngine.PRUSA) {
                                    PlateThreeMfWriter.Dialect.PRUSA_LEGACY
                                } else {
                                    PlateThreeMfWriter.Dialect.ORCA
                                },
                            )
                            listOf(SliceModel(file = staging, name = "plate"))
                        } else {
                            snapshot.models.mapIndexed { index, model ->
                                // CuraEngine gets an STL plus painted modifier volumes;
                                // PrusaSlicer and OrcaSlicer read paint from the model file
                                // itself, so a painted object has to reach them as 3MF or the
                                // paint is silently dropped.
                                val file = if (slicerFork && !model.supportPaint.isEmpty) {
                                    File(stagingDirectory, "model-$index.3mf").also { staged ->
                                        // The dialect is the engine's, not a default: the two forks
                                        // read paint from differently named attributes, and the
                                        // Prusa one only through the loader its stamp selects.
                                        PaintedMeshWriter.write(
                                            mesh = model.mesh,
                                            paint = model.supportPaint,
                                            destination = staged,
                                            dialect = if (sliceEngine == SlicerEngine.PRUSA) {
                                                PlateThreeMfWriter.Dialect.PRUSA_LEGACY
                                            } else {
                                                PlateThreeMfWriter.Dialect.ORCA
                                            },
                                        )
                                    }
                                } else {
                                    File(stagingDirectory, "model-$index.stl").also { staged ->
                                        StlMeshWriter.writeBinary(model.mesh, staged)
                                    }
                                }
                                SliceModel(file = file, name = model.name, supportPaint = model.supportPaint)
                            }
                        }
                        val transformedFile = plate.first().file
                        // What the multi-object menu asks the engine for. The user's own
                        // "All settings" overrides are merged after these, so an explicit key
                        // still wins over the menu.
                        val platePreferences = snapshot.platePreferences
                        if (platePreferences.sequential && snapshot.models.size > 1) {
                            val refusal = SequentialPrintCheck.refuseReason(
                                models = snapshot.models,
                                settings = snapshot.settings,
                            )
                            if (refusal != null) throw IllegalStateException(refusal)
                        }
                        val plateKeys = buildMap {
                            if (platePreferences.objectLabels) put("gcode_label_objects", "firmware")
                            if (platePreferences.sequential) put("complete_objects", "1")
                        }
                        val orcaPlateKeys = buildMap {
                            if (platePreferences.objectLabels) {
                                put("gcode_label_objects", "1")
                                put("exclude_object", "1")
                            }
                            if (platePreferences.sequential) put("print_sequence", "by object")
                        }
                        val curaPlateKeys = buildMap {
                            if (platePreferences.sequential) put("print_sequence", "one_at_a_time")
                        }
                        if (sliceEngine == SlicerEngine.PRUSA) {
                            val prusaResult = prusaEngine.slice(
                                modelFile = transformedFile,
                                printer = snapshot.printer,
                                settings = snapshot.prusaSettings.copy(
                                    extraKeys = plateKeys + snapshot.extraPrusaSettings,
                                ),
                                machineSettings = snapshot.settings,
                                startGcode = snapshot.prusaStartGcode.ifBlank { initialStartGcode },
                                endGcode = snapshot.prusaEndGcode.ifBlank { initialEndGcode },
                                presets = snapshot.prusaPreset,
                                onProgress = { percent ->
                                    _uiState.update {
                                        it.copy(
                                            statusMessage = "PrusaSlicer is slicing… $percent%",
                                            sliceProgressPercent = percent,
                                        )
                                    }
                                },
                            )
                            EngineSliceOutcome(
                                artifactId = prusaResult.artifactId,
                                gcodeFile = prusaResult.gcodeFile,
                                baseGcodeFile = prusaResult.baseGcodeFile,
                                logFile = prusaResult.logFile,
                                elapsedMilliseconds = prusaResult.elapsedMilliseconds,
                                estimatedPrintSeconds = prusaResult.estimatedPrintSeconds,
                                layerPreview = prusaResult.layerPreview,
                                layerEvents = prusaResult.layerEvents,
                                nozzleCollisionAlert = null,
                                collisionSweepFailure = null,
                            )
                        } else if (sliceEngine == SlicerEngine.ORCA) {
                            // OrcaSlicer's own non-planar implementation: Z-layer
                            // contouring varies Z inside a layer so top-facing
                            // surfaces follow the model. The relief-field settings
                            // of the CuraEngine path do not apply here; zaa_min_z
                            // and zaa_minimize_perimeter_height keep their Orca
                            // defaults unless All settings overrides them.
                            val orcaExtras = orcaPlateKeys + snapshot.extraOrcaSettings +
                                if (nonPlanarActive) mapOf("zaa_enabled" to "1") else emptyMap()
                            val orcaResult = orcaEngine.slice(
                                modelFile = transformedFile,
                                printer = snapshot.printer,
                                settings = snapshot.orcaSettings.copy(extraKeys = orcaExtras),
                                machineSettings = snapshot.settings,
                                startGcode = snapshot.startGcode,
                                endGcode = snapshot.endGcode,
                                onProgress = { percent ->
                                    _uiState.update {
                                        it.copy(
                                            statusMessage = "OrcaSlicer is slicing… $percent%",
                                            sliceProgressPercent = percent,
                                        )
                                    }
                                },
                            )
                            EngineSliceOutcome(
                                artifactId = orcaResult.artifactId,
                                gcodeFile = orcaResult.gcodeFile,
                                baseGcodeFile = orcaResult.baseGcodeFile,
                                logFile = orcaResult.logFile,
                                elapsedMilliseconds = orcaResult.elapsedMilliseconds,
                                estimatedPrintSeconds = orcaResult.estimatedPrintSeconds,
                                layerPreview = orcaResult.layerPreview,
                                layerEvents = orcaResult.layerEvents,
                                nozzleCollisionAlert = null,
                                collisionSweepFailure = null,
                            )
                        } else {
                            val smartResolution = SmartOverhangStrategy.resolve(
                                settings = snapshot.settings,
                                nonPlanarSettings = NonPlanarRuntime.current(),
                                mesh = transformedMesh,
                                layerHeightMm = snapshot.settings.layerHeightMm,
                                nozzleDiameterMm = snapshot.printer.withSettings(snapshot.settings).nozzleSizeMm,
                            )
                            strategyMessage = smartResolution.message
                            val curaResult = engine.slice(
                                modelFile = transformedFile,
                                models = plate,
                                // One object at a time is a group boundary in CuraEngine, not just
                                // the print_sequence switch: the runner emits the --next.
                                sequential = platePreferences.sequential,
                                printer = snapshot.printer,
                                settings = smartResolution.settings,
                                startGcode = snapshot.startGcode,
                                endGcode = snapshot.endGcode,
                                profile = snapshot.engineProfile,
                                machineId = snapshot.curaMachineId,
                                layerEvents = snapshot.layerEvents.filter { it.source == LayerEventSource.USER },
                                supportPaint = snapshot.supportPaint,
                                extraSettings = curaPlateKeys + snapshot.extraCuraSettings,
                                onProgress = { percent ->
                                    _uiState.update {
                                        it.copy(
                                            statusMessage = "CuraEngine is slicing… $percent%",
                                            sliceProgressPercent = percent,
                                        )
                                    }
                                },
                            )
                            EngineSliceOutcome(
                                artifactId = curaResult.artifactId,
                                gcodeFile = curaResult.gcodeFile,
                                baseGcodeFile = curaResult.baseGcodeFile,
                                logFile = curaResult.logFile,
                                elapsedMilliseconds = curaResult.elapsedMilliseconds,
                                estimatedPrintSeconds = curaResult.estimatedPrintSeconds,
                                layerPreview = curaResult.layerPreview,
                                layerEvents = curaResult.layerEvents,
                                nozzleCollisionAlert = curaResult.nozzleCollisionAlert,
                                collisionSweepFailure = curaResult.collisionSweepFailure,
                            )
                        }
                    } finally {
                        stagingDirectory.deleteRecursively()
                    }
                    // Read here, on IO, so the state the UI composes from never has
                    // to inspect the published artifact on disk.
                    outcome.copy(
                        gcodeComplete = SliceArtifactPublisher.isCompleteGcode(
                            outcome.gcodeFile,
                            outcome.artifactId,
                        ),
                    )
                }
            }.onSuccess { result ->
                val previousArtifactId = _uiState.value.gcodePath?.let(::File)?.parentFile?.name
                _uiState.update { current ->
                    val printTime = result.estimatedPrintSeconds?.let(::formatPrintTime)
                    current.copy(
                        sliceResultId = result.artifactId,
                        gcodePath = result.gcodeFile.absolutePath,
                        gcodeComplete = result.gcodeComplete,
                        baseGcodePath = result.baseGcodeFile.absolutePath,
                        sliceEngine = sliceEngine,
                        layerPreview = result.layerPreview,
                        layerEvents = result.layerEvents,
                        estimatedPrintSeconds = result.estimatedPrintSeconds,
                        sliceLogPath = result.logFile.absolutePath,
                        sliceDurationMilliseconds = result.elapsedMilliseconds,
                        isBusy = false,
                        statusMessage = buildString {
                            append("Sliced ${formatFileSize(result.gcodeFile.length())} of validated G-code in ${formatDuration(result.elapsedMilliseconds)}")
                            if (printTime != null) append(" · estimated print $printTime")
                            if (strategyMessage != null) append(" · $strategyMessage")
                            result.nozzleCollisionAlert?.let { alert ->
                                val zone = when (alert.worstViolationZone) {
                                    2 -> "the heating block"
                                    3 -> "the plate clearance"
                                    else -> "the nozzle cone"
                                }
                                val layersSuffix = alert.offendingLayers
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { " on layers " + it.sorted().joinToString(", ") }
                                    ?: ""
                                append(
                                    " · ⚠ nozzle collision risk: up to " +
                                        "%.1f mm into $zone".format(alert.maximumViolationMm) +
                                        layersSuffix +
                                        if (alert.cutoffViolatingMoves > 0) {
                                            " · material exceeds the holding-object clearance"
                                        } else {
                                            ""
                                        },
                                )
                            }
                            result.collisionSweepFailure?.let { failure ->
                                append(" · ⚠ nozzle collision sweep failed: $failure")
                            }
                            if (result.layerPreview == null) append(" · layer preview unavailable; see diagnostic log")
                            if (result.layerEvents.isNotEmpty()) append(" · ${result.layerEvents.size} layer events")
                        },
                    )
                }
                // The same facts the status line carries, written down. A safety
                // alert is a WARNING rather than a failure: the slice is good,
                // and the one line about it must not read as "your slice broke".
                Diagnostics.info(
                    "slice",
                    "Sliced " + formatFileSize(result.gcodeFile.length()) +
                        " of validated G-code in " + formatDuration(result.elapsedMilliseconds),
                )
                result.nozzleCollisionAlert?.let { alert ->
                    val zone = when (alert.worstViolationZone) {
                        2 -> "the heating block"
                        3 -> "the plate clearance"
                        else -> "the nozzle cone"
                    }
                    val layers = alert.offendingLayers
                        .takeIf { it.isNotEmpty() }
                        ?.let { " on layers " + it.sorted().joinToString(", ") }
                        ?: ""
                    Diagnostics.warning(
                        "slice",
                        "nozzle collision risk: up to %.1f mm into %s%s".format(alert.maximumViolationMm, zone, layers),
                    )
                }
                result.collisionSweepFailure?.let { sweep ->
                    Diagnostics.warning("slice", "nozzle collision sweep failed: " + sweep)
                }
                previousArtifactId
                    ?.takeIf { it != result.artifactId }
                    ?.let {
                        when (sliceEngine) {
                            SlicerEngine.CURA -> engine.releaseArtifact(it)
                            SlicerEngine.PRUSA -> prusaEngine.releaseArtifact(it)
                            SlicerEngine.ORCA -> orcaEngine.releaseArtifact(it)
                        }
                    }
            }.onFailure(::showSliceFailure)
        }
    }

    fun addLayerEvent(
        layerNumber: Int,
        zMm: Float,
        type: LayerEventType,
        value: Double? = null,
        secondaryValue: Double? = null,
        text: String = "",
    ) {
        val event = LayerEvent(
            id = "user-${layerEventSequence.incrementAndGet()}",
            layerNumber = layerNumber,
            zMm = zMm,
            type = type,
            value = value,
            secondaryValue = secondaryValue,
            text = text,
        )
        reapplyLayerEvents(_uiState.value.layerEvents + event, "Layer event added")
    }

    fun removeLayerEvent(id: String) {
        reapplyLayerEvents(_uiState.value.layerEvents.filterNot { it.id == id }, "Layer event removed")
    }

    fun clearLayerEvents() {
        reapplyLayerEvents(emptyList(), "User layer events cleared")
    }

    private fun reapplyLayerEvents(events: List<LayerEvent>, message: String) {
        val current = _uiState.value
        val basePath = current.baseGcodePath
        if (basePath == null) {
            showEventFailure(IllegalStateException("Slice the model before editing layer events"))
            return
        }
        if (current.sliceEngine != SlicerEngine.CURA) {
            showEventFailure(
                IllegalStateException(
                    "Layer events are a Cura feature; switch to the Cura engine and re-slice before editing them",
                ),
            )
            return
        }
        if (!beginOperation("Applying layer events…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val applied = engine.applyLayerEvents(File(basePath), events, current.endGcode)
                    applied to SliceArtifactPublisher.isCompleteGcode(applied.gcodeFile, applied.artifactId)
                }
            }.onSuccess { (result, complete) ->
                val previousArtifactId = _uiState.value.gcodePath?.let(::File)?.parentFile?.name
                _uiState.update { current ->
                    current.copy(
                        sliceResultId = result.artifactId,
                        gcodePath = result.gcodeFile.absolutePath,
                        gcodeComplete = complete,
                        baseGcodePath = result.baseGcodeFile.absolutePath,
                        layerPreview = result.layerPreview,
                        layerEvents = result.layerEvents,
                        estimatedPrintSeconds = result.estimatedPrintSeconds,
                        isBusy = false,
                        statusMessage = "$message · ${result.layerEvents.size} active events",
                    )
                }
                previousArtifactId
                    ?.takeIf { it != result.artifactId }
                    ?.let(engine::releaseArtifact)
            }.onFailure(::showEventFailure)
        }
    }

    fun exportGcode(uri: Uri) {
        if (deferUntilRestoreCompletes { exportGcode(uri) }) return
        val artifactSnapshot = _uiState.value
        val sourcePath = artifactSnapshot.gcodePath
        val expectedArtifactId = artifactSnapshot.sliceResultId
        if (sourcePath == null || expectedArtifactId == null) {
            showOperationFailure(IllegalStateException("Slice the model before exporting G-code"))
            return
        }

        if (!beginOperation("Exporting G-code…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val source = File(sourcePath)
                    check(SliceArtifactPublisher.isCompleteGcode(source, expectedArtifactId)) {
                        "Generated G-code is incomplete, stale, or no longer available"
                    }
                    pendingExportStore.begin(uri)
                    try {
                        SliceArtifactPublisher.acquireLease(source, expectedArtifactId).use {
                            val written = app.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->
                                source.inputStream().buffered().use { input -> input.copyTo(output).also { output.flush() } }
                            } ?: error("Unable to open the G-code destination")
                            check(written == source.length()) { "The G-code export ended before every byte was written" }
                        }
                        pendingExportStore.complete(uri)
                    } catch (error: Throwable) {
                        pendingExportStore.fail(uri)
                        throw error
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(isBusy = false, statusMessage = "G-code exported") }
            }.onFailure(::showOperationFailure)
        }
    }

    fun exportConfiguration(uri: Uri) {
        if (deferUntilRestoreCompletes { exportConfiguration(uri) }) return
        if (!beginOperation("Exporting configuration…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = configurationJson(_uiState.value).toString(2).toByteArray(Charsets.UTF_8)
                    pendingExportStore.begin(uri)
                    try {
                        val written = app.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->
                            output.write(bytes)
                            output.flush()
                            bytes.size.toLong()
                        } ?: error("Unable to open the export destination")
                        check(written == bytes.size.toLong()) { "The configuration export was incomplete" }
                        pendingExportStore.complete(uri)
                    } catch (error: Throwable) {
                        pendingExportStore.fail(uri)
                        throw error
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(isBusy = false, statusMessage = "Configuration exported") }
            }.onFailure(::showOperationFailure)
        }
    }

    /**
     * Writes the diagnostic report for the last slice - the setup it ran with plus the
     * engine's own log - to the document the user picked. The engines' failure messages
     * tell the user to export it, so this is what makes that promise true.
     */
    fun exportDiagnosticLog(uri: Uri) {
        if (deferUntilRestoreCompletes { exportDiagnosticLog(uri) }) return
        val state = _uiState.value
        val logPath = state.sliceLogPath
        if (logPath == null) {
            _uiState.update { it.copy(statusMessage = "There is no engine log yet - slice first") }
            return
        }
        if (!beginOperation("Exporting diagnostic log…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val logFile = File(logPath)
                    check(logFile.isFile) { "The engine's log is no longer on disk - slice again" }
                    val bytes = diagnosticReport(
                        state = state,
                        engine = state.sliceEngine ?: activeEngine,
                        appVersion = BuildConfig.VERSION_NAME,
                        device = deviceDescription(),
                        now = Instant.now().toString(),
                        logText = logFile.inputStream().use { logInput ->
                            val (text, truncated) = readPickedTextTruncated(logInput, MAX_DIAGNOSTIC_LOG_BYTES)
                            text + if (truncated) {
                                "\n\n[The engine log is longer than 8 MiB; only its first part is included.]"
                            } else {
                                ""
                            }
                        },
                    ).toByteArray(Charsets.UTF_8)
                    pendingExportStore.begin(uri)
                    try {
                        val written = app.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->
                            output.write(bytes)
                            output.flush()
                            bytes.size.toLong()
                        } ?: error("Unable to open the export destination")
                        check(written == bytes.size.toLong()) { "The diagnostic export was incomplete" }
                        pendingExportStore.complete(uri)
                    } catch (error: Throwable) {
                        pendingExportStore.fail(uri)
                        throw error
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(isBusy = false, statusMessage = "Diagnostic log exported") }
            }.onFailure(::showOperationFailure)
        }
    }

    private fun deviceDescription(): String =
        Build.MODEL + " (" + Build.MANUFACTURER + "), Android " + Build.VERSION.RELEASE +
            " (API " + Build.VERSION.SDK_INT + ")"

    fun importConfiguration(uri: Uri) {
        if (deferUntilRestoreCompletes { importConfiguration(uri) }) return
        if (!beginOperation("Importing configuration snapshot…")) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val sourceName = displayName(uri)
                    val root = app.contentResolver.openInputStream(uri)?.use { input ->
                        JSONObject(readPickedText(input, MAX_CONFIG_SNAPSHOT_BYTES, "configuration snapshot"))
                    } ?: error("Unable to open the selected configuration snapshot")
                    val format = root.optString("format")
                    val version = root.optInt("version", -1)
                    require(format == CONFIG_SNAPSHOT_FORMAT) {
                        "The selected file is not a TrioSlicer configuration snapshot"
                    }
                    require(version == CONFIG_SNAPSHOT_VERSION) {
                        "Unsupported configuration snapshot version $version"
                    }
                    val values = root.optJSONObject("settings")
                        ?: error("The configuration snapshot has no settings")
                    val snapshot = SlicerSettingsJson.apply(SlicerSettings(), values, SlicerSettingsJson.allKeys)
                        .copy(overriddenSettingKeys = emptySet())
                    val startGcode = root.optString("startGcode", initialStartGcode)
                    val endGcode = root.optString("endGcode", initialEndGcode)
                    // Prusa fields are absent from older snapshots; null keeps the
                    // currently stored Prusa state untouched in that case.
                    val prusaSettings = root.optString("prusaSettings", "")
                        .takeIf { it.isNotBlank() }
                        ?.let(PrusaSliceSettingsJson::deserialize)
                    val hasPrusaGcode = root.has("prusaStartGcode") || root.has("prusaEndGcode")
                    val prusaStartGcode = root.optString("prusaStartGcode", "").takeIf { hasPrusaGcode }
                    val prusaEndGcode = root.optString("prusaEndGcode", "").takeIf { hasPrusaGcode }
                    val extraPrusaSettings = root.optJSONObject("extraPrusaSettings")
                        ?.let { parseStringMap(it).takeIf { map -> map.isNotEmpty() } }
                    val extraCuraSettings = root.optJSONObject("extraCuraSettings")
                        ?.let { parseStringMap(it).takeIf { map -> map.isNotEmpty() } }
                    val orcaSettings = root.optString("orcaSettings", "")
                        .takeIf { it.isNotBlank() }
                        ?.let(OrcaSliceSettingsJson::deserialize)
                    val extraOrcaSettings = root.optJSONObject("extraOrcaSettings")
                        ?.let { parseStringMap(it).takeIf { map -> map.isNotEmpty() } }
                    SnapshotImport(
                        snapshot,
                        startGcode,
                        endGcode,
                        sourceName,
                        prusaSettings,
                        prusaStartGcode,
                        prusaEndGcode,
                        extraPrusaSettings,
                        extraCuraSettings,
                        orcaSettings,
                        extraOrcaSettings,
                    )
                }
            }.onSuccess { pending -> commitSnapshotImport(pending) }
                .onFailure(::showOperationFailure)
        }
    }

    private suspend fun commitSnapshotImport(pending: SnapshotImport) {
        val pendingSettingsWrite = settingsPersistenceJob
        val pendingPrusaWrite = prusaSettingsPersistenceJob
        // Extras are applied last at slice time, so an app-managed key arriving in a
        // snapshot would silently override the app's own value on every later slice.
        // The store refuses unusable values; the keys the app itself manages are
        // dropped here, where the engine that owns them is known, and reported below.
        val acceptedPrusaExtras = pending.extraPrusaSettings
            ?.filterKeys { it !in AllSettingsCatalogs.PRUSA_BLOCKED_KEYS }
        val acceptedCuraExtras = pending.extraCuraSettings
            ?.filterKeys { it !in AllSettingsCatalogs.CURA_BLOCKED_KEYS }
        val acceptedOrcaExtras = pending.extraOrcaSettings
            ?.filterKeys { it !in AllSettingsCatalogs.ORCA_BLOCKED_KEYS }
        val blockedExtras = buildList {
            pending.extraPrusaSettings?.keys
                ?.filterTo(this) { it in AllSettingsCatalogs.PRUSA_BLOCKED_KEYS }
            pending.extraCuraSettings?.keys
                ?.filterTo(this) { it in AllSettingsCatalogs.CURA_BLOCKED_KEYS }
            pending.extraOrcaSettings?.keys
                ?.filterTo(this) { it in AllSettingsCatalogs.ORCA_BLOCKED_KEYS }
        }
        val rejectedExtras = runCatching {
            withContext(Dispatchers.IO) {
                pendingSettingsWrite?.join()
                // The Prusa settings below are written straight from the snapshot,
                // so a keystroke still in flight here would overwrite the imported
                // ones with the values it is replacing.
                pendingPrusaWrite?.join()
                stateStore.clearImport()
                stateStore.saveSnapshotBaseline(
                    AppStateStore.SnapshotBaseline(
                        settings = pending.settings,
                        startGcode = pending.startGcode,
                        endGcode = pending.endGcode,
                        profileName = pending.sourceName,
                        profileSource = "Configuration snapshot",
                    ),
                )
                stateStore.saveSettings(pending.settings)
                // The store commits synchronously, so the remaining snapshot writes
                // belong on this thread with the ones above, not on the main thread.
                if (pending.prusaSettings != null) stateStore.savePrusaSettings(pending.prusaSettings!!)
                if (pending.orcaSettings != null) stateStore.saveOrcaSettings(pending.orcaSettings!!)
                if (pending.prusaStartGcode != null && pending.prusaEndGcode != null) {
                    stateStore.savePrusaGcode(pending.prusaStartGcode!!, pending.prusaEndGcode!!)
                }
                buildList {
                    acceptedPrusaExtras
                        ?.let { addAll(stateStore.saveExtraPrusaSettings(it).rejectedKeys) }
                    acceptedCuraExtras
                        ?.let { addAll(stateStore.saveExtraCuraSettings(it).rejectedKeys) }
                    acceptedOrcaExtras
                        ?.let { addAll(stateStore.saveExtraOrcaSettings(it).rejectedKeys) }
                }
            }
        }.onFailure {
            showOperationFailure(it)
            return
        }.getOrDefault(emptyList()) + blockedExtras
        importedSettingsBaseline = pending.settings
        importedScene = null
        val prusaSnapshot = pending.prusaSettings != null || pending.prusaStartGcode != null
        _uiState.update { current ->
            current.withoutPublishedSlice().copy(
                settings = pending.settings,
                startGcode = pending.startGcode,
                endGcode = pending.endGcode,
                prusaSettings = pending.prusaSettings ?: current.prusaSettings,
                prusaStartGcode = pending.prusaStartGcode ?: current.prusaStartGcode,
                prusaEndGcode = pending.prusaEndGcode ?: current.prusaEndGcode,
                extraPrusaSettings = acceptedPrusaExtras ?: current.extraPrusaSettings,
                extraCuraSettings = acceptedCuraExtras ?: current.extraCuraSettings,
                orcaSettings = pending.orcaSettings ?: current.orcaSettings,
                extraOrcaSettings = acceptedOrcaExtras ?: current.extraOrcaSettings,
                importedRawSettingCount = SlicerSettingsJson.allKeys.size,
                curaVersion = null,
                settingVersion = "27",
                engineProfile = null,
                importedSceneTransformAvailable = false,
                importedSceneModelName = null,
                // The store's snapshot baseline carries this name, and the workspace
                // fingerprint is taken from the state: a stale name here would make the
                // restored workspace look like it was saved under other settings.
                profileName = pending.sourceName,
                profileSource = "Configuration snapshot",
                isBusy = false,
                statusMessage = "Imported ${pending.sourceName}; settings and custom G-code are active until overridden" +
                    (if (prusaSnapshot) "; Prusa state restored" else "") +
                    (if (rejectedExtras.isEmpty()) {
                        ""
                    } else {
                        "; not saved (the engine cannot take " +
                            rejectedExtras.sorted().joinToString(", ") + ")"
                    }),
            )
        }
        // The model's workspace record was saved under the settings that were active
        // when the model was imported. Re-persist it under the imported ones, or the
        // next launch sees a fingerprint mismatch and drops the model from the plate.
        withContext(Dispatchers.IO) { persistCurrentWorkspace(_uiState.value) }
    }

    /** Placement changes this session, newest last, for taking one back. */
    private val placementHistory = PlacementHistory()

    /**
     * Puts the model back where it was before the last placement change.
     *
     * Covers everything that moves the model, because they all come through
     * changePlacement: the gizmo's drags and arrows, the typed fields, drop, lay
     * flat, reset and an imported scene transform.
     */
    fun undoPlacement() {
        val step = placementHistory.undo() ?: return
        changePlacement("Undid " + step.label, recordHistory = false) { _, _ -> step.placement }
    }

    private fun changePlacement(
        message: String,
        recordHistory: Boolean = true,
        transform: (ModelPlacement, StlMesh) -> ModelPlacement,
    ) {
        val original = sourceMesh
        val stateSnapshot = _uiState.value
        val current = stateSnapshot.modelPlacement
        // Every placement change belongs to the selected object: the mesh it is computed from is
        // that object's, and so is the envelope it has to stay inside.
        val selectedId = stateSnapshot.selectedModel?.id
        if (original == null || current == null || selectedId == null) {
            showOperationFailure(IllegalStateException("Import an STL before changing model placement"))
            return
        }
        if (!beginOperation("Updating model placement…")) return
        viewModelScope.launch {
            runCatching {
                val prepared = withContext(Dispatchers.Default) {
                    val changed = transform(current, original)
                    val transformed = changed.transformed(original)
                    // Reject placements that leave the model hanging off the
                    // build volume or above the printer height so the user gets
                    // immediate feedback instead of a slice-time failure.
                    PrinterEnvelope.from(printer.withSettings(stateSnapshot.settings)).requireModelFits(transformed)
                    changed to transformed
                }
                // The save has to describe the plate as it will be, not as it was: the state
                // update below is what the next launch has to find.
                val changed = prepared.first
                val nextModels = stateSnapshot.models.map { model ->
                    if (model.id == selectedId) model.withPlacement(changed) else model
                }
                val nextSnapshot = workspaceSnapshot(stateSnapshot.copy(models = nextModels))
                withContext(Dispatchers.IO) { nextSnapshot?.let(workspaceStore::save) }
                prepared
            }.onSuccess { (changed, _) ->
                if (recordHistory) placementHistory.record(message, current)
                _uiState.update { state ->
                    state.withoutPublishedSlice()
                        .withModel(selectedId) { model -> model.withPlacement(changed) }
                        .copy(
                            canUndoPlacement = placementHistory.canUndo,
                            undoPlacementLabel = placementHistory.nextLabel,
                            isBusy = false,
                            statusMessage = "$message; slice again to export G-code",
                        )
                }
            }.onFailure(::showOperationFailure)
        }
    }

    fun deferUntilRestoreCompletes(action: () -> Unit): Boolean = synchronized(deferredRestoreActions) {
        if (!restoringPersistedState) {
            false
        } else {
            deferredRestoreActions.addLast(action)
            true
        }
    }

    private fun finishRestoreAndReplayResults() {
        val actions = synchronized(deferredRestoreActions) {
            restoringPersistedState = false
            val pending = deferredRestoreActions.toList()
            deferredRestoreActions.clear()
            pending
        }
        if (actions.isEmpty()) return
        viewModelScope.launch {
            actions.forEach { action ->
                if (_uiState.value.isBusy) uiState.first { state -> !state.isBusy }
                action()
                if (_uiState.value.isBusy) uiState.first { state -> !state.isBusy }
            }
        }
    }

    private fun restorePersistedState() {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // The destination is kept: an interrupted export may already
                    // hold the whole document, so it is reported rather than
                    // deleted. Resolve its name here, where a content query is off
                    // the main thread and a URI the process no longer holds a grant
                    // for cannot fail the restore.
                    val interruptedExportName = pendingExportStore.recover()?.let { destination ->
                        runCatching { displayName(destination) }.getOrDefault("the selected file")
                    }
                    val saved = stateStore.savedImport()
                    val config = saved?.let { persisted ->
                        val parsed = persisted.file.inputStream().use { input ->
                            when (persisted.kind) {
                                AppStateStore.KIND_PROJECT -> CuraProjectParser.parse(input, persisted.displayName, SlicerSettings())
                                AppStateStore.KIND_PROFILE -> CuraProfileParser.parse(input, persisted.displayName, SlicerSettings())
                                else -> error("Unknown persisted Cura import kind: ${persisted.kind}")
                            }
                        }
                        CuraImportedSettingsResolver.resolveForUi(
                            config = parsed,
                            printer = printer,
                            fallbackStartGcode = initialStartGcode,
                            fallbackEndGcode = initialEndGcode,
                        )
                    }
                    val snapshotBaseline = if (saved == null) stateStore.snapshotBaseline() else null
                    val scene = saved?.takeIf { it.kind == AppStateStore.KIND_PROJECT }
                        ?.file
                        ?.inputStream()
                        ?.use(CuraProjectSceneParser::parse)
                    val baseSettings = config?.mappedSettings ?: snapshotBaseline?.settings ?: SlicerSettings()
                    val settings = stateStore.restoreSettings(baseSettings).withRecomputedDerived()
                    val effectiveStartGcode = config?.startGcode ?: snapshotBaseline?.startGcode ?: initialStartGcode
                    val effectiveEndGcode = config?.endGcode ?: snapshotBaseline?.endGcode ?: initialEndGcode
                    val effectiveProfileName = config?.name
                        ?: snapshotBaseline?.profileName
                        ?: "Built-in current Cura settings"
                    val effectiveProfileSource = config?.source
                        ?: snapshotBaseline?.profileSource
                        ?: "Cura 5.14.0-alpha.0 / setting version 27 reference"
                    val fingerprint = workspaceFingerprint(
                        config,
                        settings,
                        effectiveStartGcode,
                        effectiveEndGcode,
                        effectiveProfileName,
                        effectiveProfileSource,
                    )
                    // The fingerprint is the guard the workspace store keeps: a workspace
                    // saved under other settings must not put its model and placement on
                    // screen under the settings being restored now. A mismatch skips the
                    // workspace instead of half-restoring it, and says so below.
                    val workspaceLoad = runCatching { workspaceStore.load() }
                    val savedWorkspace = workspaceLoad.getOrNull()
                    if (workspaceLoad.isFailure) {
                        // Unreadable, not merely out of date. The descriptor is cleared so it
                        // cannot fail again on every launch, and the user is told below.
                        Diagnostics.failure("workspace", workspaceLoad.exceptionOrNull()!!)
                        runCatching { workspaceStore.clear() }
                    }
                    val workspaceMatches = savedWorkspace?.configurationFingerprint == fingerprint
                    val workspace = savedWorkspace
                        ?.takeIf { workspaceMatches }
                        ?.let { snapshot ->
                            runCatching {
                                // A descriptor written before multi-object printing has no
                                // models list; its single model is the whole plate.
                                val entries = snapshot.models.ifEmpty {
                                    listOf(
                                        WorkspaceStateStore.Entry(
                                            modelPath = snapshot.modelPath,
                                            modelDisplayName = snapshot.modelDisplayName,
                                            placement = snapshot.placement,
                                            supportPaint = snapshot.supportPaint,
                                        ),
                                    )
                                }
                                val objects = entries.mapNotNull { entry ->
                                    runCatching {
                                        val source = StlParser.parse(
                                            file = File(entry.modelPath),
                                            displayName = entry.modelDisplayName,
                                            maxTriangles = MeshTriangleLimits.current(),
                                        )
                                        RestoredPlateObject(
                                            source = source,
                                            transformed = entry.placement.transformed(source),
                                            placement = entry.placement,
                                            supportPaint = entry.supportPaint,
                                            path = entry.modelPath,
                                            name = entry.modelDisplayName,
                                        )
                                    }.getOrNull()
                                }
                                RestoredWorkspace(snapshot = snapshot, objects = objects)
                            }.getOrNull()?.takeIf { it.objects.isNotEmpty() }
                        }
                    val skippedWorkspaceName = savedWorkspace
                        ?.takeIf { !workspaceMatches }
                        ?.modelDisplayName
                    RestoredImport(
                        config = config,
                        settings = settings,
                        scene = scene,
                        workspace = workspace,
                        startGcode = effectiveStartGcode,
                        endGcode = effectiveEndGcode,
                        profileName = effectiveProfileName,
                        profileSource = effectiveProfileSource,
                        baselineSettings = snapshotBaseline?.settings,
                        interruptedExportName = interruptedExportName,
                        skippedWorkspaceName = skippedWorkspaceName,
                        workspaceUnreadable = workspaceLoad.isFailure,
                    )
                }
            }

            result.onSuccess { restored ->
                importedScene = restored.scene
                if (restored.config == null) {
                    importedSettingsBaseline = restored.baselineSettings
                    _uiState.update {
                        it.copy(
                            settings = restored.settings,
                            startGcode = restored.startGcode,
                            endGcode = restored.endGcode,
                            profileName = restored.profileName,
                            profileSource = restored.profileSource,
                            importedSceneTransformAvailable = restored.scene?.affine != null,
                            importedSceneModelName = restored.scene?.modelName,
                            isBusy = false,
                            statusMessage = if (restored.settings.overriddenSettingKeys.isEmpty()) {
                                "Import an STL to begin"
                            } else {
                                "Restored ${restored.settings.overriddenSettingKeys.size} saved app setting overrides"
                            },
                        )
                    }
                } else {
                    applyImportedConfig(
                        config = restored.config,
                        settings = restored.settings,
                        scene = restored.scene,
                        statusMessage = "Restored ${restored.config.name} and ${restored.settings.overriddenSettingKeys.size} app overrides",
                    )
                }
                restoreWorkspace(restored.workspace)
                restored.skippedWorkspaceName?.let { name ->
                    _uiState.update {
                        it.copy(
                            statusMessage = "Settings changed since $name was saved; " +
                                "the workspace was not restored - import the model again",
                        )
                    }
                }
                if (restored.workspaceUnreadable) {
                    _uiState.update {
                        it.copy(
                            statusMessage = "The saved plate could not be read and was not " +
                                "restored - import the models again",
                        )
                    }
                }
                // Last, so the restore's own message cannot replace it: what the
                // user has to check is whether that document is complete.
                restored.interruptedExportName?.let { name ->
                    _uiState.update {
                        it.copy(statusMessage = "An export to $name did not finish; the file was kept")
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Saved Cura configuration could not be restored: ${error.message}",
                    )
                }
            }
            finishRestoreAndReplayResults()
        }
    }

    private fun restoreWorkspace(workspace: RestoredWorkspace?) {
        if (workspace == null) return
        val snapshot = workspace.snapshot
        // Names are made unique when a model is imported; a descriptor written before that - or
        // by hand - can still hold two parts with one name, which the engines then label
        // identically and a Klipper host treats as one cancellable object.
        val seenNames = HashSet<String>()
        val objects = workspace.objects.map { restored ->
            val model = restored.toPlateObject()
            val unique = if (seenNames.add(model.name)) {
                model.name
            } else {
                generateSequence(2) { it + 1 }
                    .map { "${model.name} ($it)" }
                    .first { seenNames.add(it) }
            }
            model.copy(name = unique)
        }
        _uiState.update { current ->
            current.withoutPublishedSlice()
                .withModels(objects)
                .copy(
                    isBusy = false,
                    statusMessage = if (objects.size > 1) {
                        "Restored ${objects.size} models on the plate; slice again to create validated G-code"
                    } else {
                        "Restored ${snapshot.modelDisplayName} workspace; slice again to create validated G-code"
                    },
                )
        }
    }

    /** The descriptor for the plate as the state already holds it. */
    private fun workspaceSnapshot(state: MainUiState): WorkspaceStateStore.Snapshot? {
        val selected = state.selectedModel ?: return null
        val path = selected.sourcePath ?: return null
        return WorkspaceStateStore.Snapshot(
            modelPath = path,
            modelDisplayName = selected.name,
            placement = selected.placement,
            configurationFingerprint = workspaceFingerprint(state),
            supportPaint = selected.supportPaint,
            models = state.models.mapNotNull { it.toWorkspaceEntry() },
        )
    }

    /**
     * The descriptor for an import that has not reached the state yet.
     *
     * The save runs before the state update, so a workspace that cannot be written fails the
     * import rather than leaving a model on the plate that the next launch would not restore.
     */
    private fun workspaceSnapshot(
        source: StlMesh,
        modelFile: File,
        placement: ModelPlacement,
        paint: SupportPaintState,
        state: MainUiState,
    ): WorkspaceStateStore.Snapshot = WorkspaceStateStore.Snapshot(
        modelPath = modelFile.absolutePath,
        modelDisplayName = source.displayName,
        placement = placement,
        configurationFingerprint = workspaceFingerprint(state),
        supportPaint = paint,
        models = state.models.mapNotNull { it.toWorkspaceEntry() } + WorkspaceStateStore.Entry(
            modelPath = modelFile.absolutePath,
            modelDisplayName = source.displayName,
            placement = placement,
            supportPaint = paint,
        ),
    )

    private fun PlateObject.toWorkspaceEntry(): WorkspaceStateStore.Entry? {
        val path = sourcePath ?: return null
        return WorkspaceStateStore.Entry(
            modelPath = path,
            modelDisplayName = name,
            placement = placement,
            supportPaint = supportPaint,
        )
    }

    private fun workspaceFingerprint(state: MainUiState): String = WorkspaceStateStore.fingerprint(
        state.profileName,
        state.profileSource,
        state.curaVersion,
        state.settingVersion,
        state.settings,
        state.startGcode,
        state.endGcode,
    )

    private fun workspaceFingerprint(
        config: ImportedCuraConfig?,
        settings: SlicerSettings,
        startGcode: String,
        endGcode: String,
        profileName: String,
        profileSource: String,
    ): String = WorkspaceStateStore.fingerprint(
        config?.name ?: profileName,
        config?.source ?: profileSource,
        config?.curaVersion,
        config?.settingVersion ?: "27",
        settings,
        startGcode,
        endGcode,
    )

    private fun persistSettings(settings: SlicerSettings, generation: Long) {
        val stateSnapshot = _uiState.value.copy(settings = settings)
        val previousWrite = settingsPersistenceJob
        settingsPersistenceJob = viewModelScope.launch(Dispatchers.IO) {
            previousWrite?.join()
            val settingsCommitted = stateStore.saveSettings(settings)
            if (settingsCommitted && workspaceMutationGeneration.get() == generation) {
                persistCurrentWorkspace(stateSnapshot)
            }
            if (!settingsCommitted) {
                _uiState.update {
                    it.copy(statusMessage = "Settings could not be saved; they will not survive a restart")
                }
            }
        }
    }

    /**
     * Saves the plate as it is now, off the main thread.
     *
     * An import saves before it touches the state, so a save that fails fails the import. The
     * arrangement that follows that save then moves every object on the plate - including the
     * ones that were already there - so without this second write the descriptor describes the
     * layout from before the new part was placed, and a relaunch inside that window restores it.
     */
    /**
     * An import has landed: the plate is not the one the placement undo was recorded against, and
     * the descriptor has to describe the arranged plate rather than the pre-arrangement one.
     */
    private fun onPlateImported() {
        placementHistory.clear()
        persistPlateSoon()
    }

    private fun persistPlateSoon() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { persistCurrentWorkspace(_uiState.value) } }
        }
    }

    private fun persistCurrentWorkspace(state: MainUiState) {
        // Every model on the plate is saved, not just the selected one: rearranging one object
        // must not drop the others from the workspace the next launch restores.
        val snapshot = workspaceSnapshot(state) ?: return
        workspaceStore.save(snapshot)
    }

    private fun stageAndParseImport(
        uri: Uri,
        kind: String,
        sourceName: String,
        parseScene: ((File) -> CuraProjectScene?)? = null,
        parse: (File) -> ImportedCuraConfig,
    ): PendingImport {
        val staged = app.contentResolver.openInputStream(uri)?.use(stateStore::stageImport)
            ?: error("Unable to open the selected Cura file")
        return try {
            PendingImport(
                config = parse(staged),
                stagedFile = staged,
                kind = kind,
                displayName = sourceName,
                scene = parseScene?.invoke(staged),
            )
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
    }

    private suspend fun commitImportedConfig(pending: PendingImport) {
        val pendingSettingsWrite = settingsPersistenceJob
        runCatching {
            withContext(Dispatchers.IO) {
                pendingSettingsWrite?.join()
                stateStore.commitImport(pending.stagedFile, pending.kind, pending.displayName)
                stateStore.clearSavedSettings()
                stateStore.clearSnapshotBaseline()
            }
        }.onFailure {
            showOperationFailure(it)
            return
        }
        importedScene = pending.scene
        val resolvedConfig = withContext(Dispatchers.Default) {
            CuraImportedSettingsResolver.resolveForUi(
                config = pending.config,
                printer = printer,
                fallbackStartGcode = initialStartGcode,
                fallbackEndGcode = initialEndGcode,
            )
        }
        val baseline = resolvedConfig.mappedSettings.copy(overriddenSettingKeys = emptySet())
        withContext(Dispatchers.IO) { stateStore.saveSettings(baseline) }
        applyImportedConfig(
            config = resolvedConfig,
            settings = baseline,
            scene = pending.scene,
            statusMessage = null,
        )
    }

    private suspend fun applyImportedConfig(
        config: ImportedCuraConfig,
        settings: SlicerSettings,
        scene: CuraProjectScene?,
        statusMessage: String?,
    ) {
        importedSettingsBaseline = config.mappedSettings.copy(overriddenSettingKeys = emptySet())
        importedScene = scene
        val original = sourceMesh
        val autoPlacement = if (
            original != null && scene?.affine != null && modelNamesMatch(scene.modelName, original.displayName)
        ) {
            ModelPlacement.from3mf(original, scene.affine, scene.dropToBuildPlate)
        } else {
            null
        }
        val transformed = if (autoPlacement != null && original != null) {
            withContext(Dispatchers.Default) { autoPlacement.transformed(original) }
        } else {
            null
        }
        _uiState.update { current ->
            val concreteCount = config.engineProfile?.concreteSettingCount ?: config.rawValues.size
            val definitionLabel = if (config.engineProfile?.usesProjectDefinitions == true) {
                " with project machine/extruder definitions"
            } else {
                ""
            }
            val mismatchWarning = if (
                original != null && scene?.affine != null && !modelNamesMatch(scene.modelName, original.displayName)
            ) {
                "Imported Cura transform is for ${scene.modelName ?: "another model"}; use Model position & rotation to apply it manually"
            } else {
                null
            }
            val auditWarnings = CuraProjectAudit.warnings(config.rawValues)
            val warnings = (config.warnings + scene?.warnings.orEmpty() + auditWarnings + listOfNotNull(mismatchWarning)).distinct()
            // The transformed mesh was computed off the main thread above; the scene transform
            // names one model, and that is the selected one.
            val selectedId = current.selectedModel?.id
            val placedModels = if (autoPlacement != null && transformed != null && selectedId != null) {
                current.models.map { model ->
                    if (model.id == selectedId) model.withPlacement(autoPlacement) else model
                }
            } else {
                current.models
            }
            current.withoutPublishedSlice().copy(
                settings = settings,
                profileName = config.name,
                profileSource = config.source,
                importedRawSettingCount = concreteCount,
                curaVersion = config.curaVersion,
                settingVersion = config.settingVersion ?: "27",
                engineProfile = config.engineProfile,
                startGcode = config.startGcode ?: initialStartGcode,
                endGcode = config.endGcode ?: initialEndGcode,
                models = placedModels,
                importedSceneTransformAvailable = scene?.affine != null,
                importedSceneModelName = scene?.modelName,
                warnings = warnings,
                statusMessage = statusMessage
                    ?: buildString {
                        append("Imported $concreteCount concrete Cura values$definitionLabel")
                        if (autoPlacement != null) append(" and applied the matching scene transform")
                        append("; imported values remain active until overridden")
                    },
            )
        }
        withContext(Dispatchers.IO) { persistCurrentWorkspace(_uiState.value) }
        _uiState.update { it.copy(isBusy = false) }
    }

    private data class ImportedModel(
        val mesh: StlMesh,
        val modelFile: File,
        val paint: SupportPaintState,
    )

    /** A fresh staging path for a model that arrived in another format. */
    private fun stagedModelFile(): File =
        File(File(app.filesDir, "models").apply { mkdirs() }, "model-${System.nanoTime()}.stl")

    private fun materializeModel(uri: Uri, maxTriangles: Int, extension: String): File {
        val directory = File(app.filesDir, "models").apply { mkdirs() }
        val target = File(directory, "model-${System.nanoTime()}.$extension")
        val temporary = File(directory, "${target.name}.tmp")
        val maxBytes = MeshTriangleLimits.maxInputFileBytes(maxTriangles)
        temporary.delete()
        try {
            app.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) {
                            "The model is larger than ${MeshTriangleLimits.formatBytes(maxBytes)} for the ${MeshTriangleLimits.formatCount(maxTriangles)}-triangle limit"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Unable to copy the selected model")
            check(temporary.length() > 0L) { "The selected model is empty" }
            check(temporary.renameTo(target) || temporary.copyTo(target, overwrite = false).let { temporary.delete(); true }) {
                "Unable to store the selected model locally"
            }
            return target
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    private fun modelNamesMatch(projectName: String?, stlName: String): Boolean {
        if (projectName.isNullOrBlank()) return false
        fun normalize(value: String): String = value
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBeforeLast('.', value)
            .lowercase()
            .filter(Char::isLetterOrDigit)
        return normalize(projectName) == normalize(stlName)
    }

    /**
     * Claims the single long-operation slot, atomically.
     *
     * BlenderEngine invokes its export callback on Dispatchers.IO, so a model handoff
     * and a slice can reach this check at the same moment. A plain check-then-set let
     * both through, and the slice then published G-code for the model that was on the
     * plate when it started.
     */
    private fun beginOperation(message: String): Boolean = synchronized(operationLock) {
        if (_uiState.value.isBusy) {
            // Controls stay live while an engine works, so a refusal has to say something: a tap
            // that changes nothing and says nothing reads as a broken button.
            _uiState.update { it.copy(statusMessage = "An operation is still running; try again in a moment") }
            return false
        }
        workspaceMutationGeneration.incrementAndGet()
        _uiState.update { it.copy(isBusy = true, statusMessage = message, sliceProgressPercent = null) }
        true
    }

    /**
     * An export that arrived while the app was busy; taken when it is free.
     *
     * Written from BlenderEngine's IO callback and drained on the main thread, so
     * the swap is guarded: a plain check-then-null dropped a write that landed
     * between the two statements, and the engine had already claimed that export.
     */
    // A queue, not one slot: one "All to plate" writes one file per shape and the watcher hands
    // them over back to back. The second export used to overwrite the first, and the watcher
    // claims a file before it calls, so the overwritten one was never offered again.
    private val pendingEngineImports = ArrayDeque<Pair<File, String>>()
    private val pendingEngineImportLock = Any()

    /** Holds a claimed export with the engine it came from, so the drain can name it right. */
    private fun queueEngineImport(file: File, engine: String) {
        synchronized(pendingEngineImportLock) { pendingEngineImports.addLast(file to engine) }
    }

    /** Called wherever the app goes idle, so claimed exports are not lost. */
    private fun drainPendingEngineImport() {
        val next = synchronized(pendingEngineImportLock) { pendingEngineImports.removeFirstOrNull() }
            ?: return
        importEngineStl(next.first, next.second)
    }

        /**
     * Copies the loaded model into the embedded Blender engine's import
     * directory so it can be opened and modified there; the engine's existing
     * export handoff brings the result back into the slicer.
     */
    fun sendModelToBlender() {
        val path = _uiState.value.modelPath
        if (path.isNullOrBlank()) {
            showOperationFailure(IllegalStateException("Import a model first"))
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // The engine may have been stopped explicitly, or the keeper
                    // may have been reclaimed while the app was in the background.
                    BlenderEngine.ensureStarted(app)
                    BlenderEngineService.start(app)
                    val target = BlenderModelHandoff.publish(
                        blenderRoot = File(app.filesDir, "blender"),
                        source = File(path),
                    )
                    // Copying a file into the import directory is not the same
                    // as opening it: the engine went on holding whatever it had,
                    // and the modelling view - which shows the engine's own
                    // scene - quite correctly kept showing the default cube
                    // after a model had been sent. Load it.
                    //
                    // Inside withContext(IO) deliberately. On the main thread
                    // this is a NetworkOnMainThreadException, which runCatching
                    // then swallows: the import failed silently, which is how it
                    // behaved the first time.
                    val loaded = runCatching {
                        val blenderDir = File(app.filesDir, "blender")
                        // The token file too: the engine refuses anything else, so a
                        // client without it retried this import for two minutes and
                        // then reported that the engine would not load the model.
                        EnginePreviewClient(tokenFile = BlenderEngine.tokenFile(blenderDir))
                            .use { it.importModelWhenReady(target, blenderDir) }
                    }.getOrDefault(false)
                    target to loaded
                }
            }.onSuccess { (target, loaded) ->
                _uiState.update {
                    it.copy(
                        statusMessage = "Sent " + File(path).name +
                            " to Blender (" + target.parentFile?.name + "/" + target.name + ")" +
                            if (loaded) ", loaded into the engine" else " - engine did not load it",
                    )
                }
            }.onFailure(::showOperationFailure)
        }
    }

    private fun showOperationFailure(error: Throwable) {
        if (error is CancellationException) {
            _uiState.update { it.copy(isBusy = false, statusMessage = "Operation cancelled") }
            throw error
        }
        // Every operation failure in the app arrives here, which is why the log
        // can be a faithful record without a call at each of the call sites.
        Diagnostics.failure("operation", error)
        _uiState.update { current ->
            current.copy(
                isBusy = false,
                statusMessage = error.message ?: error::class.java.simpleName,
            )
        }
    }
    private fun showSliceFailure(error: Throwable) {
        if (error is CancellationException) {
            _uiState.update { it.copy(isBusy = false, statusMessage = "Slice cancelled") }
            throw error
        }
        Diagnostics.failure("slice", error)
        _uiState.update { current ->
            current.copy(
                isBusy = false,
                sliceResultId = null,
                gcodePath = null,
                baseGcodePath = null,
                layerPreview = null,
                layerEvents = emptyList(),
                estimatedPrintSeconds = null,
                sliceLogPath = (error as? CuraEngineRunner.SliceException)?.logFile?.absolutePath
                    ?: (error as? PrusaEngineRunner.SliceException)?.logFile?.absolutePath
                    ?: (error as? OrcaEngineRunner.SliceException)?.logFile?.absolutePath
                    ?: current.sliceLogPath,
                statusMessage = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun showEventFailure(error: Throwable) = showOperationFailure(error)

    private fun retainReadPermission(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun displayName(uri: Uri): String {
        return app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "imported file"
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes bytes"
    }

    private fun formatDuration(milliseconds: Long): String = when {
        milliseconds >= 60_000L -> "%.1f min".format(milliseconds / 60_000.0)
        milliseconds >= 1_000L -> "%.1f s".format(milliseconds / 1_000.0)
        else -> "$milliseconds ms"
    }

    private fun configurationJson(state: MainUiState): JSONObject {
        val settings = state.settings
        val placement = state.modelPlacement
        return JSONObject()
            .put("format", CONFIG_SNAPSHOT_FORMAT)
            .put("version", CONFIG_SNAPSHOT_VERSION)
            .put("printer", state.printer.name)
            .put("profileName", state.profileName)
            .put("profileSource", state.profileSource)
            .put("curaVersion", state.curaVersion)
            .put("settingVersion", state.settingVersion)
            .put("importedValues", state.importedRawSettingCount)
            .put("appOverrideKeys", settings.overriddenSettingKeys.sorted())
            .put("estimatedPrintSeconds", state.estimatedPrintSeconds)
            .put("maxMeshTriangles", MeshTriangleLimits.current())
            .put("layerEvents", state.layerEvents.map { event ->
                JSONObject()
                    .put("layer", event.layerNumber)
                    .put("zMm", event.zMm)
                    .put("type", event.type.name)
                    .put("value", event.value)
                    .put("secondaryValue", event.secondaryValue)
                    .put("text", event.text)
                    .put("source", event.source.name)
            })
            .put("warnings", state.warnings)
            .put(
                "modelPlacement",
                placement?.let {
                    JSONObject()
                        .put("source", it.source)
                        .put("centerXmm", it.centerXmm)
                        .put("centerYmm", it.centerYmm)
                        .put("baseZmm", it.baseZmm)
                        .put("linear", it.linear)
                },
            )
            .put("settings", SlicerSettingsJson.serialize(settings))
            .put("startGcode", state.startGcode)
            .put("endGcode", state.endGcode)
            .put("prusaSettings", PrusaSliceSettingsJson.serialize(state.prusaSettings))
            .put("prusaStartGcode", state.prusaStartGcode)
            .put("prusaEndGcode", state.prusaEndGcode)
            .put("extraPrusaSettings", JSONObject(state.extraPrusaSettings))
            .put("extraCuraSettings", JSONObject(state.extraCuraSettings))
            .put("orcaSettings", OrcaSliceSettingsJson.serialize(state.orcaSettings))
            .put("extraOrcaSettings", JSONObject(state.extraOrcaSettings))
    }

    private fun parseStringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = json.optString(key, "")
        }
        return result
    }
}
