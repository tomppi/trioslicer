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
import com.tomppi.enderslicer.viewer.BedClipper
import com.tomppi.enderslicer.viewer.MeshBoolean
import com.tomppi.enderslicer.viewer.MeshPicker
import com.tomppi.enderslicer.viewer.MeshVolume
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.PaintedMeshWriter
import com.tomppi.enderslicer.viewer.PlateThreeMfWriter
import com.tomppi.enderslicer.viewer.SnapFacing
import com.tomppi.enderslicer.viewer.SnapFit
import com.tomppi.enderslicer.viewer.SnapFitFrame
import com.tomppi.enderslicer.viewer.SnapFitGate
import com.tomppi.enderslicer.viewer.SnapFitRung
import com.tomppi.enderslicer.viewer.SnapFitHalf
import com.tomppi.enderslicer.viewer.SnapFitParameters
import com.tomppi.enderslicer.viewer.SnapJoint
import com.tomppi.enderslicer.viewer.SnapLayout
import com.tomppi.enderslicer.viewer.SnapPad
import com.tomppi.enderslicer.viewer.SnapSpread
import com.tomppi.enderslicer.viewer.SnapTightness
import com.tomppi.enderslicer.viewer.SolidSplitter
import com.tomppi.enderslicer.viewer.Vec3
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

/**
 * Kept between two parts on a sequentially printed plate, over [SequentialPrintCheck]'s own
 * head clearance: the check refuses a gap that only equals the sweep, and float placements are
 * not exact to the last bit.
 */
private const val SEQUENTIAL_HEAD_CLEARANCE_MARGIN_MM = 0.5
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

/**
 * How long a joint's inputs must sit still before the boolean runs.
 *
 * A slider reports every frame it moves, and each report is a union, a
 * subtraction and the mesh copies around them. Long enough to collapse a drag
 * into one operation, short enough that letting go feels like it did something.
 */
private const val SNAP_PREVIEW_DEBOUNCE_MILLIS = 220L

/**
 * The joint's scale slider: half-size to double.
 *
 * One control for the whole joint. [SnapFitParameters] multiplies every
 * dimension it has - the key, the beam, the barb and all three clearances - by
 * this, so there is nothing to tune per dimension and nothing that can be
 * forgotten at one setting and wrong at another. It is exposed to the panel
 * itself because the slider's range is the same value the view model clamps to.
 */
const val MIN_JOINT_SCALE = 0.5f
const val MAX_JOINT_SCALE = 2f

/** Said when a placement would leave the whole model under the bed. */
private const val ENTIRELY_BELOW_BED_MESSAGE =
    "The whole model is below the build plate; raise it so part of it is above Z=0 before slicing"

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
        /** The identity the descriptor saved, so a snap pair survives a relaunch. */
        val id: String?,
    ) {
        fun toPlateObject(): PlateObject = PlateObject(
            id = id ?: PlateObject.newId(),
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
     * The joint's booleans, one at a time and only after a pause.
     *
     * A slider reports every frame it moves, and each report would otherwise be
     * a union plus a subtraction over the whole half; the debounce collapses a
     * drag into the value it was let go at, and cancelling the running job is
     * what stops a stale result from landing on top of a newer one.
     */
    private var snapPreviewJob: Job? = null

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
                    // The plate is empty, so every joint and every previewed half the
                    // session holds belongs to an object that is gone.
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
                    ).withoutSnap()
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
        val platePreferences = preferences.sanitized()
        val slots = PlateArranger.arrange(
            footprints = models.map { model ->
                PlateFootprint(model.bounds.width.toDouble(), model.bounds.depth.toDouble())
            },
            bedWidthMm = usableWidth,
            bedDepthMm = usableDepth,
            spacingMm = platePreferences.spacingMm,
            // One object at a time is a plate the app's own check has to accept, and the
            // packer is the only thing that can leave room for the head: with the user's
            // spacing alone, every plate of two or more that Arrange had just laid out was
            // refused by Slice as too close for the print head. The margin is what keeps a
            // gap that lands exactly on the sweep from still counting as touching.
            neighborClearanceMm = if (platePreferences.sequential) {
                SequentialPrintCheck.headClearanceMm(settings) + SEQUENTIAL_HEAD_CLEARANCE_MARGIN_MM
            } else {
                0.0
            },
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
                        // The packer returns fresh objects at fresh placements, so a
                        // standing preview describes the plate that was just rearranged
                        // away, and the session goes with it.
                        current.withoutPublishedSlice(
                            "Arranged ${next.size} objects; slice again to export G-code",
                        ).copy(
                            models = next,
                            canUndoPlacement = false,
                            undoPlacementLabel = null,
                            isBusy = false,
                        ).withoutSnap()
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
                // The object that left may have been a half, so the session and its previewed
                // halves go; the pair itself stays, and says it is stale if one of them went.
                .withoutSnap()
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

    // --- Splitting a model in two -------------------------------------------

    /**
     * Opens or closes the cut preview over the model view.
     *
     * Opening puts the plane in the middle of the selected object's own reach on
     * the axis, which is a cut that always has two halves to look at; the mesh
     * is not touched either way.
     */
    fun setCutActive(active: Boolean) {
        _uiState.update { current ->
            if (!active) return@update current.copy(cutActive = false)
            val span = current.cutSpanMm ?: return@update current.copy(cutActive = false)
            current.copy(cutActive = true, cutOffsetMm = middleOf(span).toDouble())
        }
    }

    /**
     * Switches the cut between a cut from the top (Z) and the two side cuts.
     *
     * The plane is re-centred on the new axis: a number that meant something on
     * Z says nothing about where the part reaches on X, and keeping it would put
     * the plane outside the model on the axis just chosen.
     */
    fun setCutAxis(axis: ModelPlacement.Axis) {
        _uiState.update { current ->
            val span = current.spanAlong(axis)
            current.copy(
                cutAxis = axis,
                cutOffsetMm = span?.let { middleOf(it).toDouble() } ?: current.cutOffsetMm,
            )
        }
    }

    /** Moves the cut plane. The preview follows it; nothing is cut until [splitModel]. */
    fun setCutOffset(offsetMm: Double) {
        if (!offsetMm.isFinite()) return
        _uiState.update { current ->
            val span = current.cutSpanMm ?: return@update current
            current.copy(
                cutOffsetMm = offsetMm.coerceIn(
                    span.start.toDouble(),
                    span.endInclusive.toDouble(),
                ),
            )
        }
    }

    /**
     * Cuts the selected object at the previewed plane and puts both halves on
     * the plate, side by side and both standing on the bed.
     *
     * This is where the preview becomes geometry: [SolidSplitter] produces the
     * two meshes - with the mesh boolean where the engine takes the model, so
     * each half is a closed solid by construction, and with [BedClipper]'s
     * capped cut as the fallback - each half is staged as its own STL file (the workspace
     * descriptor restores an object from the file it names, so two objects
     * sharing the original's file would both come back whole), and the plate's
     * own packer lays the plate out again. The two halves are separate objects
     * the moment this returns, so they move, slice and export like any other
     * import.
     */
    fun splitModel() {
        val state = _uiState.value
        val model = state.selectedModel
        if (model == null) {
            showOperationFailure(IllegalStateException("Import an STL before splitting it"))
            return
        }
        if (!state.cutInsideModel) {
            showOperationFailure(
                IllegalStateException("Move the cut inside " + model.name + " before splitting it"),
            )
            return
        }
        if (!beginOperation("Splitting " + model.name + "…")) return
        val axis = state.cutAxis
        val offsetMm = state.cutOffsetMm
        val lowName = state.uniqueModelName(state.cutHalfNames.first)
        val highName = state.uniqueModelName(state.cutHalfNames.second)
        viewModelScope.launch {
            // Every file this call writes, so a failure before the plate is
            // committed leaves nothing orphaned behind.
            val staged = ArrayList<File>(2)
            runCatching {
                val cut = withContext(Dispatchers.Default) {
                    val placed = model.mesh
                    val offset = offsetMm.toFloat()
                    val split = SolidSplitter.split(placed, axis, offset)
                    check(split.low.triangleCount > 0 && split.high.triangleCount > 0) {
                        "The cut leaves nothing on one side; move it inside the model"
                    }
                    // Which of the two cuts ran is the one thing the user cannot
                    // see in the halves, and the one thing worth having in a bug
                    // report: the boolean makes closed solids, the clipper is
                    // the fallback for a model the engine would not take.
                    Diagnostics.info(
                        "split",
                        model.name + " on " + axis.name + " at " + offset + " mm via " + split.path +
                            ": low " + split.low.triangleCount + " tris, high " + split.high.triangleCount + " tris",
                    )
                    split
                }
                val halves = listOf(lowName to cut.low, highName to cut.high)
                val objects = withContext(Dispatchers.IO) {
                    halves.map { (name, half) ->
                        val file = stagedModelFile()
                        staged += file
                        StlMeshWriter.writeBinary(half.copy(displayName = name), file)
                        cutHalfObject(name, half, file, state)
                    }
                }
                val packed = withContext(Dispatchers.Default) {
                    val replaced = state.models.flatMap { existing ->
                        if (existing.id == model.id) objects else listOf(existing)
                    }
                    arranged(replaced, state.platePreferences, state.settings)
                        ?: throw IllegalStateException(
                            "The two halves do not fit on the bed " +
                                state.platePreferences.sanitized().spacingMm + " mm apart; " +
                                "nothing was split",
                        )
                }
                // The packer worked from the plate as it was. If anything moved on
                // while it ran, its layout is stale and must not be committed, and
                // the halves it was given are not the plate's halves either.
                val live = _uiState.value
                val unchanged = live.models.size == state.models.size &&
                    live.models.indices.all { live.models[it] === state.models[it] }
                if (!unchanged) {
                    runCatching { staged.forEach { it.delete() } }
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = "The plate changed while " + model.name +
                                " was being split; nothing was split",
                        )
                    }
                    return@runCatching
                }
                val low = objects.first()
                val commit = { current: MainUiState ->
                    current.withoutPublishedSlice(
                        "Split " + model.name + ": " + low.name + " and " +
                            objects.last().name + cutPathNote(cut.path) +
                            "; slice again to export G-code",
                    ).copy(
                        models = packed,
                        selectedModelId = low.id,
                        cutActive = false,
                        canUndoPlacement = false,
                        undoPlacementLabel = null,
                        isBusy = false,
                    ).afterPlateReplaced().withSnapPair(
                        // The split is what defines the assembly axis, so the two
                        // halves it just made are the pair the snap fit offers -
                        // recorded here, where their ids are in hand, rather than
                        // asked for again when the tool opens. The plane travels
                        // with them, in each half's own coordinates: the packer
                        // moves the halves apart, and a joint measured from where
                        // they landed would be built in the gap.
                        lowHalfId = low.id,
                        highHalfId = objects.last().id,
                        axis = axis,
                        lowFaceMm = offsetMm.toFloat(),
                        highFaceMm = offsetMm.toFloat(),
                    )
                }
                val descriptor = workspaceSnapshot(commit(_uiState.value))
                    ?: throw IllegalStateException("The split plate could not be saved")
                withContext(Dispatchers.IO) {
                    workspaceStore.save(descriptor)
                    // Nothing on the plate is restored from the original's file any
                    // more; it goes the way a removed object's file goes.
                    if (packed.none { it.sourcePath == model.sourcePath }) {
                        model.sourcePath?.let(::File)?.takeIf { it.isFile }?.delete()
                    }
                }
                // The plate the history was recorded against no longer exists: every step
                // in it is a placement of the whole model on the pre-split plate, and
                // undoing one would put that placement on a half. The commit drops the
                // button; this is what makes the forgotten step unreachable.
                placementHistory.clear()
                _uiState.update { commit(it) }
            }.onFailure { error ->
                runCatching { staged.forEach { it.delete() } }
                // Apply runs from the snap panel, so its failure belongs on the line the
                // panel shows, the same as its refusals do.
                showSnapFailure(error)
            }
        }
    }

    /**
     * The half-line a split's status message carries about how it was cut.
     *
     * The halves themselves do not say which path ran, and a report of a bad
     * split is worth exactly as much as that answer: the boolean is the path
     * that makes closed solids, so a split that fell back to the clipper is the
     * one to look at when a half will not take a joint.
     */
    private fun cutPathNote(path: SolidSplitter.Path): String = when (path) {
        SolidSplitter.Path.BOOLEAN -> " (cut by the mesh boolean)"
        SolidSplitter.Path.CLIPPER -> " (cut by the clipper: the boolean engine would not take this model)"
    }

    /**
     * One half as a plate object of its own.
     *
     * The cut mesh is already in build-plate coordinates, so the placement only
     * drops it onto the bed and centres it - [ModelPlacement.centeredOnBed] plus
     * [ModelPlacement.transformed] put its own minimum Z on the plate - and the
     * arranger then moves it beside its other half. Its file is its own, so the
     * workspace restores these two parts rather than two copies of the original.
     */
    private fun cutHalfObject(
        name: String,
        half: StlMesh,
        stagedFile: File,
        state: MainUiState,
    ): PlateObject {
        val placement = ModelPlacement.centeredOnBed(
            mesh = half,
            bedWidthMm = state.settings.machineWidthMm,
            bedDepthMm = state.settings.machineDepthMm,
            originAtCenter = state.settings.originAtCenter,
        )
        return PlateObject(
            id = PlateObject.newId(),
            name = name,
            sourceMesh = half,
            mesh = placement.transformed(half),
            sourcePath = stagedFile.absolutePath,
            placement = placement,
            supportPaint = SupportPaintState(),
        )
    }

    // --- The snap fit -------------------------------------------------------

    /**
     * Opens the Snap fit tool over the cut's own pair of halves.
     *
     * There is no pair to nominate: the split recorded the two objects and the
     * axis, and this only refuses when one of them has left the plate or the
     * boolean engine is not packaged for this device - both of which are things
     * a tap cannot fix.
     */
    fun setSnapActive(active: Boolean) {
        if (!active) {
            snapPreviewJob?.cancel()
            _uiState.update { it.withoutSnap() }
            return
        }
        val state = _uiState.value
        if (!state.snapAvailable) {
            showSnapFailure(
                IllegalStateException("Split a model first: the snap fit joins the two halves a split made"),
            )
            return
        }
        // Available only once the library has been asked for: the flag is about
        // a library that failed to load, not one that has not been loaded yet.
        MeshBoolean.ensureLoaded()
        if (!MeshBoolean.isAvailable) {
            showSnapFailure(
                IllegalStateException(
                    "The mesh boolean engine is not available on this device, so a joint cannot be made",
                ),
            )
            return
        }
        Diagnostics.info("snap fit", "opened over the split pair; Manifold " + MeshBoolean.engineVersion)
        _uiState.update {
            it.copy(
                snapActive = true,
                snapJoints = emptyList(),
                snapSelectedJoint = -1,
                snapMovingJoint = false,
                snapMixedFacing = false,
                snapFacing = SnapFacing.SAME,
                snapBarbs = 1,
                snapSocketRamp = false,
                snapScale = 1f,
                snapPreview = null,
                snapFailure = null,
            )
        }
    }

    /**
     * A tap on the model: it ADDS a joint to the pair, or moves the selected one
     * when the panel is waiting for a tap to move it to.
     *
     * The tap is a point on whatever surface the picker hit, which is almost
     * never exactly on the mating plane, so it is stored as tapped and read in
     * the tapped half's own coordinates wherever it is used. Which half matters:
     * the plate has moved the two apart since the split, so the same plate point
     * is a different spot in each half's frame, and the pair of point and half is
     * what keeps the features on the same physical place. [tappedHalfId] is that
     * half's object id, taken from the picker's own object index.
     *
     * A new joint takes the next clearance step by placement order - the first
     * loose, the next a step tighter - so a two-joint seam holds without having
     * to be told which one is which.
     */
    fun placeSnapJoint(point: Vec3, tappedHalfId: String? = null) {
        val state = _uiState.value
        if (!state.snapActive) return
        val moving = state.snapMovingJoint && state.snapSelectedSpec != null
        if (moving) {
            val index = state.snapSelectedJoint
            val moved = state.snapJoints[index].copy(anchorPointMm = point, anchorHalfId = tappedHalfId)
            val joints = state.snapJoints.toMutableList().also { it[index] = moved }
            _uiState.update {
                it.copy(snapJoints = joints, snapMovingJoint = false, snapFailure = null)
            }
        } else {
            val index = state.snapJoints.size
            val spec = SnapJointSpec(
                anchorPointMm = point,
                anchorHalfId = tappedHalfId,
                beamHalf = state.snapBeamHalf,
                tightness = if (index % 2 == 0) SnapTightness.LOOSE else SnapTightness.TIGHT,
                barbs = state.snapBarbs,
                facing = state.snapFacing,
            )
            _uiState.update {
                it.copy(
                    snapJoints = it.snapJoints + spec,
                    snapSelectedJoint = index,
                    snapFailure = null,
                )
            }
        }
        previewSnapJoint()
    }

    /** Which joint the panel's controls act on. */
    fun selectSnapJoint(index: Int) {
        if (index !in _uiState.value.snapJoints.indices) return
        _uiState.update { it.copy(snapSelectedJoint = index, snapFailure = null) }
    }

    /** Asks for the next tap to move the selected joint rather than add one. */
    fun moveSnapJoint(index: Int) {
        if (index !in _uiState.value.snapJoints.indices) return
        _uiState.update { it.copy(snapSelectedJoint = index, snapMovingJoint = true, snapFailure = null) }
    }

    /** Takes one joint off the pair; the others stay exactly as they are. */
    fun removeSnapJoint(index: Int) {
        if (index !in _uiState.value.snapJoints.indices) return
        _uiState.update { it.withoutSnapJoint(index) }
        previewSnapJoint()
    }

    /** Empties the seam: no joints, and nothing previewed. */
    fun clearSnapJoints() {
        snapPreviewJob?.cancel()
        _uiState.update {
            it.copy(
                snapJoints = emptyList(),
                snapSelectedJoint = -1,
                snapMovingJoint = false,
                snapPreview = null,
                snapFailure = null,
            )
        }
    }

    /** Loose or tight for one joint: clearance, and nothing else about it. */
    fun setSnapJointTightness(index: Int, tightness: SnapTightness) {
        val state = _uiState.value
        if (index !in state.snapJoints.indices) return
        val joints = state.snapJoints.toMutableList().also { it[index] = it[index].copy(tightness = tightness) }
        _uiState.update { it.copy(snapJoints = joints, snapFailure = null) }
        previewSnapJoint()
    }

    /** Which half carries one joint's beam. */
    fun setSnapJointBeamHalf(index: Int, half: SnapJoint.JointHalf) {
        val state = _uiState.value
        if (index !in state.snapJoints.indices) return
        val joints = state.snapJoints.toMutableList().also { it[index] = it[index].copy(beamHalf = half) }
        _uiState.update { it.copy(snapJoints = joints, snapBeamHalf = half, snapFailure = null) }
        previewSnapJoint()
    }

    /**
     * How many teeth a joint's beam carries: one, two or three.
     *
     * The stepper acts on the selected joint and becomes the default for the
     * next one, because a seam's material varies but the user's intent usually
     * does not.
     */
    fun setSnapBarbs(count: Int) {
        val barbs = count.coerceIn(1, SnapFit.MAX_BARBS)
        val state = _uiState.value
        val joints = if (state.snapSelectedJoint in state.snapJoints.indices) {
            state.snapJoints.toMutableList().also {
                it[state.snapSelectedJoint] = it[state.snapSelectedJoint].copy(barbs = barbs)
            }
        } else {
            state.snapJoints
        }
        _uiState.update { it.copy(snapJoints = joints, snapBarbs = barbs, snapFailure = null) }
        previewSnapJoint()
    }

    /**
     * Lets the joints face different ways, or puts them all back the same way.
     * Turning mixing OFF is the pair-level decision: every joint takes the
     * pair's own way again, so a mixed set is always something the user asked
     * for.
     */
    fun setSnapMixedFacing(mixed: Boolean) {
        val state = _uiState.value
        val facing = state.snapFacing
        val joints = if (mixed) {
            state.snapJoints
        } else {
            state.snapJoints.map { it.copy(facing = facing) }
        }
        _uiState.update { it.copy(snapJoints = joints, snapMixedFacing = mixed, snapFailure = null) }
        previewSnapJoint()
    }

    /**
     * Flips every joint on the seam together, which is the only flip there is
     * unless the user has explicitly asked to mix them.
     */
    fun flipSnapFacing() {
        val state = _uiState.value
        val facing = state.snapFacing.flipped()
        val joints = state.snapJoints.map { it.copy(facing = facing) }
        _uiState.update { it.copy(snapJoints = joints, snapFacing = facing, snapFailure = null) }
        previewSnapJoint()
    }

    /** One joint's own facing: only reachable while mixing is on. */
    fun setSnapJointFacing(index: Int, facing: SnapFacing) {
        val state = _uiState.value
        if (!state.snapMixedFacing || index !in state.snapJoints.indices) return
        val joints = state.snapJoints.toMutableList().also { it[index] = it[index].copy(facing = facing) }
        _uiState.update { it.copy(snapJoints = joints, snapFailure = null) }
        previewSnapJoint()
    }

    /** The socket mouth chamfer: on for every joint on the pair, or off for all. */
    fun setSnapSocketRamp(on: Boolean) {
        _uiState.update { it.copy(snapSocketRamp = on) }
        previewSnapJoint()
    }

    /**
     * The automatic spread: the app's proposal for where along the seam's own
     * rim a set of joints goes.
     *
     * It proposes and the user decides. Every proposed point is checked by
     * building the joint it would carry, so a point the material cannot take is
     * reported and left out rather than added and quietly refused later.
     */
    fun autoSpreadSnapJoints() {
        val state = _uiState.value
        if (!state.snapActive) return
        val lowFit = state.snapLowFitHalf
        val highFit = state.snapHighFitHalf
        if (lowFit == null || highFit == null) {
            _uiState.update { it.copy(snapFailure = SNAP_PAIR_STALE_MESSAGE) }
            return
        }
        val axes = SnapFit.frameAxes(SnapFit.axisDirection(state.snapAxis)) ?: return
        val direction = axes.first
        val side = axes.second
        val rise = axes.third
        val origin = state.snapSelectedSpec
            ?.let { spec -> state.snapAnchorHalfFor(spec)?.toLocal(spec.anchorPointMm) }
            ?: Vec3(lowFit.mesh.bounds.centerX, lowFit.mesh.bounds.centerY, lowFit.mesh.bounds.centerZ)
        val rim = SnapFit.seamRim(lowFit, highFit, direction, side, rise, origin)
        if (rim == null) {
            _uiState.update {
                it.copy(
                    snapFailure = "The automatic joints need the material the two halves share at the seam, " +
                        "and there is none to read here: tap a joint by hand.",
                )
            }
            return
        }
        val requested = if (state.snapFullJoint) SnapFitRung.FULL else null
        val probeSpec = SnapJointSpec(
            anchorPointMm = origin,
            anchorHalfId = state.snapLowHalfId,
            beamHalf = state.snapBeamHalf,
            tightness = SnapTightness.LOOSE,
            barbs = state.snapBarbs,
            facing = state.snapFacing,
        )
        val parameters = state.snapParametersFor(probeSpec)
        // A probe joint, built at the rim's own reference point, is what the
        // spacing is read from: the joint that will really stand there.
        val probe = SnapJoint.build(
            axis = state.snapAxis,
            anchorMm = origin,
            scale = state.snapScale,
            lowHalf = lowFit,
            highHalf = highFit,
            beamHalf = state.snapBeamHalf,
            parameters = parameters,
            requested = requested,
        )
        val probeJoint = when (probe) {
            is SnapJoint.Either.Placed -> probe.placement.joint
            is SnapJoint.Either.Flipped -> probe.placement.joint
            is SnapJoint.Either.Failed -> {
                _uiState.update { it.copy(snapFailure = "The automatic joints cannot start here: " + probe.failure.summary) }
                return
            }
        }
        val occupied = state.snapJoints.mapNotNull { spec ->
            state.snapAnchorHalfFor(spec)?.toLocal(spec.anchorPointMm)?.let(rim::plane)
        }
        val plan = SnapSpread.plan(rim, probeJoint, occupied, SnapSpread.MAX_JOINTS)
        val proposed = when (plan) {
            is SnapSpread.Plan.Refused -> {
                _uiState.update { it.copy(snapFailure = "Automatic joints: " + plan.reason) }
                return
            }
            is SnapSpread.Plan.Proposed -> plan
        }
        val lowHalfId = state.snapLowHalfId
        val added = ArrayList<SnapJointSpec>()
        val refused = ArrayList<String>()
        proposed.anchors.forEachIndexed { index, anchor ->
            val one = SnapJoint.build(
                axis = state.snapAxis,
                anchorMm = anchor,
                scale = state.snapScale,
                lowHalf = lowFit,
                highHalf = highFit,
                beamHalf = state.snapBeamHalf,
                parameters = parameters,
                requested = requested,
            )
            when (one) {
                is SnapJoint.Either.Failed -> refused += "point " + (index + 1) + ": " + one.failure.summary
                else -> {
                    val place = state.snapJoints.size + added.size
                    added += SnapJointSpec(
                        // The rim's points are in the halves' shared own
                        // coordinates; the low half carries them to the plate.
                        anchorPointMm = lowFit.toPlate(anchor),
                        anchorHalfId = lowHalfId,
                        beamHalf = state.snapBeamHalf,
                        tightness = if (place % 2 == 0) SnapTightness.LOOSE else SnapTightness.TIGHT,
                        barbs = state.snapBarbs,
                        facing = state.snapFacing,
                    )
                }
            }
        }
        if (added.isEmpty()) {
            _uiState.update { it.copy(snapFailure = "Automatic joints: nothing on this rim can take a joint: " + refused.joinToString("; ")) }
            return
        }
        val joints = state.snapJoints + added
        val notes = ArrayList<String>()
        proposed.note?.let { notes += it }
        if (refused.isNotEmpty()) notes += refused.size.toString() + " of the proposed points were left out: " + refused.joinToString("; ")
        _uiState.update {
            it.copy(
                snapJoints = joints,
                snapSelectedJoint = joints.size - 1,
                snapFailure = notes.takeIf { note -> note.isNotEmpty() }?.joinToString(". "),
            )
        }
        previewSnapJoint()
    }

    /** The one control every dimension of the joint follows, clearances included. */
    fun setSnapScale(scale: Float) {
        if (!scale.isFinite()) return
        _uiState.update { it.copy(snapScale = scale.coerceIn(MIN_JOINT_SCALE, MAX_JOINT_SCALE)) }
    }

    /**
     * The hook's own dimensions, in millimetres, each overriding its share of
     * the scale. They are stored without previewing - a slider that rebuilt the
     * booleans on every frame would stutter - and [commitSnapPreview] runs them
     * once the finger is up.
     */
    fun setSnapHookLength(millimetres: Float?) {
        if (millimetres != null && !millimetres.isFinite()) return
        _uiState.update { it.copy(snapHookLengthMm = millimetres) }
    }

    fun setSnapHookThickness(millimetres: Float?) {
        if (millimetres != null && !millimetres.isFinite()) return
        _uiState.update { it.copy(snapHookThicknessMm = millimetres) }
    }

    fun setSnapHookLip(millimetres: Float?) {
        if (millimetres != null && !millimetres.isFinite()) return
        _uiState.update { it.copy(snapHookLipMm = millimetres) }
    }

    /** Runs the preview for the settings the sliders have settled on. */
    fun commitSnapPreview() {
        previewSnapJoint()
    }

    /**
     * Which half the beam goes into for the selected joint; the other half gets
     * the matching pocket. With nothing selected it is the default the next
     * joint is placed with.
     */
    fun setSnapBeamHalf(half: SnapJoint.JointHalf) {
        val state = _uiState.value
        if (state.snapSelectedJoint in state.snapJoints.indices) {
            setSnapJointBeamHalf(state.snapSelectedJoint, half)
            return
        }
        _uiState.update { it.copy(snapBeamHalf = half) }
        previewSnapJoint()
    }

    /**
     * Pins the full joint, or hands the choice back to the material.
     *
     * Auto is the ladder: the seam's own cross-section decides whether the pad,
     * the key or only the cantilever is placed, and the panel says which and
     * why. Pinning Full skips those fit checks and builds the pad anyway - the
     * user is responsible for the result, and the panel tells him what is being
     * placed rather than blocking him.
     */
    fun setSnapFullJoint(full: Boolean) {
        _uiState.update { it.copy(snapFullJoint = full) }
        previewSnapJoint()
    }

    /**
     * Schedules the boolean preview after a pause.
     *
     * Debounced because the scale slider reports every frame it moves and each
     * report would otherwise be a union and a subtraction over the whole half -
     * the union alone measured under 2 ms on the phone, but the copies around it
     * are 11 ms, which is a stutter per frame. The pending job is cancelled, so
     * what lands is always the value the slider stopped at.
     */
    private fun previewSnapJoint() {
        snapPreviewJob?.cancel()
        if (!_uiState.value.snapActive) return
        // A child of the view model's own scope: the job this used to live in replaced the
        // view model's job with a fresh one, so onCleared never cancelled it and a boolean
        // could run on after the view model was gone.
        snapPreviewJob = viewModelScope.launch {
            delay(SNAP_PREVIEW_DEBOUNCE_MILLIS)
            runCatching { computeSnapPreview() }.onFailure(::showSnapFailure)
        }
    }

    /**
     * Runs the joint's two booleans in-process and puts the result on the plate.
     *
     * Everything is measured from the state as it is when this runs, not from
     * what was scheduled: the halves are the ones on the plate, and the preview
     * is dropped rather than committed if anything moved while the boolean ran.
     */
    private suspend fun computeSnapPreview() {
        val state = _uiState.value
        val specs = state.snapJoints
        if (specs.isEmpty()) {
            _uiState.update { it.copy(snapPreview = null, snapFailure = null) }
            return
        }
        val scale = state.snapScale
        val fullJoint = state.snapFullJoint
        val axis = state.snapAxis
        // The pair the split recorded may not be the plate any more: a half
        // deleted, a re-split, a restored workspace. The panel is told which,
        // in the same sentence Apply uses, rather than the preview quietly
        // never arriving.
        val lowHalf = state.snapLowHalf
        val highHalf = state.snapHighHalf
        if (lowHalf == null || highHalf == null) {
            _uiState.update { it.copy(snapPreview = null, snapFailure = SNAP_PAIR_STALE_MESSAGE) }
            return
        }
        val lowPlacement = lowHalf.placement
        val highPlacement = highHalf.placement
        val lowFit = state.snapLowFitHalf
        val highFit = state.snapHighFitHalf
        if (lowFit == null || highFit == null) {
            _uiState.update {
                it.copy(
                    snapPreview = null,
                    snapFailure = "The plane this pair was split on is not known; split the model again.",
                )
            }
            return
        }
        // Every joint's tap, read in the half it landed on: the plate has moved
        // the two apart since the split, so the same plate point is a different
        // spot in each half's own frame.
        val anchors = ArrayList<Vec3>(specs.size)
        for ((index, spec) in specs.withIndex()) {
            val local = state.snapAnchorHalfFor(spec)?.toLocal(spec.anchorPointMm)
            if (local == null) {
                _uiState.update {
                    it.copy(
                        snapPreview = null,
                        snapFailure = "Joint " + (index + 1) + " is not on either half any more - " +
                            SNAP_PAIR_STALE_MESSAGE,
                    )
                }
                return
            }
            anchors += local
        }
        val prepared = withContext(Dispatchers.Default) prepared@{
            val lowReady = SnapFitGate.prepare(lowFit.mesh, SnapFitGate.NativeEngine, lowHalf.name)
            if (lowReady is SnapFitGate.Result.Refused) {
                return@prepared SnapPreviewResult.Refused(lowReady.reason)
            }
            val highReady = SnapFitGate.prepare(highFit.mesh, SnapFitGate.NativeEngine, highHalf.name)
            if (highReady is SnapFitGate.Result.Refused) {
                return@prepared SnapPreviewResult.Refused(highReady.reason)
            }
            val lowPrepared = (lowReady as SnapFitGate.Result.Prepared).ready
            val highPrepared = (highReady as SnapFitGate.Result.Prepared).ready
            val note = listOfNotNull(lowPrepared.note, highPrepared.note)
                .takeIf { it.isNotEmpty() }?.joinToString("; ")
            // The gate's repair, if any, replaces the mesh the joints measure;
            // each half's own face and placement travel with it unchanged.
            SnapPreviewResult.Prepared(
                lowHalf = lowFit.copy(mesh = lowPrepared.mesh),
                highHalf = highFit.copy(mesh = highPrepared.mesh),
                note = note,
            )
        }
        val ready = when (prepared) {
            is SnapPreviewResult.Refused -> {
                _uiState.update { it.copy(snapPreview = null, snapFailure = prepared.reason) }
                return
            }
            is SnapPreviewResult.Prepared -> prepared
        }
        val hookParameters = SnapFitParameters(
            beamLengthOverrideMm = state.snapHookLengthMm,
            beamThicknessOverrideMm = state.snapHookThicknessMm,
            lipDepthOverrideMm = state.snapHookLipMm,
        )
        val outcome = withContext(Dispatchers.Default) {
            placeEveryJoint(
                state = state,
                ready = ready,
                lowName = lowHalf.name,
                highName = highHalf.name,
                specs = specs,
                anchors = anchors,
                scale = scale,
                fullJoint = fullJoint,
                hookParameters = hookParameters,
                axis = axis,
            )
        }
        when (outcome) {
            is SnapPlacement.Refused ->
                _uiState.update { it.copy(snapPreview = null, snapFailure = outcome.reason) }

            is SnapPlacement.Done -> _uiState.update { current ->
                // The inputs moved while the booleans ran - another tap, a joint
                // removed, another scale, the plate itself: this result is for
                // geometry that is no longer the one asked about, so it is
                // dropped rather than shown as if it were current.
                val stale = !current.snapActive ||
                    current.snapLowHalfId != lowHalf.id ||
                    current.snapHighHalfId != highHalf.id ||
                    // The ids can stand while a half has been moved, which is a different
                    // half for every purpose the joint has: drop the result and let the
                    // placement change's own re-preview build it where it now stands.
                    current.snapLowHalf?.placement != lowPlacement ||
                    current.snapHighHalf?.placement != highPlacement ||
                    current.snapJoints != specs ||
                    current.snapScale != scale ||
                    current.snapFullJoint != fullJoint ||
                    current.snapSocketRamp != state.snapSocketRamp ||
                    current.snapHookLengthMm != state.snapHookLengthMm ||
                    current.snapHookThicknessMm != state.snapHookThicknessMm ||
                    current.snapHookLipMm != state.snapHookLipMm
                if (stale) current else current.copy(snapPreview = outcome.preview, snapFailure = outcome.notice)
            }
        }
    }

    /** What a whole set of joints came out as. */
    private sealed interface SnapPlacement {
        data class Done(val preview: SnapPreview, val notice: String?) : SnapPlacement

        /** Nothing is previewed at all; [reason] says which joint and what about it. */
        data class Refused(val reason: String) : SnapPlacement
    }

    /**
     * Every joint on the seam, built and then booleaned in turn onto the pair.
     *
     * Two things are deliberately separate here. Every joint is BUILT on the
     * pair's own faces, so which rung it comes out as is the material's answer
     * about that place on the seam and not about how many joints were placed
     * before it. The BOOLEANS then run one joint after another over the running
     * halves, because that is what the printed parts will be: union the beam,
     * fuse the pad on, cut the pocket, cut the recess, and hand the result to
     * the next joint - a few milliseconds each.
     *
     * Nothing is ever dropped quietly. A joint the material will not take, a
     * pair of joints whose pockets would cut into each other, and a pocket that
     * removes no material at all because a neighbour already took it are three
     * different sentences, and each one stops the preview and names the joint.
     */
    private fun placeEveryJoint(
        state: MainUiState,
        ready: SnapPreviewResult.Prepared,
        lowName: String,
        highName: String,
        specs: List<SnapJointSpec>,
        anchors: List<Vec3>,
        scale: Float,
        fullJoint: Boolean,
        hookParameters: SnapFitParameters,
        axis: ModelPlacement.Axis,
    ): SnapPlacement {
        val engine = SnapFitGate.NativeEngine
        // A joint's own parameters: the hook controls the user has typed, and
        // then its own clearance step, its own teeth, its own facing and the
        // pair's socket ramp. Nothing else differs between two joints.
        fun parametersFor(spec: SnapJointSpec): SnapFitParameters = hookParameters.copy(
            barbCount = spec.barbs,
            facing = spec.facing,
            socketRamp = state.snapSocketRamp,
        ).tightenedBy(spec.tightness.stepMm)

        val built = ArrayList<SnapJoint.Placement>(specs.size)
        val notices = ArrayList<String>()
        for (index in specs.indices) {
            val spec = specs[index]
            val parameters = parametersFor(spec)
            val placement = when (
                val result = SnapJoint.build(
                    axis = axis,
                    anchorMm = anchors[index],
                    scale = scale,
                    lowHalf = ready.lowHalf,
                    highHalf = ready.highHalf,
                    beamHalf = spec.beamHalf,
                    parameters = parameters,
                    requested = if (fullJoint) SnapFitRung.FULL else null,
                )
            ) {
                is SnapJoint.Either.Placed -> result.placement
                is SnapJoint.Either.Flipped -> {
                    notices += "Joint " + (index + 1) + ": " + result.why.summary
                    result.placement
                }
                is SnapJoint.Either.Failed ->
                    return SnapPlacement.Refused("Joint " + (index + 1) + ": " + result.failure.summary)
            }
            // The full joint's pad is contoured to the material it stands on,
            // and when the wall is too thin for a rim the ladder's lesser rung is
            // used and says so: a pad that collapses to nothing is not a pad.
            val contoured = contouredOrLesser(placement, ready, anchors[index], axis, scale, spec.beamHalf, parameters)
                ?: return SnapPlacement.Refused(
                    "Joint " + (index + 1) + ": " + SnapPad.TOO_THIN_REASON +
                        ", and the lesser joints do not fit either",
                )
            built += contoured
        }

        // The guard, before a boolean moves anything: two joints whose pockets
        // cross, or leave no printable wall between them, would silently cut
        // each other away - and a joint that has been cut away still passes
        // every closedness check there is.
        val wall = built.maxOf { it.joint.dimensions.beamThicknessMm }
        val conflicts = SnapLayout.conflicts(built.map { SnapLayout.footprintOf(it.joint) }, wall)
        if (conflicts.isNotEmpty()) {
            return SnapPlacement.Refused(
                conflicts.joinToString("; ") +
                    ". Move a joint, remove one, or clear them and spread them again.",
            )
        }

        var lowMesh = ready.lowHalf.mesh
        var highMesh = ready.highHalf.mesh
        val placed = ArrayList<SnapPlacedJoint>(specs.size)
        for (index in specs.indices) {
            val placement = built[index]
            val joint = placement.joint
            val withStep = joint.rung == SnapFitRung.FULL
            val union = engine.union(placement.beamMesh, joint.unionSolid)
            if (union !is MeshBoolean.Result.Success) {
                return SnapPlacement.Refused(
                    "Joint " + (index + 1) + " could not be joined on: " +
                        (union as MeshBoolean.Result.Failure).reason,
                )
            }
            // The whole-seam registration step, when this rung carries one. Its
            // solids are separate meshes because one mesh holding two
            // overlapping shells is not manifold, so the engine is what fuses
            // the boss on and cuts the matching recess out.
            val registered = if (withStep) {
                val stepped = engine.union(union.mesh, joint.registrationSolid)
                if (stepped !is MeshBoolean.Result.Success) {
                    return SnapPlacement.Refused(
                        "Joint " + (index + 1) + " could not have its registration step joined on: " +
                            (stepped as MeshBoolean.Result.Failure).reason,
                    )
                }
                stepped
            } else {
                union
            }
            val socket = engine.subtract(placement.socketMesh, joint.subtractSolid)
            if (socket !is MeshBoolean.Result.Success) {
                return SnapPlacement.Refused(
                    "Joint " + (index + 1) + " could not have its pocket cut: " +
                        (socket as MeshBoolean.Result.Failure).reason,
                )
            }
            val recessed = if (withStep) {
                val hollowed = engine.subtract(socket.mesh, joint.registrationRecess)
                if (hollowed !is MeshBoolean.Result.Success) {
                    return SnapPlacement.Refused(
                        "Joint " + (index + 1) + " could not have its matching recess cut: " +
                            (hollowed as MeshBoolean.Result.Failure).reason,
                    )
                }
                hollowed
            } else {
                socket
            }
            // A joint that shares no volume with the half it is rooted in is a
            // floating block, and a pocket that removes nothing is half a joint:
            // both pass a closedness check, so both are measured here instead.
            // With several joints, a pocket that removes nothing is also what a
            // neighbour's pocket having taken the material already looks like.
            val beamGain = union.volumeMm3 - MeshVolume.of(placement.beamMesh)
            val pocketLoss = MeshVolume.of(placement.socketMesh) - socket.volumeMm3
            val padGain = registered.volumeMm3 - union.volumeMm3
            val recessLoss = socket.volumeMm3 - recessed.volumeMm3
            val failure = when {
                beamGain < MIN_FEATURE_MM3 ->
                    "Joint " + (index + 1) + " has no material where it was placed for its beam to root in; " +
                        "tap another spot on the seam."
                pocketLoss < MIN_FEATURE_MM3 ->
                    "Joint " + (index + 1) + ": the matching pocket would cut nothing out of the other half; " +
                        "tap another spot, or move the joints apart."
                withStep && recessLoss < MIN_FEATURE_MM3 ->
                    "Joint " + (index + 1) + ": the matching recess would cut nothing out of the other half."
                withStep && padGain < MIN_FEATURE_MM3 ->
                    "Joint " + (index + 1) + ": the whole-seam pad has nothing to hold on to here."
                else -> null
            }
            if (failure != null) return SnapPlacement.Refused(failure)

            // Which half carries the beam decides which running mesh takes what,
            // and where each half's mating face now is on the plate.
            val beamIsLow = placement.beamMesh === ready.lowHalf.mesh
            if (beamIsLow) {
                lowMesh = registered.mesh
                highMesh = recessed.mesh
            } else {
                lowMesh = recessed.mesh
                highMesh = registered.mesh
            }
            placed += SnapPlacedJoint(
                spec = specs[index],
                joint = joint,
                beamInChosenHalf = placement.chosenHalfCarriesBeam,
                beamHalfName = if (beamIsLow) lowName else highName,
                socketHalfName = if (beamIsLow) highName else lowName,
            )
            // The proof the preview is geometry and not an empty boolean: how
            // much material the half gained or lost, how much of the joint
            // really sits in the half it was built for, and what the beam has to
            // bend through. Only asked for while the log is being kept - the
            // intersections are extra engine work.
            if (Diagnostics.isEnabled()) {
                Diagnostics.info(
                    "snap fit",
                    "joint " + (index + 1) + "/" + specs.size +
                        " rung=" + joint.rung.name +
                        (joint.rungReason?.let { " (" + it + ")" } ?: "") +
                        " " + joint.dimensions.barbCount + " tooth" +
                        (if (joint.dimensions.barbCount == 1) "" else " teeth") +
                        " " + joint.clickSummary +
                        ", " + specs[index].tightness.label.lowercase() + " clearance" +
                        (if (joint.dimensions.mouthChamferMm > 0f) ", socket ramp" else "") +
                        ": beam root +" + millimetres(beamGain) +
                        " mm3 (seat " + millimetres(seatVolume(placement.beamMesh, joint.unionSolid)) +
                        " mm3), pocket -" + millimetres(pocketLoss) +
                        " mm3, pad +" + millimetres(padGain) +
                        " mm3, recess -" + millimetres(recessLoss) +
                        " mm3, deflection room " + millimetres(joint.dimensions.deflectionRoomMm.toDouble()) +
                        " mm",
                )
            }
        }

        val lowPreview = lowMesh.copy(displayName = lowName)
        val highPreview = highMesh.copy(displayName = highName)
        // Every joint on a seam sits on the same plane, so the first one's
        // frames say where each half's mating face is on the plate: Apply
        // re-centres these meshes and makes them the halves' new own frames.
        val first = built.first()
        val firstBeamIsLow = first.beamMesh === ready.lowHalf.mesh
        val lowFace = if (firstBeamIsLow) plateFace(first.joint.frame, axis) else plateFace(first.joint.socketFrame, axis)
        val highFace = if (firstBeamIsLow) plateFace(first.joint.socketFrame, axis) else plateFace(first.joint.frame, axis)
        val preview = SnapPreview(
            lowMesh = lowPreview,
            highMesh = highPreview,
            joints = placed,
            specs = specs,
            lowHalfId = state.snapLowHalfId,
            highHalfId = state.snapHighHalfId,
            lowPlacement = state.snapLowHalf?.placement,
            highPlacement = state.snapHighHalf?.placement,
            scale = scale,
            fullJoint = fullJoint,
            socketRamp = state.snapSocketRamp,
            hookLengthMm = state.snapHookLengthMm,
            hookThicknessMm = state.snapHookThicknessMm,
            hookLipMm = state.snapHookLipMm,
            repairNote = ready.note,
            lowFaceMm = lowFace,
            highFaceMm = highFace,
        )
        return SnapPlacement.Done(preview, notices.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    /**
     * The joint to place: the pad contoured to the half's own material, or the
     * ladder's next rung when the wall is too thin for a rim.
     *
     * The contour is what keeps a full joint on a hollow part honest - the pad
     * follows the walls instead of spanning the void - and it is the engine
     * that says whether enough material survives to be a rim. When it does
     * not, dropping a rung and naming why is the ladder's own answer, not a
     * failure to place anything.
     */
    private fun contouredOrLesser(
        base: SnapJoint.Placement,
        ready: SnapPreviewResult.Prepared,
        /** The joint's own tap, in the halves' own coordinates. */
        anchorMm: Vec3,
        axis: ModelPlacement.Axis,
        scale: Float,
        beamHalf: SnapJoint.JointHalf,
        parameters: SnapFitParameters,
    ): SnapJoint.Placement? {
        if (base.joint.rung != SnapFitRung.FULL) return base
        val beamFit = if (base.beamMesh === ready.lowHalf.mesh) ready.lowHalf else ready.highHalf
        val socketFit = if (base.beamMesh === ready.lowHalf.mesh) ready.highHalf else ready.lowHalf
        val contoured = SnapPad.contour(base.joint, beamFit, socketFit)
        if (contoured != null) return base.copy(joint = contoured)
        for (rung in listOf(SnapFitRung.SIMPLE, SnapFitRung.MINIMAL)) {
            val lesser = SnapJoint.build(
                axis = axis,
                anchorMm = anchorMm,
                scale = scale,
                lowHalf = ready.lowHalf,
                highHalf = ready.highHalf,
                beamHalf = beamHalf,
                parameters = parameters,
                requested = rung,
            )
            val placement = when (lesser) {
                is SnapJoint.Either.Placed -> lesser.placement
                is SnapJoint.Either.Flipped -> lesser.placement
                is SnapJoint.Either.Failed -> continue
            }
            val reason = SnapPad.TOO_THIN_REASON + "; " + rung.label.lowercase() + " only"
            return placement.copy(joint = placement.joint.copy(rungReason = reason))
        }
        return null
    }

    /** The one line a flip deserves; null when the joint went where it was asked to. */
    private fun flipNotice(built: SnapJoint.Either): String? =
        (built as? SnapJoint.Either.Flipped)?.why?.summary

    private sealed interface SnapPreviewResult {
        /** One half, or both, refused the watertight pre-flight; nothing was booleaned. */
        data class Refused(val reason: String) : SnapPreviewResult

        data class Prepared(
            /** The two halves, each with its own mating face and placement. */
            val lowHalf: SnapFitHalf,
            val highHalf: SnapFitHalf,
            val note: String?,
        ) : SnapPreviewResult
    }

    /**
     * Where a half's own mating face now sits along [axis], on the plate.
     *
     * The frame's origin is the anchor dropped onto that face, so its component
     * along the axis is the face's own coordinate - read in the plate frame the
     * previewed mesh is in, which is the frame Apply re-centres the half into.
     * A half placed by translation is exact; a rotation of the split plane is
     * not something the carried face represents, and the split never makes one.
     */
    private fun plateFace(frame: SnapFitFrame, axis: ModelPlacement.Axis): Float =
        frame.originMm.dot(SnapFit.axisDirection(axis))

    /** The material two solids share, or 0 when the engine would not answer. */
    private fun seatVolume(first: StlMesh, second: StlMesh): Double =
        when (val shared = MeshBoolean.intersect(first, second)) {
            is MeshBoolean.Result.Success -> shared.volumeMm3
            is MeshBoolean.Result.Failure -> Double.NaN
        }

    /** One decimal place, locale-independent: these numbers land in a log line. */
    private fun millimetres(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    /**
     * Less material moved than this and there is no feature there: a joint
     * whose beam, pad or recess moves less than half a cubic millimetre is
     * half a joint, and the panel says so instead of showing it.
     */
    private val MIN_FEATURE_MM3 = 0.5

    /**
     * Commits the previewed joint: the two booleaned halves replace the two
     * halves on the plate, through the same staging path the split uses - each
     * with its own STL file, laid out by the plate's own packer and saved into
     * the workspace - so a relaunch restores both and both slice.
     */
    fun applySnapJoint() {
        val state = _uiState.value
        val preview = state.snapShownMeshes
        if (preview == null) {
            val reason = state.snapBlockedReason
                ?: "The joint preview is still being built; try again in a moment"
            showSnapFailure(IllegalStateException(reason))
            return
        }
        // The pair the split recorded, as the plate has it now. A half that has
        // left the plate since - deleted, replaced by another split, or swapped
        // out by a restored workspace - is a normal thing for a plate to have
        // done, and it says so in words instead of failing on a lookup.
        val pair = state.snapPairIndices
        if (pair !is SnapPairLookup.Found) {
            showSnapFailure(IllegalStateException(SNAP_PAIR_STALE_MESSAGE))
            return
        }
        val low = state.models[pair.lowIndex]
        val high = state.models[pair.highIndex]
        if (!beginOperation("Joining " + low.name + " and " + high.name + "...")) return
        snapPreviewJob?.cancel()
        viewModelScope.launch {
            val staged = ArrayList<File>(2)
            runCatching {
                val objects = withContext(Dispatchers.IO) {
                    listOf(low.name to preview.lowMesh, high.name to preview.highMesh).map { (name, mesh) ->
                        val file = stagedModelFile()
                        staged += file
                        StlMeshWriter.writeBinary(mesh.copy(displayName = name), file)
                        cutHalfObject(name, mesh, file, state)
                    }
                }
                val laidOut = withContext(Dispatchers.Default) {
                    // The replaced halves keep their place in the plate order, so
                    // the packer lays the plate out the way it was, with the two
                    // jointed parts where the two originals stood.
                    val replacing = mapOf(low.id to objects.first(), high.id to objects.last())
                    val replaced = state.models.map { existing -> replacing[existing.id] ?: existing }
                    val packed = arranged(replaced, state.platePreferences, state.settings)
                        ?: throw IllegalStateException(
                            "The jointed halves do not fit on the bed " +
                                state.platePreferences.sanitized().spacingMm + " mm apart; nothing was changed",
                        )
                    // arranged() returns fresh objects, so the jointed parts are
                    // found by their place in the plate order and never by
                    // identity. A list that no longer holds those places is the
                    // same stale pair as above, said the same way.
                    val lowObject = packed.getOrNull(pair.lowIndex)
                    val highObject = packed.getOrNull(pair.highIndex)
                    if (lowObject == null || highObject == null) {
                        throw IllegalStateException(SNAP_PAIR_STALE_MESSAGE)
                    }
                    Triple(packed, lowObject, highObject)
                }
                val packed = laidOut.first
                val lowObject = laidOut.second
                val highObject = laidOut.third
                val live = _uiState.value
                val unchanged = live.models.size == state.models.size &&
                    live.models.indices.all { live.models[it] === state.models[it] }
                if (!unchanged) {
                    runCatching { staged.forEach { it.delete() } }
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = "The plate changed while the joint was being applied; " +
                                "nothing was changed",
                        )
                    }
                    return@runCatching
                }
                val message = "Joined " + low.name + " and " + high.name +
                    " with a snap fit; slice again to export G-code"
                val commit = { current: MainUiState ->
                    current.withoutPublishedSlice(message).copy(
                        models = packed,
                        selectedModelId = lowObject.id,
                        isBusy = false,
                    ).afterPlateReplaced().withSnapPair(
                        // The pair is the jointed halves now, so the tool can be
                        // opened again to add a second joint to the same pair.
                        // Apply re-centres the previewed meshes into fresh
                        // objects, so each half's new own frame is the plate
                        // frame they were previewed in, and the faces recorded
                        // here are the plate faces the preview built them on -
                        // a measurement of the new meshes would read the beam's
                        // own tip as the beam half's face.
                        lowHalfId = lowObject.id,
                        highHalfId = highObject.id,
                        axis = state.snapAxis,
                        lowFaceMm = preview.lowFaceMm,
                        highFaceMm = preview.highFaceMm,
                    )
                }
                val descriptor = workspaceSnapshot(commit(_uiState.value))
                    ?: throw IllegalStateException("The joined plate could not be saved")
                withContext(Dispatchers.IO) {
                    workspaceStore.save(descriptor)
                    // The halves the joint replaced are no longer on the plate, so
                    // the files they were restored from go the way a removed
                    // object's file goes - unless something else still came from one.
                    listOf(low, high).forEach { replaced ->
                        val path = replaced.sourcePath
                        if (path != null && packed.none { it.sourcePath == path }) {
                            File(path).takeIf { it.isFile }?.delete()
                        }
                    }
                }
                // Apply replaces both halves with the jointed meshes, so the placement
                // history - recorded against the objects that just left the plate - goes
                // with them, exactly as it does for a split.
                placementHistory.clear()
                _uiState.update { commit(it) }
            }.onFailure { error ->
                runCatching { staged.forEach { it.delete() } }
                // Apply runs from the snap panel, so its failure belongs on the line the
                // panel shows, the same as its refusals do.
                showSnapFailure(error)
            }
        }
    }

    /** The middle of a span: where a cut starts, so both halves are worth looking at. */
    private fun middleOf(span: ClosedFloatingPointRange<Float>): Float =
        span.start + (span.endInclusive - span.start) / 2f

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
     * a typed value does - except downwards, where the model may hang below the
     * bed and be cut off there.
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
                        snapshot.models.forEach { model ->
                            require(model.mesh.bounds.maxZ > 0f) {
                                model.name + " is entirely below the bed at Z=0; raise it before slicing"
                            }
                        }
                        val slicerFork = sliceEngine != SlicerEngine.CURA
                        val plate: List<SliceModel> = if (slicerFork && snapshot.models.size > 1) {
                            val staging = File(stagingDirectory, "plate.3mf")
                            PlateThreeMfWriter.write(
                                file = staging,
                                entries = snapshot.models.map { model ->
                                    val prepared = model.staged()
                                    PlateThreeMfWriter.Entry(
                                        name = model.name,
                                        mesh = prepared.mesh,
                                        paint = prepared.paint,
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
                                val prepared = model.staged()
                                val file = if (slicerFork && !prepared.paint.isEmpty) {
                                    File(stagingDirectory, "model-$index.3mf").also { staged ->
                                        // The dialect is the engine's, not a default: the two forks
                                        // read paint from differently named attributes, and the
                                        // Prusa one only through the loader its stamp selects.
                                        PaintedMeshWriter.write(
                                            mesh = prepared.mesh,
                                            paint = prepared.paint,
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
                                        StlMeshWriter.writeBinary(prepared.mesh, staged)
                                    }
                                }
                                SliceModel(file = file, name = model.name, supportPaint = prepared.paint)
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
     * What is staged for the engine: the placed mesh with everything below the
     * bed cut away at Z=0 (see [BedClipper]), and the paint read against that
     * mesh.
     *
     * The viewer keeps the unclipped mesh, so the user still sees the part they
     * are cutting and can drag the model back up. A model with nothing above the
     * bed has no geometry to stage - the callers refuse it before reaching here.
     */
    private fun PlateObject.staged(): StagedModel {
        if (mesh.bounds.minZ >= 0f) return StagedModel(mesh, supportPaint)
        val clipped = BedClipper.clipToBedWithSources(mesh)
        check(clipped.mesh.triangleCount > 0) { name + " has no geometry above the bed to slice" }
        // Paint is indices into the mesh the VIEWER draws; the engine is handed
        // the clipped one, where every triangle above the cut has been renamed
        // and the ones below it are gone. Read through the clip's own record,
        // the paint lands on the triangles the user actually painted - and no
        // painted index can fall outside the staged mesh.
        val paint = clipped.sourceTriangles?.let(supportPaint::throughClip)
            ?: supportPaint.clippedToMesh(clipped.mesh.triangleCount)
        return StagedModel(clipped.mesh, paint)
    }

    /** A placed model as the engine is handed it: the staged mesh and its paint. */
    private class StagedModel(val mesh: StlMesh, val paint: SupportPaintState)

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
                    // immediate feedback instead of a slice-time failure. Below
                    // the bed is allowed - the slice clips it there - but a
                    // model with nothing left above the bed has nothing to
                    // print, and that is worth saying now rather than at the
                    // end of a slice.
                    PrinterEnvelope.from(printer.withSettings(stateSnapshot.settings))
                        .requireModelFits(transformed, allowBelowBed = true)
                    require(transformed.bounds.maxZ > 0f) { ENTIRELY_BELOW_BED_MESSAGE }
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
            }.onSuccess { (changed, transformed) ->
                if (recordHistory) placementHistory.record(message, current)
                // What the plate's yellow notice says is read back off the placed
                // mesh (MainUiState.belowBedCutMm), so there is nothing to store
                // here: this line only keeps the status line honest about the cut.
                val cutBelowBed = transformed.bounds.minZ < 0f
                _uiState.update { state ->
                    state.withoutPublishedSlice()
                        .withModel(selectedId) { model -> model.withPlacement(changed) }
                        .copy(
                            canUndoPlacement = placementHistory.canUndo,
                            undoPlacementLabel = placementHistory.nextLabel,
                            isBusy = false,
                            statusMessage = if (cutBelowBed) {
                                "$message; slice again to export G-code · the part below the bed is cut off"
                            } else {
                                "$message; slice again to export G-code"
                            },
                        )
                }
                // The joint stands on the halves, so a placement change is a change to the
                // joint's own input: the preview key drops the old one on that update, and
                // this rebuilds it for where the half stands now. Without the rebuild the
                // panel would sit with Join disabled and a ghost on screen until some other
                // control happened to be touched.
                if (_uiState.value.snapActive) previewSnapJoint()
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
                                            id = entry.id,
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
        val seenIds = HashSet<String>()
        val objects = workspace.objects.map { restored ->
            val model = restored.toPlateObject()
            val unique = if (seenNames.add(model.name)) {
                model.name
            } else {
                generateSequence(2) { it + 1 }
                    .map { "${model.name} ($it)" }
                    .first { seenNames.add(it) }
            }
            model.copy(
                // A descriptor written by hand can hold one identity twice; the second of a
                // pair gets a fresh one rather than aliasing the first, because the plate's
                // whole addressing assumes an id names one object.
                id = if (seenIds.add(model.id)) model.id else PlateObject.newId(),
                name = unique,
            )
        }
        // The pair the descriptor saved, once both halves really are on the plate it
        // describes. Anything less - an older descriptor with no pair at all, a damaged one,
        // or a half whose file could not be parsed - restores as a plate that simply has no
        // snap fit on offer, never as a plate that failed to load. That is the property the
        // descriptor's other tolerances keep, and the pair must not cost it.
        val pair = snapshot.snap?.takeIf { saved ->
            saved.lowHalfId != saved.highHalfId &&
                objects.any { it.id == saved.lowHalfId } &&
                objects.any { it.id == saved.highHalfId }
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
                // The tool can be opened again on a restored pair, which is the whole point of
                // saving it: without this, the only way back to the joint was another split,
                // and that destroys the joint already applied.
                .withSnapPair(
                    lowHalfId = pair?.lowHalfId,
                    highHalfId = pair?.highHalfId,
                    axis = pair?.axis ?: ModelPlacement.Axis.Z,
                    lowFaceMm = pair?.lowFaceMm,
                    highFaceMm = pair?.highFaceMm,
                )
                .withoutSnap()
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
            snap = state.toSnapshotSnap(),
        )
    }

    /**
     * The split pair as the descriptor saves it, or null when there is no pair to save.
     *
     * Only a pair that is really on the plate is written: a pair left behind by a removed
     * half would restore as a tool that offers nothing, which the plate can say for itself
     * without the file remembering it.
     */
    private fun MainUiState.toSnapshotSnap(): WorkspaceStateStore.SnapState? {
        if (!snapHalvesPresent) return null
        val low = snapLowHalfId ?: return null
        val high = snapHighHalfId ?: return null
        val lowFace = snapLowFaceMm ?: return null
        val highFace = snapHighFaceMm ?: return null
        return WorkspaceStateStore.SnapState(low, high, snapAxis, lowFace, highFace)
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
            id = id,
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

    /**
     * A snap fit failure, on the line the tool's panel actually shows.
     *
     * Every other operation reports through [showOperationFailure], whose message is the
     * title of the plate's notice card - and that card is folded by default. The snap panel
     * is open in front of the user while its own work runs, and the line it shows is
     * [MainUiState.snapFailure]; a refusal that wrote only the folded card left a tap that
     * did nothing the user could see. Both lines are written: the panel says it now, and the
     * card still carries it once the panel is closed.
     */
    private fun showSnapFailure(error: Throwable) {
        if (error is CancellationException) {
            // A cancelled preview is the debounce working, not a failure to report.
            showOperationFailure(error)
            return
        }
        Diagnostics.failure("snap fit", error)
        val message = error.message ?: error::class.java.simpleName
        _uiState.update { it.copy(isBusy = false, statusMessage = message, snapFailure = message) }
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
