package com.tomppi.enderslicer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.tomppi.enderslicer.agent.AgentBridges
import com.tomppi.enderslicer.cadagent.AgentQuestion
import com.tomppi.enderslicer.cadagent.AgentToolTrace
import com.tomppi.enderslicer.cadagent.CadAgent
import com.tomppi.enderslicer.cadagent.InAppTurnKeeper
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
import android.util.Log
import com.tomppi.enderslicer.engine.GcodeDialect
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.engine.GcodeLayerPreviewParser
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
import com.tomppi.enderslicer.viewer.ModelSurfaceView
import com.tomppi.enderslicer.viewer.PreviewMemoryPolicy
import com.tomppi.enderslicer.viewer.PreviewMemoryProfile
import com.tomppi.enderslicer.viewer.MeshBoolean
import com.tomppi.enderslicer.viewer.MeshPicker
import com.tomppi.enderslicer.viewer.MeshSolidBuilder
import com.tomppi.enderslicer.viewer.MeshVolume
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.ViewerOrientation
import com.tomppi.enderslicer.viewer.PaintedMeshWriter
import com.tomppi.enderslicer.viewer.PlateModes
import com.tomppi.enderslicer.viewer.PlateThreeMfWriter
import com.tomppi.enderslicer.viewer.SnapApply
import com.tomppi.enderslicer.viewer.SnapFacing
import com.tomppi.enderslicer.viewer.SnapFit
import com.tomppi.enderslicer.viewer.SnapFitGate
import com.tomppi.enderslicer.viewer.SnapFitRung
import com.tomppi.enderslicer.viewer.SnapFitHalf
import com.tomppi.enderslicer.viewer.SnapFitParameters
import com.tomppi.enderslicer.viewer.SnapJoint
import com.tomppi.enderslicer.viewer.SnapLip
import com.tomppi.enderslicer.viewer.SnapJointStyle
import com.tomppi.enderslicer.viewer.SnapLibrary
import com.tomppi.enderslicer.viewer.SnapLibraryPair
import com.tomppi.enderslicer.viewer.SnapLibraryShape
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.tomppi.enderslicer.solidcraft.CadBodyMeshes
import com.tomppi.enderslicer.solidcraft.CadDocument
import com.tomppi.enderslicer.solidcraft.CadConvert
import com.tomppi.enderslicer.solidcraft.CadConvertedPart
import com.tomppi.enderslicer.solidcraft.CadDesignLibrary
import com.tomppi.enderslicer.solidcraft.CadLiveUpdate
import com.tomppi.enderslicer.solidcraft.CadPart
import com.tomppi.enderslicer.solidcraft.CadPartPublish
import com.tomppi.enderslicer.solidcraft.CadPersistScheduler
import com.tomppi.enderslicer.solidcraft.CadSliceGate
import com.tomppi.enderslicer.solidcraft.CadWorkspace
import com.tomppi.enderslicer.solidcraft.DocumentDelta
import com.tomppi.enderslicer.solidcraft.EngineFaceQuery
import com.tomppi.enderslicer.solidcraft.FaceSelectionFlow
import com.tomppi.enderslicer.solidcraft.FaceReapply
import com.tomppi.enderslicer.solidcraft.FaceSelectionKeeper
import com.tomppi.enderslicer.solidcraft.SelectedFace
import com.tomppi.enderslicer.solidcraft.SolidCraftEngine
import com.tomppi.enderslicer.solidcraft.SolidCraftSession
import com.tomppi.enderslicer.solidcraft.ToolStep
import org.json.JSONArray
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
 * How often a live CAD document's display cache and workspace descriptor are written.
 *
 * This is persistence, never the picture: the plate repaints after every agent edit. Writing
 * tens of kilobytes after every boolean would be disk work nobody is waiting for.
 */
private const val CAD_PERSIST_INTERVAL_MILLIS = 1_500L

/**
 * The file kinds that open as a CAD document through the engine's io crate.
 *
 * STEP and IGES are also the two formats Import model converts to a mesh through OpenCASCADE;
 * which route a file takes is the menu item the user picked, and the status line says which
 * one answered. 3MF, OBJ and glTF had no CAD route at all before this.
 */
private val CAD_DOCUMENT_EXTENSIONS = setOf("step", "stp", "iges", "igs", "3mf", "obj", "glb", "gltf", "solidcraft")

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

/**
 * What every engine's snapshot says under the imported file's name: the settings are a
 * snapshot of that file, not a live view of it - the file is gone from the app's point
 * of view the moment it is read, and only these values remain.
 */
private const val CONFIGURATION_SNAPSHOT_SOURCE = "Configuration snapshot"

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
    /** One layer-preview read at a time, for the tab that is opened before a slice exists. */
    private val layerPreviewParse = java.util.concurrent.atomic.AtomicBoolean(false)

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
        /** The two engines' own snapshots, read back with Cura's. */
        val prusaSnapshot: AppStateStore.PrusaSnapshotBaseline?,
        val orcaSnapshot: AppStateStore.OrcaSnapshotBaseline?,
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
    // One baseline per engine: a PrusaSlicer preset and an OrcaSlicer profile each keep
    // what they set, so their sheets can show it and put it back - and neither can be
    // restored as the other, or as Cura's.
    private var importedPrusaBaseline: AppStateStore.PrusaSnapshotBaseline? = null
    private var importedOrcaBaseline: AppStateStore.OrcaSnapshotBaseline? = null

    // The native BumpMesh studio's two session objects: the host seam and its bounded undo
    // history. The per-apply job is kept so the sheet's Cancel can stop the turn as well as the
    // engine. The host is created lazily on first use (it owns a WebView once the engine lane
    // lands, so the sheet's open is the right moment) and closed with this ViewModel.
    private val bumpMeshEngine: BumpMeshTextureEngine by lazy {
        BumpMeshTextureEngines.create(getApplication(), viewModelScope)
    }
    private val textureUndoHistory = TextureUndoStack()
    private var bumpMeshApplyJob: Job? = null

    /**
     * The engine lane's Mesh-Mix-S2C port, behind the ui seam. Created lazily on the sheet's
     * first open; closed with this ViewModel. The one converter in the app is the engine lane's.
     */
    private val meshToStepEngine: MeshToStepEngine by lazy {
        MeshToStepEngines.create(getApplication(), viewModelScope)
    }
    private var meshToStepJob: Job? = null

    /** Null while the native host is available; its refusal sentence when it is not. */
    internal val bumpMeshEngineUnavailableReason: String?
        get() = bumpMeshEngine.unavailableReason

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
     * Where the joint stood when the held arrow went down: every sample of that
     * grab is measured from here, so the axis carries it and nothing else does.
     * The depth has a baseline of its own, because it is a number rather than a
     * point.
     */
    private var snapAxisBaseline: Vec3? = null
    private var snapDepthBaseline: Float? = null

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
            sidebarSettings = restoredSidebarSettings(),
            curaMachineId = stateStore.restoreCuraMachine(),
            previewMemoryProfile = runCatching {
                PreviewMemoryProfile.valueOf(stateStore.restorePreviewMemoryProfile())
            }.getOrDefault(PreviewMemoryProfile.AUTO),
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

    // Declared before the init block on purpose: the collectors that block starts can emit
    // before the property initializers further down the class have run, and one of them
    // drains this queue. A field declared after the init block was still null there, which
    // is an NPE inside synchronized() the moment the view model is built on a direct
    // dispatcher (the snapshot tests did exactly that).
    private val pendingEngineImports = ArrayDeque<Pair<File, String>>()
    private val pendingEngineImportLock = Any()

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
        runCatching { bumpMeshEngine.close() }
        runCatching { meshToStepEngine.close() }
        // Ends the turn, not the engine: a blocking agent loop only stops when its own
        // cancel() is called, and its finally is the only place the keeper, the wake lock and
        // the document's authorship are released. A finished Activity (Back/swipe away) must
        // not leave a turn holding all three; rotation never clears this ViewModel.
        inAppCadAgent?.cancel()
        InAppTurnKeeper.end(app)
        runCatching { SolidCraftEngine.authorship().endInAppTurn() }
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

    // ------------------------------------------------------------------
    // SolidCraft: the plate's live CAD document
    // ------------------------------------------------------------------
    //
    // The human is a spectator on a CAD document. This section starts the engine, keeps the
    // plate's tessellation in step with the design as the agent edits it, and performs the two
    // deliberate exports the human owns: the STL a Slice consumes, and the STEP/STL/3MF an
    // explicit Export writes. Nothing in this class changes the document itself.

    /** The live tessellation: one mesh per body of the engine's design. */
    private val cadBodies = CadBodyMeshes()

    /**
     * True when [cadBodies] holds every body of the document.
     *
     * It is false after the plate is cleared (the engine's document and the composer part ways:
     * the design still has its bodies, the composer does not) and after the document is closed.
     * A delta that names bodies must then NOT be applied incrementally - it would build a plate
     * with only the named bodies, or with none for a removal-only edit. The repaint re-reads the
     * whole document first and says so in the status. [refreshCadAllBodies] restores it.
     */
    @Volatile
    private var cadComposerComplete = true

    /** Repaints run one at a time, in the order the engine reported the edits. */
    private val cadRepaintLock = Mutex()

    /**
     * The plate objects the live document draws, one per body: body name -> plate object id.
     *
     * The ViewModel owns this, like the rest of the document state: a repaint or a conversion
     * after a rotation finds the same objects, because a rotation does not restart the class.
     */
    private val cadPartIds = LinkedHashMap<String, String>()

    /** Ids of plate objects made from the document by a split (or a snap joint on its halves). */
    private val cadDerivedPlateIds = mutableSetOf<String>()

    /** The design file the running engine session has open, so a restart reopens it once. */
    @Volatile
    private var cadOpenDesignPath: String? = null

    /** When the state last took a new display revision; the UI reports the first frame after it. */
    @Volatile
    private var cadRevisionAtMillis: Long = 0L

    /**
     * The one writer for the design, its sidecar and the descriptor.
     *
     * At most one write every [CAD_PERSIST_INTERVAL_MILLIS], and always one more after the last
     * edit of a burst: the old two-branch throttle left its first (immediate) job in the field
     * forever, so every later edit inside the window was skipped and the design file on disk
     * stayed at the first edit of the burst (a data-loss bug). [CadPersistScheduler] is a single
     * loop that is only done when it is clean.
     */
    private val cadPersistScheduler: CadPersistScheduler by lazy {
        CadPersistScheduler(
            intervalMillis = CAD_PERSIST_INTERVAL_MILLIS,
            scope = viewModelScope,
            write = { cadPersistLock.withLock { persistCadWorkspaceNow() } },
        )
    }

    /**
     * Serialises the design's writers.
     *
     * The scheduled writer and the two explicit ones (Stop agent and Close document save before
     * the engine stops) can otherwise run on two IO threads at once, and the engine session is
     * not safe for two concurrent saves of the same design.
     */
    private val cadPersistLock = Mutex()

    /**
     * The plate view a running agent turn should render, looked up per call and never captured.
     *
     * The in-app turn outlives the activity by design (rotation): a view captured when the turn
     * started is detached after the recreate, and its capturePlate() refuses to draw - "no
     * frame" for the rest of the turn. This belongs to the ViewModel for the same reason the
     * conversation does, and the app writes it whenever a plate view is created or updated.
     */
    @Volatile
    var cadPlateView: ModelSurfaceView? = null

    /** True when the plate holds a split/snap layout made from this document. */
    private fun derivedLayoutOnPlate(document: CadDocument?): Boolean =
        cadDerivedPlateIds.isNotEmpty() || document?.derivedStale == true

    private val _solidCraftSheetOpen = MutableStateFlow(false)

    /** The agent sheet: the only place a human asks for a change to a CAD document. */
    val solidCraftSheetOpen: StateFlow<Boolean> = _solidCraftSheetOpen.asStateFlow()

    // --- The chat conversations, held across configuration changes --------------------------
    //
    // Rotating the phone recreates the activity, and these used to be composable `remember`s, so
    // the reported bug was "rotating the phone deletes the conversation". They live in the
    // ViewModel now, which survives a configuration change.
    //
    // Deliberately NOT persisted to disk: a conversation survives a rotation, not a process
    // death. It is session state, not saved state - writing a transcript to disk would outlive
    // the turn and the document it belongs to.

    /** The CAD document's two conversations: the in-app agent's and the harness session's. */
    var cadConversations by mutableStateOf(CadConversations())

    /** The in-app agent's status line and turn flag, both surviving a rotation. */
    var cadStatus: String? by mutableStateOf(null)

    private var cadBusyState by mutableStateOf(false)

    /**
     * The in-app agent's turn flag.
     *
     * It is mirrored into [MainUiState.cadTurnRunning] because the plate must be able to read
     * "a turn is running" as state - Convert and Rebuild are disabled from it - and the turn is
     * started by the screen, not by a plate action.
     */
    var cadBusy: Boolean
        get() = cadBusyState
        set(value) {
            if (cadBusyState == value) return
            cadBusyState = value
            _uiState.update { it.copy(cadTurnRunning = value) }
        }

    // The harness session is its own backend with its own status and busy flag: an in-app turn
    // must not make the harness source look busy, and a harness reconnect must not put its
    // failure into the in-app conversation's status line.
    var harnessCadStatus: String? by mutableStateOf(null)
    var harnessCadBusy by mutableStateOf(false)

    /** The in-app agent's endpoint notice, when it removed the web tools for this conversation. */
    var inAppWebNotice: String? by mutableStateOf(null)

    /** The chat's selected source; the sheet's mode chips and the rail door read the same state. */
    var chatSource by mutableStateOf(ChatSource.IN_APP)

    /** The live in-app agent, kept here so a turn survives - and can be cancelled across - a rotation. */
    var inAppCadAgent: CadAgent? by mutableStateOf(null)

    /** The modelling chat's conversation, the Blender screen's own. */
    var modellingMessages by mutableStateOf(listOf<AiChatMessage>())
    var modellingStatus: String? by mutableStateOf(null)
    var modellingBusy by mutableStateOf(false)

    /**
     * The in-app agent's question the user has not answered yet, or null.
     *
     * A question is asked with the app's `ask_user` tool, which ends the turn - nothing is
     * running while this is non-null. The chat shows the question card, the rail's Ask AI item
     * carries the one badge it can have, and the user's answer (or Dismiss) clears this and, for
     * an answer, starts the next turn. It is cleared by every new prompt and by closing the
     * document, so a dismissed question can never leave a stuck "waiting" state.
     */
    var pendingQuestion: AgentQuestion? by mutableStateOf(null)
        private set

    /** Shows a question card and marks the agent as waiting on the user's answer. */
    fun onInAppQuestion(question: AgentQuestion) {
        cadConversations = cadConversations.appendInApp(
            AiChatMessage(
                fromUser = false,
                text = question.question,
                kind = AiChatEntryKind.QUESTION,
                question = question,
            ),
        )
        pendingQuestion = question
    }

    /**
     * Clears the waiting question: on answer, on Dismiss, on any new prompt, and when the
     * document is closed or converted. Nothing else depends on it - the turn already ended.
     */
    fun clearPendingQuestion() {
        pendingQuestion = null
    }

    /**
     * Ends the previous document's in-app conversation, pending question and agent memory.
     *
     * A different document is a different conversation: the transcript, the model's remembered
     * turns and a waiting question all belong to the design they were made for. The docs promise
     * this, and the risk review asks that the clear is announced: the returned suffix is
     * appended to the action's status line, so the user is told the conversation was reset
     * instead of finding it gone.
     */
    private fun resetInAppConversationForDocumentSwitch(): String {
        val hadConversation = cadConversations.inApp.isNotEmpty() || pendingQuestion != null
        cadConversations = cadConversations.copy(inApp = emptyList())
        inAppCadAgent?.reset()
        clearPendingQuestion()
        return if (hadConversation) {
            " · the in-app conversation was reset for this document"
        } else {
            ""
        }
    }

    /** Bumped when a CAD turn ends, so the plate asks for a fresh frame and scene read. */
    var cadRefreshTick by mutableStateOf(0)
        private set

    /**
     * Runs one chat turn in the ViewModel's scope.
     *
     * This is what keeps a running in-app turn alive across a rotation: a composition's
     * rememberCoroutineScope is cancelled when the activity is recreated, while this scope
     * belongs to the ViewModel and outlives it. The turn's state is all ViewModel state, so the
     * next composition draws the rows it already produced.
     */
    fun runChatTurn(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(block = block)

    /** Posts one chat-state update from an agent callback running off the UI thread. */
    fun postChatUpdate(block: () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** A tool row's start or end, in the in-app conversation only. */
    fun applyCadToolTrace(trace: AgentToolTrace) {
        cadConversations = cadConversations.withToolTrace(trace)
    }

    /** Stops the in-app turn (the chat's Stop and the CAD agent sheet's Cancel, in-app mode). */
    fun cancelInAppTurn() {
        inAppCadAgent?.cancel()
    }

    /** Marks one CAD turn finished; the plate re-reads the frame and the scene on the next frame. */
    fun noteCadTurnFinished() {
        cadRefreshTick++
    }

    fun openSolidCraftSheet() {
        _solidCraftSheetOpen.value = true
    }

    fun closeSolidCraftSheet() {
        _solidCraftSheetOpen.value = false
    }

    private fun cadWorkspace(): CadWorkspace = SolidCraftEngine.workspace(app).ensureDirectories()

    /**
     * Starts the engine, subscribing the live view before it can report a change.
     *
     * Idempotent: the same call from the sheet, a restored document or a Slice finds the
     * running engine and only makes sure the plate's listener is attached.
     */
    private fun ensureCadEngine(): Boolean {
        SolidCraftEngine.onDocumentChanged = { delta -> onSolidCraftDelta(delta) }
        return SolidCraftEngine.ensureStarted(app)
    }

    /**
     * Attaches the plate's live view to the running engine for the in-app agent's tool calls.
     *
     * The bridge path subscribes when the engine is started from the sheet; the in-app agent
     * runs in this process and must repaint through the same handler, but it never starts the
     * engine itself - Connect agent is the user's button. A false answer is "not connected".
     */
    fun attachCadLiveView(): Boolean {
        if (!SolidCraftEngine.isReady()) return false
        SolidCraftEngine.onDocumentChanged = { delta -> onSolidCraftDelta(delta) }
        return true
    }

    /** Starts the engine and shows its state; the sidebar's connect-agent button lands here. */
    fun connectCadAgent() {
        if (_uiState.value.isBusy) return
        if (!beginOperation("Starting the CAD agent…")) return
        viewModelScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) {
                    if (!ensureCadEngine()) throw IllegalStateException(SolidCraftEngine.status(app))
                    val existing = _uiState.value.cadDocument
                    if (existing != null) {
                        val design = File(existing.designPath)
                        val session = SolidCraftEngine.session()
                            ?: throw IllegalStateException("the CAD agent session disappeared")
                        if (!design.isFile) {
                            throw IllegalStateException(
                                CadDesignLibrary.missingDesignSentence(existing.converted),
                            )
                        }
                        session.open(design.absolutePath)
                        cadOpenDesignPath = existing.designPath
                        // Connect starts the engine; it is not Rebuild plate. A converted plate
                        // is the human's STLs and a split/snap layout is the human's geometry
                        // made from this document: neither may be re-derived by connecting.
                        if (!existing.converted && !derivedLayoutOnPlate(existing)) {
                            if (refreshCadAllBodies(session, cadWorkspace(), existing.name)) {
                                publishCadParts(
                                    name = existing.name,
                                    designPath = existing.designPath,
                                    sourcePath = existing.sourcePath,
                                    editMillis = null,
                                    replaceDerived = false,
                                )
                            }
                        }
                    }
                    _uiState.value.cadDocument
                }
                val keptConverted = doc?.converted == true
                val keptDerived = doc != null && !keptConverted && derivedLayoutOnPlate(doc)
                if (doc != null && keptDerived) {
                    markCadStale(
                        previous = doc,
                        name = doc.name,
                        designPath = doc.designPath,
                        reason = "the plate holds a split or snap layout made from the previous " +
                            "shape of this document; the plate is not re-derived by connecting. " +
                            "Press Rebuild plate in the CAD agent sheet to take the document's " +
                            "current shape.",
                    )
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = when {
                            keptConverted -> "CAD agent ready · " + doc!!.name + " · the plate " +
                                "keeps its " + doc.convertedParts.size + " converted STLs; " +
                                "Slice can run and the agent can edit the design"
                            keptDerived -> "CAD agent ready · " + doc!!.name + " · the plate " +
                                "keeps its split layout made from the previous shape - press " +
                                "Rebuild plate to take the document's current shape"
                            doc != null -> "CAD agent ready · " + doc.name + " · " +
                                doc.bodyCount + " bodies on the plate · the agent is the only author"
                            else -> "CAD agent ready on 127.0.0.1:" + SolidCraftEngine.bridgePort() +
                                " · the plate will show the engine's document as the agent builds it"
                        },
                    )
                }
            } catch (error: Throwable) {
                showOperationFailure(error)
            }
        }
    }

    /**
     * Ends the engine. The document and its plate picture stay: a Slice starts the engine
     * again for the export it needs, and reconnecting reopens the design.
     */
    fun stopCadAgent() {
        SolidCraftEngine.onDocumentChanged = null
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "Stopping the CAD agent…") }
            withContext(Dispatchers.IO) {
                cadPersistLock.withLock { persistCadWorkspaceNow() }
                SolidCraftEngine.stop()
            }
            cadOpenDesignPath = null
            val doc = _uiState.value.cadDocument
            _uiState.update {
                it.copy(
                    statusMessage = if (doc != null) {
                        "CAD agent stopped · " + doc.name + " stays on the plate; Slice will " +
                            "export it and the agent can be connected again"
                    } else {
                        "CAD agent stopped"
                    },
                )
            }
        }
    }

    /**
     * Applies the CAD agent sheet's **Reachable on the network** switch.
     *
     * The app publishes Blender's, the CAD engine's and SolidCraft's loopback MCP ports on the
     * device's network address (see [AgentBridges]); each engine's own token check is
     * unchanged and the forwarder adds none. This only reports what happened to the sheet.
     */
    fun setCadBridgeNetworkReachable(enabled: Boolean) {
        viewModelScope.launch {
            val problem = withContext(Dispatchers.IO) { AgentBridges.setEnabled(app, enabled) }
            _uiState.update {
                it.copy(
                    statusMessage = problem ?: if (enabled) {
                        "Agent bridges published on this device's network address · each " +
                            "engine's token is still the only check"
                    } else {
                        "Agent bridges back on 127.0.0.1 only · this device only"
                    },
                )
            }
        }
    }

    /** Takes the CAD document off the plate. The design file stays in the workspace. */
    fun closeCadDocument() {
        SolidCraftEngine.onDocumentChanged = null
        viewModelScope.launch {
            val document = _uiState.value.cadDocument
            // A live document's plate objects go with it. A converted plate keeps its meshes:
            // the human converted them into ordinary STL models, and closing the document does
            // not throw away the models on the plate.
            val liveIds = document?.parts?.map { it.objectId }.orEmpty().toSet() +
                listOfNotNull(document?.displayObjectId) + cadDerivedPlateIds
            val convertedCount = document?.convertedParts?.size ?: 0
            withContext(Dispatchers.IO) {
                cadPersistLock.withLock { persistCadWorkspaceNow() }
                SolidCraftEngine.stop()
            }
            cadOpenDesignPath = null
            cadBodies.clear()
            cadPartIds.clear()
            cadDerivedPlateIds.clear()
            cadComposerComplete = false
            placementHistory.clear()
            val resetNote = resetInAppConversationForDocumentSwitch()
            _uiState.update { state ->
                state.copy(
                    models = state.models.filterNot { it.id in liveIds },
                    selectedModelId = state.models.firstOrNull { it.id !in liveIds }?.id,
                    cadDocument = null,
                ).withoutPublishedSlice("CAD document closed" + resetNote).afterPlateReplaced()
            }
            withContext(Dispatchers.IO) {
                runCatching { workspaceStore.save(workspaceSnapshot(_uiState.value) ?: return@runCatching) }
            }
            _uiState.update {
                it.copy(
                    statusMessage = (if (convertedCount > 0) {
                        "CAD document closed; its " + convertedCount + " converted part" +
                            (if (convertedCount == 1) "" else "s") + " stay on the plate as STL " +
                            "models. The design file is kept in the SolidCraft workspace."
                    } else {
                        "CAD document closed; its design file is kept in the SolidCraft " +
                            "workspace and the plate can import a mesh again"
                    }) + resetNote,
                )
            }
            dropFaceSelection("the CAD document was closed")
        }
    }

    /**
     * Re-derives the plate from the document's current shape.
     *
     * The way out of a stale plate: the split or snap layout made from the previous shape is
     * dropped (its objects removed, and it is named in the status), the document object is
     * rebuilt at the document's own coordinates, and the plate is the current shape again.
     */
    fun rebuildCadPlateFromDocument() {
        val document = _uiState.value.cadDocument ?: return
        if (_uiState.value.isBusy) return
        _uiState.value.cadRebuildBlockedReason?.let { reason ->
            _uiState.update { it.copy(statusMessage = reason) }
            return
        }
        if (!File(document.designPath).isFile) {
            _uiState.update {
                it.copy(statusMessage = CadDesignLibrary.missingDesignSentence(document.converted))
            }
            return
        }
        if (!beginOperation("Rebuilding the plate from the document…")) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!ensureCadEngine()) throw IllegalStateException(SolidCraftEngine.status(app))
                    val session = SolidCraftEngine.session()
                        ?: throw IllegalStateException("the CAD agent session disappeared")
                    openCadDesign(session, document)
                    if (!refreshCadAllBodies(session, cadWorkspace(), document.name)) {
                        throw IllegalStateException("the engine reported no bodies to draw")
                    }
                }
                val dropped = _uiState.value.models.count { it.id in cadDerivedPlateIds }
                publishCadParts(
                    name = document.name,
                    designPath = document.designPath,
                    sourcePath = document.sourcePath,
                    editMillis = null,
                    replaceDerived = true,
                )
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Plate rebuilt from the CAD document" +
                            if (dropped > 0) {
                                " · the " + dropped + "-object split layout made from the " +
                                    "previous shape was dropped"
                            } else if (document.derivedStale) {
                                " · the previous plate layout was replaced by the document"
                            } else {
                                ""
                            },
                    )
                }
                Diagnostics.info("cad", "plate rebuilt from " + document.name)
                dropFaceSelection("the plate was rebuilt from the document")
            } catch (error: Throwable) {
                showOperationFailure(error)
            }
        }
    }

    /**
     * Opens a STEP/IGES/3MF/OBJ/glTF file as the engine's live CAD document.
     *
     * This is the CAD route into the app, and it is deliberately separate from Import model:
     * that one still turns STEP/IGES into a mesh through OpenCASCADE, while this hands the
     * file to the engine's own io crate and keeps it as a document the agent can edit. The
     * status line says which of the two the user got.
     */
    fun openCadDocument(uri: Uri) {
        if (deferUntilRestoreCompletes { openCadDocument(uri) }) return
        if (!beginOperation("Opening the CAD document…")) return
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    retainReadPermission(uri)
                    val sourceName = displayName(uri)
                    val extension = sourceName.substringAfterLast('.', "").lowercase()
                    if (extension !in CAD_DOCUMENT_EXTENSIONS) {
                        throw IllegalStateException(
                            "SolidCraft opens STEP, IGES, 3MF, OBJ and glTF files; " +
                                sourceName + " is not one of them",
                        )
                    }
                    if (!ensureCadEngine()) throw IllegalStateException(SolidCraftEngine.status(app))
                    val session = SolidCraftEngine.session()
                        ?: throw IllegalStateException("the CAD agent session disappeared")
                    val workspace = cadWorkspace()
                    val staged = workspace.importFile(sourceName)
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        staged.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("could not read " + sourceName)
                    session.open(staged.absolutePath)
                    val baseName = sourceName.substringBeforeLast('.', sourceName)
                        .ifBlank { "CAD document" }
                    // An Open whose name is already another design in the workspace must not
                    // overwrite it: the design file is the app's only source of truth.
                    val design = workspace.uniqueDesignFile(baseName)
                    val name = design.nameWithoutExtension
                    session.save(design.absolutePath)
                    cadOpenDesignPath = design.absolutePath
                    if (!refreshCadAllBodies(session, workspace, name)) {
                        throw IllegalStateException(
                            "the engine opened " + sourceName + " but reported no bodies to draw",
                        )
                    }
                    PreparedCadOpen(name, sourceName, design, cadBodies.count, cadBodies.triangleCount())
                }
                // An opened file is a new document, not an edit of whatever was on the plate:
                // it replaces the document's plate object (and any split layout made from the
                // old one), exactly as opening a model does in mesh mode.
                publishCadParts(
                    name = prepared.name,
                    designPath = prepared.design.absolutePath,
                    sourcePath = prepared.sourceName,
                    editMillis = null,
                    replaceDerived = true,
                )
                val resetNote = resetInAppConversationForDocumentSwitch()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Opened " + prepared.sourceName + " as a CAD document " +
                            "(SolidCraft engine) · " + prepared.bodies + " bodies · " +
                            "%,d".format(prepared.triangles) +
                            " triangles display tessellation · saved as " + prepared.design.name +
                            " · the agent can now edit it" + resetNote,
                    )
                }
                scheduleCadPersist(force = true)
                dropFaceSelection("a different CAD document was opened")
            } catch (error: Throwable) {
                showOperationFailure(error)
            }
        }
    }

    /**
     * Writes STEP/STL/3MF on demand: the human taking the result out, not editing it.
     */
    fun exportCadDocument(uri: Uri, format: String) {
        val doc = _uiState.value.cadDocument ?: return
        if (!beginOperation("Exporting the CAD document as " + format.uppercase() + "…")) return
        viewModelScope.launch {
            try {
                val written = withContext(Dispatchers.IO) {
                    if (!ensureCadEngine()) throw IllegalStateException(SolidCraftEngine.status(app))
                    val session = SolidCraftEngine.session()
                        ?: throw IllegalStateException("the CAD agent session disappeared")
                    openCadDesign(session, doc)
                    val target = cadWorkspace().exportFile(doc.name, format)
                    session.export(target.absolutePath, format = format.filter { it.isLetterOrDigit() })
                    check(target.isFile && target.length() > 0L) {
                        "the engine wrote no " + format.uppercase() + " data"
                    }
                    app.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        target.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Unable to open the export destination")
                    target
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Exported " + doc.name + " as " + format.uppercase() +
                            " (" + formatFileSize(written.length()) + ") · the document is unchanged",
                    )
                }
            } catch (error: Throwable) {
                showOperationFailure(error)
            }
        }
    }

    // --- The SolidCraft workspace's designs -------------------------------------------------

    /** The workspace the SolidCraft files screen lists and loads from. */
    fun solidCraftWorkspace(): CadWorkspace = cadWorkspace()

    /**
     * The human's own step from the live CAD document to the STLs a Slice reads.
     *
     * Each body is exported on its own into the workspace's converted/ folder and becomes an
     * ordinary plate object: named after the body, at the placement the human had already given
     * that part, with the exported file as its source. The document stays open in the engine -
     * the agent can keep editing it - and the plate now holds STL models instead of live
     * geometry, which is exactly the state Slice needs. Nothing else converts: Slice, Export, a
     * repaint and a file load all leave this press to the human.
     */
    fun convertCadToStl() {
        val state = _uiState.value
        val document = state.cadDocument ?: return
        if (state.isBusy) return
        // A running turn owns the engine session: converting beside it can interleave the
        // conversion's inspect/export with the agent's tool calls. Refused by name, and the
        // plate disables the press from the same MainUiState fact.
        state.cadConvertBlockedReason?.let { reason ->
            _uiState.update { it.copy(statusMessage = reason) }
            return
        }
        // The converted plate's design file can be deleted from SolidCraft files while its STLs
        // stay on the plate: name that instead of telling the user to reconnect.
        if (!File(document.designPath).isFile) {
            _uiState.update {
                it.copy(statusMessage = CadDesignLibrary.missingDesignSentence(document.converted))
            }
            return
        }
        // A split/snap layout is geometry made from this document: converting it would silently
        // drop the human's layout or leave the halves beside the whole converted parts (a
        // duplicated print). Refuse by name and point at the one press that takes the document back.
        val derived = if (cadDerivedPlateIds.isNotEmpty()) {
            cadDerivedPlateIds
        } else if (document.derivedStale) {
            setOf("the previous plate layout")
        } else {
            emptySet()
        }
        CadConvert.refusalFor(derived)?.let { refusal ->
            _uiState.update { it.copy(statusMessage = refusal) }
            return
        }
        val again = document.converted
        if (!beginOperation(
                if (again) "Converting the CAD document again…" else "Converting the CAD document to STL…",
            )
        ) {
            return
        }
        viewModelScope.launch {
            try {
                val exported = withContext(Dispatchers.IO) {
                    if (!ensureCadEngine()) throw IllegalStateException(SolidCraftEngine.status(app))
                    val session = SolidCraftEngine.session()
                        ?: throw IllegalStateException("the CAD agent session disappeared")
                    openCadDesign(session, document)
                    val workspace = cadWorkspace()
                    val bodies = cadBodyNames(session)
                    if (bodies.isEmpty()) {
                        throw IllegalStateException("the design has no bodies to convert")
                    }
                    val steps = bodies.map { body ->
                        ToolStep(
                            command = "file.save_mesh",
                            params = JSONObject()
                                .put("path", workspace.convertedFile(document.name, body).absolutePath)
                                .put("format", "stl")
                                .put("bodies", JSONArray().put(body)),
                        )
                    }
                    session.batch(steps, rollback = false)
                    bodies.map { body ->
                        val file = workspace.convertedFile(document.name, body)
                        if (!file.isFile || file.length() == 0L) {
                            throw IllegalStateException("the engine wrote no STL for " + body)
                        }
                        CadConvertedPart(
                            body = body,
                            mesh = StlParser.parse(file, body, MeshTriangleLimits.current()),
                            stlPath = file.absolutePath,
                        )
                    }
                }
                val replacing = if (document.converted) document.convertedParts else document.parts
                val plan = CadConvert.plan(
                    exported,
                    _uiState.value.models,
                    replacing,
                    legacyObjectId = document.displayObjectId,
                )
                cadPartIds.clear()
                for (part in plan.parts) cadPartIds[part.body] = part.objectId
                val documentAfter = document.copy(
                    displayObjectId = null,
                    displayMeshPath = null,
                    parts = emptyList(),
                    converted = true,
                    convertedRevision = document.displayRevision,
                    convertedParts = plan.parts,
                    triangleCount = plan.triangleCount,
                    bodyCount = plan.parts.size,
                    updating = false,
                    derivedStale = false,
                    staleReason = null,
                )
                val paintNote = if (plan.paintDropped.isNotEmpty()) {
                    " · painted supports were cleared on " + plan.paintDropped.joinToString(", ") +
                        " because the exported mesh changed size"
                } else {
                    ""
                }
                val status = "Converted " + document.name + (if (again) " again" else "") +
                    " to STL: " + plan.parts.size + " part" +
                    (if (plan.parts.size == 1) "" else "s") + ", " +
                    "%,d".format(plan.triangleCount) + " triangles · Slice can run now · the " +
                    "STLs are in the SolidCraft workspace's converted folder" + paintNote
                cadDerivedPlateIds.clear()
                placementHistory.clear()
                _uiState.update { current ->
                    current.copy(
                        models = plan.models,
                        selectedModelId = current.selectedModelId
                            ?.takeIf { id -> plan.models.any { it.id == id } }
                            ?: plan.parts.firstOrNull()?.objectId,
                        cadDocument = documentAfter,
                        isBusy = false,
                    ).withoutPublishedSlice(status).afterPlateReplaced()
                }
                // Unconditionally: after a conversion the plate holds STL objects, so even an
                // armed-but-empty face mode would leave pixels waiting for a document that is no
                // longer drawn. dropFaceSelection is a no-op when nothing is selected or armed.
                run {
                    dropFaceSelection("the document was converted to STL; the plate holds mesh models now")
                }
                Diagnostics.info(
                    "cad",
                    "converted " + document.name + ": " + plan.parts.size + " parts, " +
                        plan.triangleCount + " triangles",
                )
                scheduleCadPersist(force = true)
            } catch (error: Throwable) {
                showOperationFailure(error)
            }
        }
    }

    /**
     * Starts a fresh design and clears the document's plate objects.
     *
     * The engine owns the document, so this is the engine's own New Design: saved into the
     * workspace so the files screen lists it and a later session can open it. The previous
     * document's plate objects go - they belonged to the design that is gone - and the plate is
     * ready for the agent's first body.
     */
    fun newCadDesign() {
        if (_uiState.value.isBusy) return
        if (!beginOperation("Starting a new CAD design…")) return
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    if (!ensureCadEngine()) throw IllegalStateException(SolidCraftEngine.status(app))
                    val session = SolidCraftEngine.session()
                        ?: throw IllegalStateException("the CAD agent session disappeared")
                    session.newDesign()
                    val workspace = cadWorkspace()
                    // Never overwrite an existing design: the engine's default name is
                    // "Untitled" for every new design, which used to destroy the previous one.
                    val design = workspace.uniqueDesignFile(cadDesignName(session))
                    val name = design.nameWithoutExtension
                    session.save(design.absolutePath)
                    cadOpenDesignPath = design.absolutePath
                    cadBodies.clear()
                    cadComposerComplete = true
                    CadDesignLibrary.writeMeta(workspace, design, name, 0, System.currentTimeMillis())
                    PreparedCadLoad(name = name, design = design, parts = 0, triangles = 0)
                }
                val state = _uiState.value
                val dropped = state.cadDocument?.parts?.map { it.objectId }.orEmpty()
                    .plus(state.cadDocument?.convertedParts?.map { it.objectId }.orEmpty())
                    .plus(listOfNotNull(state.cadDocument?.displayObjectId))
                    .plus(cadDerivedPlateIds)
                cadPartIds.clear()
                cadDerivedPlateIds.clear()
                placementHistory.clear()
                val document = CadDocument(
                    name = prepared.name,
                    designPath = prepared.design.absolutePath,
                    displayRevision = (state.cadDocument?.displayRevision ?: 0L) + 1L,
                )
                val resetNote = resetInAppConversationForDocumentSwitch()
                _uiState.update { current ->
                    val models = current.models.filterNot { it.id in dropped }
                    current.copy(
                        models = models,
                        selectedModelId = current.selectedModelId
                            ?.takeIf { id -> models.any { it.id == id } }
                            ?: models.firstOrNull()?.id,
                        cadDocument = document,
                        isBusy = false,
                    ).withoutPublishedSlice(
                        "Started a new CAD design: " + prepared.name +
                            " · the plate is empty and the agent can build the first body" +
                            resetNote,
                    ).afterPlateReplaced()
                }
                dropFaceSelection("a new CAD design was started")
                scheduleCadPersist(force = true)
            } catch (error: Throwable) {
                showOperationFailure(error)
            }
        }
    }

    /** Saves the live design into the SolidCraft workspace, where the files screen lists it. */
    fun saveCadDesign() {
        val document = _uiState.value.cadDocument ?: return
        if (_uiState.value.isBusy) return
        if (!beginOperation("Saving the CAD design…")) return
        viewModelScope.launch {
            try {
                val design = withContext(Dispatchers.IO) {
                    if (!ensureCadEngine()) throw IllegalStateException(SolidCraftEngine.status(app))
                    val session = SolidCraftEngine.session()
                        ?: throw IllegalStateException("the CAD agent session disappeared")
                    openCadDesign(session, document)
                    val file = File(document.designPath)
                    session.save(file.absolutePath)
                    check(file.isFile && file.length() > 0L) { "the engine wrote no design file" }
                    CadDesignLibrary.writeMeta(
                        workspace = cadWorkspace(),
                        design = file,
                        name = document.name,
                        partCount = maxOf(
                            cadBodies.count,
                            document.parts.size,
                            document.convertedParts.size,
                        ),
                        savedAtMillis = System.currentTimeMillis(),
                    )
                    file
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Saved " + document.name + " to the SolidCraft workspace (" +
                            formatFileSize(design.length()) + ") · SolidCraft files lists it",
                    )
                }
                scheduleCadPersist(force = true)
            } catch (error: Throwable) {
                showOperationFailure(error)
            }
        }
    }

    /**
     * Opens one design from the SolidCraft workspace as the plate's live document.
     *
     * The files screen's own action: the design file is the engine's source of truth, so this
     * is the same open an imported file gets, without a new file to stage. Whatever the plate
     * was showing of a previous document - live parts or converted STLs - is replaced by this
     * design's parts; the previous design file stays in the workspace untouched.
     */
    fun loadCadDesign(designPath: String) {
        if (_uiState.value.isBusy) return
        val design = File(designPath)
        if (!design.isFile) {
            _uiState.update {
                it.copy(statusMessage = "That design is gone from the SolidCraft workspace: " + design.name)
            }
            return
        }
        if (!beginOperation("Loading " + design.name + "…")) return
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    if (!ensureCadEngine()) throw IllegalStateException(SolidCraftEngine.status(app))
                    val session = SolidCraftEngine.session()
                        ?: throw IllegalStateException("the CAD agent session disappeared")
                    session.open(design.absolutePath)
                    cadOpenDesignPath = design.absolutePath
                    val name = runCatching { cadDesignName(session) }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: design.nameWithoutExtension
                    if (!refreshCadAllBodies(session, cadWorkspace(), name)) {
                        throw IllegalStateException(
                            "the engine opened " + design.name + " but reported no bodies to draw",
                        )
                    }
                    PreparedCadLoad(
                        name = name,
                        design = design,
                        parts = cadBodies.count,
                        triangles = cadBodies.triangleCount(),
                    )
                }
                val previous = _uiState.value.cadDocument
                val dropped = previous?.parts?.map { it.objectId }.orEmpty()
                    .plus(previous?.convertedParts?.map { it.objectId }.orEmpty())
                    .plus(listOfNotNull(previous?.displayObjectId))
                    .plus(cadDerivedPlateIds)
                cadDerivedPlateIds.clear()
                cadPartIds.clear()
                placementHistory.clear()
                _uiState.update { current ->
                    val models = current.models.filterNot { it.id in dropped }
                    current.copy(
                        models = models,
                        selectedModelId = current.selectedModelId
                            ?.takeIf { id -> models.any { it.id == id } }
                            ?: models.firstOrNull()?.id,
                        cadDocument = null,
                    )
                }
                publishCadParts(
                    name = prepared.name,
                    designPath = prepared.design.absolutePath,
                    sourcePath = null,
                    editMillis = null,
                    replaceDerived = true,
                )
                val resetNote = resetInAppConversationForDocumentSwitch()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Loaded " + prepared.design.name + " from the SolidCraft " +
                            "workspace: " + prepared.parts + " part" +
                            (if (prepared.parts == 1) "" else "s") + " · " +
                            "%,d".format(prepared.triangles) + " triangles · it is the live " +
                            "document now, and Slice needs Convert to STL" + resetNote,
                    )
                }
                dropFaceSelection("a design from the SolidCraft workspace was loaded")
                scheduleCadPersist(force = true)
            } catch (error: Throwable) {
                showOperationFailure(error)
            }
        }
    }

    /** One prepared open, so the plate update happens on the main-thread coroutine. */
    private data class PreparedCadOpen(
        val name: String,
        val sourceName: String,
        val design: File,
        val bodies: Int,
        val triangles: Int,
    )

    /** One prepared workspace load, on the main thread. */
    private data class PreparedCadLoad(
        val name: String,
        val design: File,
        val parts: Int,
        val triangles: Int,
    )

    /**
     * The live-view callback: an agent's edit landed, repaint what it changed.
     *
     * The callback arrives on the bridge's thread. Repaints are serialised by [cadRepaintLock]
     * and each one is cheap by construction - the engine exports only the bodies the edit named
     * and their triangles are patched into the composer, so the common edit costs one engine
     * round trip plus one small batch of exports, not a re-read of the document.
     */
    private fun onSolidCraftDelta(delta: DocumentDelta) {
        if (SolidCraftEngine.session() == null) return
        viewModelScope.launch(Dispatchers.IO) {
            cadRepaintLock.withLock { repaintCad(delta) }
        }
    }

    private suspend fun repaintCad(delta: DocumentDelta) {
        val session = SolidCraftEngine.session() ?: return
        val workspace = cadWorkspace()
        val receivedAt = System.currentTimeMillis()
        val startNanos = System.nanoTime()
        val previous = _uiState.value.cadDocument
        val openedName = delta.openedPath
            ?.let { File(it).nameWithoutExtension }
            ?.takeIf { it.isNotBlank() }
        val name = openedName
            ?: previous?.name
            ?: runCatching { cadDesignName(session) }.getOrNull()
            ?: "CAD document"
        val design = workspace.designFile(name)
        // A converted plate is not live geometry any more: the document moved on under the
        // human's converted STLs, so the repaint names that instead of pasting a new shape.
        if (previous?.converted == true) {
            markCadStale(
                previous = previous,
                name = name,
                designPath = previous.designPath,
                reason = "the agent changed the CAD document after it was converted; the plate " +
                    "still shows the converted STLs - press Convert again to take the new shape",
                editMillis = System.currentTimeMillis() - receivedAt,
            )
            return
        }
        // A split or snap layout is geometry the human made from the previous shape: never
        // paste the document's new shape over it. The persisted derivedStale flag keeps that
        // rule across a relaunch, when the in-memory id set would otherwise be empty.
        if (derivedLayoutOnPlate(previous)) {
            markCadStale(
                previous = previous,
                name = name,
                designPath = design.absolutePath,
                reason = "the agent changed the document, but the plate holds a split or snap " +
                    "layout made from its previous shape. The plate shows the previous shape; " +
                    "press Rebuild plate in the CAD agent sheet to take the new one.",
                editMillis = System.currentTimeMillis() - receivedAt,
            )
            return
        }
        // A cleared plate (or a closed document) has no composer to patch: read the whole
        // document back first, so the first edit after Clear cannot rebuild the plate from only
        // the bodies it happened to name (and a removal-only edit cannot report an empty design
        // while the engine still holds every body).
        val composerIncomplete = !cadComposerComplete || cadBodies.count == 0
        markCadUpdating(true)
        var incremental = false
        if (!composerIncomplete && !delta.fullRefresh && delta.repaintBodies.isNotEmpty()) {
            val loaded = exportCadBodies(session, workspace, name, delta.repaintBodies)
            if (loaded != null && CadLiveUpdate.applyIncremental(cadBodies, delta) { loaded[it] }) {
                incremental = true
            }
        } else if (!composerIncomplete && !delta.fullRefresh) {
            // A removed-only edit (a delete): no triangles to export, the composer drops them.
            cadBodies.remove(delta.removedBodies)
            incremental = true
        }
        val exportedNanos = System.nanoTime()
        if (!incremental) {
            // The honest fallback for the edits that do not name bodies (undo, a parameter
            // rebuild, an opened file): re-read the document body by body.
            if (!refreshCadAllBodies(session, workspace, name)) {
                if (cadDocumentHasNoBodies(session)) {
                    // An empty document is a real state (an agent's new_design, or an opened
                    // file with nothing in it): the previous design's picture must not stay on
                    // the plate under a status that says there is nothing to draw.
                    dropCadPictureForEmptyDocument(previous, name, design.absolutePath, delta, receivedAt)
                } else {
                    // The engine did not answer the re-read (a timeout, a busy session): keep
                    // the last picture rather than taking a working plate down for it.
                    markCadUpdating(false)
                    _uiState.update {
                        it.copy(
                            statusMessage = "CAD document: the engine did not answer the " +
                                "document re-read; the plate keeps the last picture",
                        )
                    }
                }
                return
            }
        }
        if (cadBodies.count == 0) {
            markCadUpdating(false)
            dropFaceSelection("the design has no bodies now")
            _uiState.update { it.copy(statusMessage = "CAD document: the design is empty") }
            return
        }
        // The source of truth is saved as soon as it exists, not only on a timer: the design
        // file is what a later session opens, and losing one edit to a crash would lose the
        // agent's work rather than the picture of it.
        val designPath = design.absolutePath
        if (!File(designPath).isFile || delta.fullRefresh || delta.openedPath != null) {
            runCatching {
                session.save(designPath)
                cadOpenDesignPath = designPath
            }.onFailure { Log.w("MainViewModel", "could not save the CAD design", it) }
        }
        val meshDoneNanos = System.nanoTime()
        val published = publishCadParts(
            name = name,
            designPath = designPath,
            sourcePath = delta.openedPath ?: previous?.sourcePath,
            editMillis = System.currentTimeMillis() - receivedAt,
        )
        val stateDoneNanos = System.nanoTime()
        if (!published) return
        val triangles = cadBodies.triangleCount()
        val millis = fun (from: Long, to: Long) = (to - from) / 1_000_000
        Log.i(
            "MainViewModel",
            "cad live: " + delta.tool + (delta.command?.let { " " + it } ?: "") +
                " · call→export " + millis(startNanos, exportedNanos) + " ms" +
                " · export→mesh " + millis(exportedNanos, meshDoneNanos) + " ms" +
                " · mesh→state " + millis(meshDoneNanos, stateDoneNanos) + " ms" +
                " · total " + millis(startNanos, stateDoneNanos) + " ms" +
                " · " + cadBodies.count + " bodies · " + triangles + " triangles",
        )
        Diagnostics.info(
            "cad",
            "repainted " + name + " after " + delta.tool + ": " + triangles +
                " triangles from " + cadBodies.count + " bodies",
        )
        if (composerIncomplete) {
            _uiState.update {
                it.copy(
                    statusMessage = (it.statusMessage ?: "CAD document") +
                        " · the plate had been cleared, so the whole document was read back",
                )
            }
        }
        scheduleCadPersist()
    }

    /**
     * Takes the document's picture off the plate when the engine reports it holds no bodies.
     *
     * The status sentence stays ("the engine has no bodies to draw yet"), the document record
     * survives one display revision further, and the plate object the document was drawn in is
     * dropped - so a new_design (or an empty open) leaves a clean plate instead of the previous
     * part standing there with a message about a document that is gone.
     */
    private fun dropCadPictureForEmptyDocument(
        previous: CadDocument?,
        name: String,
        designPath: String,
        delta: DocumentDelta,
        receivedAt: Long,
    ) {
        val dropped = previous?.parts?.map { it.objectId }.orEmpty()
            .plus(previous?.convertedParts?.map { it.objectId }.orEmpty())
            .plus(listOfNotNull(previous?.displayObjectId))
        cadBodies.clear()
        cadPartIds.clear()
        // An empty design is a complete picture: the composer is not missing bodies, there are
        // none. The next edit may patch incrementally.
        cadComposerComplete = true
        cadRevisionAtMillis = System.currentTimeMillis()
        _uiState.update {
            it.withoutCadBodies(
                partObjectIds = dropped,
                name = name,
                designPath = designPath,
                sourcePath = delta.openedPath ?: previous?.sourcePath,
                editMillis = if (delta.fullRefresh) System.currentTimeMillis() - receivedAt else null,
            )
        }
        dropFaceSelection("the document has no bodies to draw")
        scheduleCadPersist()
    }

    /**
     * True when the engine's design holds no bodies, as opposed to a re-read that failed.
     *
     * [refreshCadAllBodies] answers null both for an empty document and for an engine that did
     * not answer; the plate is cleared only for the first. This is the one extra engine question
     * on that cold path, and only after the re-read has already failed.
     */
    private fun cadDocumentHasNoBodies(session: SolidCraftSession): Boolean =
        runCatching { cadBodyNames(session).isEmpty() }.getOrDefault(false)

    /** Marks the document as being repainted, without touching the picture or the camera. */
    private fun markCadUpdating(updating: Boolean) {
        _uiState.update { state ->
            val doc = state.cadDocument ?: return@update state
            if (doc.updating == updating) state else state.copy(cadDocument = doc.copy(updating = updating))
        }
    }

    /**
     * Puts one freshly composed tessellation on the plate, in place.
     *
     * The same plate object id, the same name and the same camera: the model view resets its
     * camera when a mesh arrives under a different name or the plate's object count changes,
     * and the camera belongs to the spectator. The placement is the document's own coordinates
     * (exactly the identity), because where the agent puts the part in the design is where it
     * prints - the app adds no placement of its own.
     */
    /**
     * Puts the document's parts on the plate, one object per body, in place.
     *
     * The same plate object ids, the same names and the same object count: the model view
     * resets its camera when a mesh arrives under a different name or the plate's object count
     * changes, and the camera belongs to the spectator. Each part keeps its own placement - the
     * human's move, turn and scale relative to the old tessellation - and its paint while the
     * triangle count has not changed; [CadPartPublish] is the rule, this is the view-model half
     * that runs it against the real plate.
     *
     * A repaint that must not paste never reaches here: a split/snap layout made from the old
     * shape, and a converted plate, are marked stale by [markCadStale] instead.
     */
    private fun publishCadParts(
        name: String,
        designPath: String,
        sourcePath: String?,
        editMillis: Long?,
        replaceDerived: Boolean = false,
    ): Boolean {
        val state = _uiState.value
        val previous = state.cadDocument
        // Only a split (or a jointed pair) is stale geometry made from the previous shape. An
        // object the user REMOVED from the plate is not stale: the next agent edit brings the
        // document back as fresh plate objects, the way an import would.
        if (derivedLayoutOnPlate(previous) && !replaceDerived) {
            markCadStale(
                previous = previous,
                name = name,
                designPath = designPath,
                reason = "the agent changed the document, but the plate holds a split or snap " +
                    "layout made from its previous shape. The plate shows the previous shape; " +
                    "press Rebuild plate in the CAD agent sheet to take the new one.",
                editMillis = editMillis,
            )
            return false
        }
        // A converted plate is the human's STLs now: taking the live document again is an
        // explicit act (Rebuild plate, opening a file, loading a design), never a repaint.
        if (previous?.converted == true && !replaceDerived) {
            markCadStale(
                previous = previous,
                name = name,
                designPath = designPath,
                reason = "the agent changed the CAD document after it was converted; the plate " +
                    "still shows the converted STLs - press Convert again to take the new shape",
                editMillis = editMillis,
            )
            return false
        }
        val workspace = cadWorkspace()
        val modelsBefore = if (replaceDerived) {
            state.models.filterNot { it.id in cadDerivedPlateIds }
        } else {
            state.models
        }
        if (replaceDerived) {
            cadDerivedPlateIds.clear()
            // Every object the plate was showing from the document is replaced, so a placement
            // history recorded against those objects would undo onto a different object.
            placementHistory.clear()
        }
        // Taking the live document again drops the converted layout: those meshes were made
        // from the document, and the plate now shows the document itself.
        val convertedIds = previous?.convertedParts?.map { it.objectId }.orEmpty().toSet()
        val modelsForPlan = modelsBefore.filterNot { it.id in convertedIds }
        val plan = CadPartPublish.plan(
            bodies = cadBodies.entries(),
            models = modelsForPlan,
            previousParts = if (previous?.converted == true) emptyList() else previous?.parts.orEmpty(),
            displayPath = { body -> workspace.bodyFile(name, body).absolutePath },
            legacyObjectId = previous?.displayObjectId,
        )
        cadPartIds.clear()
        for (part in plan.parts) cadPartIds[part.body] = part.objectId
        val document = (previous ?: CadDocument(name = name, designPath = designPath)).copy(
            name = name,
            designPath = designPath,
            sourcePath = sourcePath ?: previous?.sourcePath,
            displayObjectId = null,
            displayMeshPath = null,
            parts = plan.parts,
            converted = false,
            convertedRevision = -1L,
            convertedParts = emptyList(),
            displayRevision = (previous?.displayRevision ?: 0L) + 1L,
            triangleCount = plan.triangleCount,
            bodyCount = plan.parts.size,
            updating = false,
            derivedStale = false,
            staleReason = null,
            lastEditMillis = editMillis ?: previous?.lastEditMillis,
            editCount = (previous?.editCount ?: 0) + if (editMillis != null) 1 else 0,
        )
        _uiState.update { current ->
            val selected = current.selectedModelId?.takeIf { id -> plan.models.any { it.id == id } }
                ?: plan.parts.firstOrNull()?.objectId
            val next = current.copy(
                models = plan.models,
                selectedModelId = selected,
                cadDocument = document,
            ).withoutPublishedSlice(current.statusMessage)
            if (replaceDerived) next.afterPlateReplaced() else next
        }
        val paintNote = if (plan.paintDropped.isNotEmpty()) {
            " · painted supports were cleared on " + plan.paintDropped.joinToString(", ") +
                " because the shape's triangle count changed"
        } else {
            ""
        }
        val renameNote = if (plan.renamed.isNotEmpty()) {
            " · renamed " + plan.renamed.joinToString(", ") { (from, to) -> from + " -> " + to } +
                " (placement and paint kept)"
        } else {
            ""
        }
        _uiState.update {
            it.copy(
                statusMessage = cadStatusLine(it.cadDocument ?: document) + paintNote + renameNote,
            )
        }
        Diagnostics.info(
            "cad",
            "display " + name + ": " + plan.triangleCount + " triangles from " +
                plan.parts.size + " parts, revision " + document.displayRevision,
        )
        // Every republish renumbers the plate's triangles: a selected face is re-resolved by
        // NAME against the part that now exists, so the amber follows the face the edit kept
        // and a face the edit removed drops the selection with a sentence naming it.
        if (_uiState.value.selectedFace != null) {
            viewModelScope.launch(Dispatchers.IO) {
                SolidCraftEngine.session()?.let { reapplySelectedFace(it) }
            }
        }
        return true
    }

    /**
     * Records that the plate no longer matches the document, with the sentence that says why.
     *
     * Nothing on the plate changes except the status and the document's stale flag: the plate
     * shows the shape it already had (a split layout, or the converted STLs), Slice refuses it
     * with this reason, and the way forward is named in the sentence.
     */
    private fun markCadStale(
        previous: CadDocument?,
        name: String,
        designPath: String,
        reason: String,
        editMillis: Long? = null,
    ) {
        val document = (previous ?: CadDocument(name = name, designPath = designPath)).copy(
            name = name,
            designPath = designPath,
            updating = false,
            bodyCount = cadBodies.count,
            derivedStale = true,
            staleReason = reason,
            lastEditMillis = editMillis ?: previous?.lastEditMillis,
            editCount = (previous?.editCount ?: 0) + 1,
        )
        _uiState.update { it.copy(cadDocument = document, statusMessage = reason) }
        scheduleCadPersist()
    }

    /** The status line for a live document: what it is, how big, and what its plate holds. */
    private fun cadStatusLine(document: CadDocument): String = buildString {
        append("CAD document ").append(document.name)
        if (document.converted) {
            append(" · converted to ").append(document.convertedParts.size).append(" STL part")
            if (document.convertedParts.size != 1) append('s')
            append(" · ").append("%,d".format(document.triangleCount)).append(" triangles on the plate")
        } else {
            append(" · ").append(document.parts.size).append(" part")
            if (document.parts.size != 1) append('s')
            append(" on the plate · ").append("%,d".format(document.triangleCount)).append(" triangles")
        }
        document.lastEditMillis?.let { append(" · last edit ").append(it).append(" ms") }
        if (document.derivedStale) {
            append(" · stale: ").append(document.staleReason ?: "convert or rebuild it")
        }
    }

    /** The first frame after a repaint, reported by the plate, so the latency has an end. */
    fun onCadFrame(displayRevision: Long) {
        val document = _uiState.value.cadDocument ?: return
        if (document.displayRevision != displayRevision) return
        val frameMillis = System.currentTimeMillis() - cadRevisionAtMillis
        Log.i(
            "MainViewModel",
            "cad frame: revision " + displayRevision + " composed and drawn " + frameMillis +
                " ms after the edit reached the state",
        )
    }

    /**
     * Exports the named bodies and parses them, or null when the engine could not.
     *
     * One batch call for every body keeps the round trip count at one; the files are separate
     * because the composer patches bodies separately.
     */
    private fun exportCadBodies(
        session: SolidCraftSession,
        workspace: CadWorkspace,
        name: String,
        bodies: List<String>,
    ): Map<String, com.tomppi.enderslicer.viewer.StlMesh>? {
        val unique = bodies.distinct().filter { it.isNotBlank() }
        if (unique.isEmpty()) return emptyMap()
        val steps = unique.map { body ->
            ToolStep(
                command = "file.export",
                params = JSONObject()
                    .put("path", workspace.bodyFile(name, body).absolutePath)
                    .put("format", "stl")
                    .put("bodies", JSONArray().put(body)),
            )
        }
        return try {
            session.batch(steps, rollback = false)
            val parsed = LinkedHashMap<String, com.tomppi.enderslicer.viewer.StlMesh>()
            for (body in unique) {
                val file = workspace.bodyFile(name, body)
                if (!file.isFile || file.length() == 0L) return null
                parsed[body] = StlParser.parse(file, body, MeshTriangleLimits.current())
            }
            parsed
        } catch (error: Throwable) {
            Log.w("MainViewModel", "the CAD engine could not export a changed body", error)
            null
        }
    }

    /**
     * Reads every body of the design and rebuilds the composer.
     *
     * The fallback for the edits that do not name bodies, and the first read after the app
     * starts: still a refresh of the same scene - one batch of exports and the plate object it
     * already has - never a file load.
     */
    private fun refreshCadAllBodies(
        session: SolidCraftSession,
        workspace: CadWorkspace,
        name: String,
    ): Boolean {
        // Until the whole document has been read the composer is not trustworthy for an
        // incremental patch; a failure leaves it unknown, not "empty".
        cadComposerComplete = false
        val bodyNames = runCatching { cadBodyNames(session) }.getOrNull() ?: return false
        cadBodies.clear()
        if (bodyNames.isEmpty()) return false
        val steps = bodyNames.map { body ->
            ToolStep(
                command = "file.export",
                params = JSONObject()
                    .put("path", workspace.bodyFile(name, body).absolutePath)
                    .put("format", "stl")
                    .put("bodies", JSONArray().put(body)),
            )
        }
        runCatching { session.batch(steps, rollback = false) }.getOrElse { return false }
        for (body in bodyNames) {
            val file = workspace.bodyFile(name, body)
            if (!file.isFile || file.length() == 0L) return false
            val mesh = runCatching {
                StlParser.parse(file, body, MeshTriangleLimits.current())
            }.getOrNull() ?: return false
            cadBodies.put(body, mesh)
        }
        cadComposerComplete = cadBodies.count > 0
        return cadBodies.count > 0
    }

    /** The design's own name, for a first document the app did not open from a file. */
    private fun cadDesignName(session: SolidCraftSession): String =
        session.inspectDesignJson(measure = false, sketches = false)
            .optString("name")
            .takeIf { it.isNotBlank() }
            ?: "CAD document"

    /** The design's body names, from the engine's own report. */
    private fun cadBodyNames(session: SolidCraftSession): List<String> {
        val design = session.inspectDesignJson(measure = false, sketches = false)
        val bodies = design.optJSONArray("bodies") ?: return emptyList()
        return (0 until bodies.length()).mapNotNull { index ->
            when (val value = bodies.opt(index)) {
                is JSONObject -> value.optString("name").takeIf { it.isNotBlank() }
                is String -> value.takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }

    /** Makes the engine's session hold [document]'s design, reopening it after a restart. */
    private fun openCadDesign(session: SolidCraftSession, document: CadDocument) {
        if (cadOpenDesignPath == document.designPath) return
        val design = File(document.designPath)
        if (!design.isFile) {
            throw IllegalStateException(
                "the CAD document's design file is missing (" + design.absolutePath +
                    "); reconnect the agent and rebuild it",
            )
        }
        session.open(design.absolutePath)
        cadOpenDesignPath = document.designPath
    }

    /**
     * The Slice the plate's button calls.
     *
     * A mesh plate slices exactly as before, and so does a converted CAD plate: its parts are
     * ordinary STL models by then. A live CAD document is refused by name - a slicer takes STL
     * triangles, and the user's own Convert to STL is the only way across. Nothing here
     * converts the document, and nothing here exports it to make Slice work: converting as a
     * side effect of slicing is exactly what the user asked not to have.
     */
    fun sliceModel() {
        val reason = CadSliceGate.reasonFor(_uiState.value.cadDocument)
        if (reason != null) {
            _uiState.update { it.copy(statusMessage = reason) }
            return
        }
        sliceModelCore()
    }

    /** Writes the design, its sidecar and the workspace descriptor, at most every 1.5 s. */
    private fun scheduleCadPersist(force: Boolean = false) {
        cadPersistScheduler.schedule(force)
    }

    /**
     * Persists a live document: the design (the source of truth), its display cache (so a
     * restored plate has something to draw before the engine starts) and the workspace
     * descriptor that names both.
     *
     * Batched rather than per edit: the picture is never batched, but writing tens of
     * kilobytes to disk after every boolean would be work with no one waiting for it.
     */
    private fun persistCadWorkspaceNow() {
        val state = _uiState.value
        val document = state.cadDocument ?: return
        // The per-body display exports ARE the plate's caches: each part object's sourcePath
        // names the engine's own file for that body, so a restore has one file per part to read
        // and nothing has to be re-composed. What is left to write is what the app knows about
        // the design - the files screen's sidecar - and the design itself.
        val partCount = maxOf(
            cadBodies.count,
            document.parts.size,
            document.convertedParts.size,
        )
        CadDesignLibrary.writeMeta(
            workspace = cadWorkspace(),
            design = File(document.designPath),
            name = document.name,
            partCount = partCount,
            savedAtMillis = System.currentTimeMillis(),
        )
        SolidCraftEngine.session()?.takeIf { SolidCraftEngine.isReady() }?.let { session ->
            runCatching { session.save(document.designPath) }.onFailure {
                Log.w("MainViewModel", "could not save the CAD design", it)
            }
            cadOpenDesignPath = document.designPath
        }
        workspaceSnapshot(_uiState.value)?.let { snapshot ->
            runCatching { workspaceStore.save(snapshot) }.onFailure {
                Log.w("MainViewModel", "could not save the CAD workspace descriptor", it)
            }
        }
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

    /**
     * Records what an imported PrusaSlicer preset set: its values, the machine envelope
     * it left, its G-code, and the file it came from. Persisted with Cura's baseline -
     * one baseline per engine - so a relaunch still shows the snapshot and can still
     * put it back.
     */
    private fun savePrusaSnapshot(
        machine: SlicerSettings,
        settings: PrusaSliceSettings,
        startGcode: String,
        endGcode: String,
        sourceName: String,
        importedValues: Int,
        unmappedKeys: List<String>,
    ) {
        val baseline = AppStateStore.PrusaSnapshotBaseline(
            machine = machine,
            settings = settings,
            startGcode = startGcode,
            endGcode = endGcode,
            profileName = sourceName,
            profileSource = CONFIGURATION_SNAPSHOT_SOURCE,
            importedValues = importedValues,
            unmappedKeys = unmappedKeys,
        )
        importedPrusaBaseline = baseline
        _uiState.update {
            it.copy(
                prusaSnapshot = EngineSnapshot(
                    name = sourceName,
                    source = CONFIGURATION_SNAPSHOT_SOURCE,
                    importedValues = importedValues,
                    notSentKeys = unmappedKeys,
                ),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { stateStore.savePrusaSnapshotBaseline(baseline) }
                .onFailure(::showOperationFailure)
        }
    }

    /** The same, for an imported OrcaSlicer profile. */
    private fun saveOrcaSnapshot(
        machine: SlicerSettings,
        settings: OrcaSliceSettings,
        sourceName: String,
        importedValues: Int,
        refusedKeys: List<String>,
    ) {
        val baseline = AppStateStore.OrcaSnapshotBaseline(
            machine = machine,
            settings = settings,
            profileName = sourceName,
            profileSource = CONFIGURATION_SNAPSHOT_SOURCE,
            importedValues = importedValues,
            refusedKeys = refusedKeys,
        )
        importedOrcaBaseline = baseline
        _uiState.update {
            it.copy(
                orcaSnapshot = EngineSnapshot(
                    name = sourceName,
                    source = CONFIGURATION_SNAPSHOT_SOURCE,
                    importedValues = importedValues,
                    notSentKeys = refusedKeys,
                ),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { stateStore.saveOrcaSnapshotBaseline(baseline) }
                .onFailure(::showOperationFailure)
        }
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
                val stored = _uiState.value
                if (imported.startGcode != null || imported.endGcode != null) {
                    stateStore.savePrusaGcode(stored.prusaStartGcode, stored.prusaEndGcode)
                }
                persistSettings(stored.settings, workspaceMutationGeneration.incrementAndGet())
                // What this preset set, kept as the engine's own snapshot: the sheet
                // shows it, and "Reset all app overrides" puts it back.
                savePrusaSnapshot(
                    machine = stored.settings,
                    settings = imported.settings,
                    startGcode = stored.prusaStartGcode,
                    endGcode = stored.prusaEndGcode,
                    sourceName = displayName(uri),
                    importedValues = imported.unusedKeyCount,
                    unmappedKeys = imported.unmappedKeys,
                )
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
        // The profile's own snapshot, per engine like Cura's: shown by its sheet, and
        // the thing "Reset all app overrides" puts back.
        val stored = _uiState.value
        saveOrcaSnapshot(
            machine = stored.settings,
            settings = stored.orcaSettings,
            sourceName = imported.sourceName,
            importedValues = result.values.size,
            refusedKeys = buildList {
                // Keys the profile carries that the app manages itself, and values the
                // engine's own catalogue refused: neither is sent to the engine, so the
                // snapshot names them instead of dropping them in silence.
                addAll(result.managedKeys)
                addAll(result.refusedKeys)
            }.distinct(),
        )
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

    /**
     * The stored sidebar for an engine, or the built-in four when it has never been
     * edited. Everything that comes back through here is filtered first: a list
     * written by another build, or edited by hand, restores as the rows that still
     * name something rather than as a sidebar with dead entries in it.
     */
    private fun restoredSidebarSettings(): SidebarSettings = SidebarSettings(
        cura = restoredSidebarRows(SlicerEngine.CURA),
        prusa = restoredSidebarRows(SlicerEngine.PRUSA),
        orca = restoredSidebarRows(SlicerEngine.ORCA),
    )

    private fun restoredSidebarRows(engine: SlicerEngine): List<String> =
        stateStore.restoreSidebarSettings(engine)?.let(::sanitizeSidebarRows)
            ?: SidebarSettings().forEngine(engine)

    /**
     * Adds one of the engine's settings to the sidebar, at the end: the order the
     * user builds is the order the rows are in, and the picker moves them from
     * there. Adding one that is already on it changes nothing.
     */
    fun addSidebarSetting(engine: SlicerEngine, key: String) {
        val rows = _uiState.value.sidebarSettings.forEngine(engine)
        val id = key.trim().lowercase()
        if (id.isEmpty() || id in rows) return
        updateSidebarRows(engine, rows + id)
    }

    /** Takes a row off the sidebar; the setting itself is left alone. */
    fun removeSidebarSetting(engine: SlicerEngine, id: String) {
        val rows = _uiState.value.sidebarSettings.forEngine(engine)
        if (id !in rows) return
        updateSidebarRows(engine, rows - id)
    }

    /** Moves a row one place up ([delta] -1) or down (+1). */
    fun moveSidebarSetting(engine: SlicerEngine, id: String, delta: Int) {
        val rows = _uiState.value.sidebarSettings.forEngine(engine).toMutableList()
        val from = rows.indexOf(id)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, rows.lastIndex)
        if (to == from) return
        rows.add(to, rows.removeAt(from))
        updateSidebarRows(engine, rows)
    }

    private fun updateSidebarRows(engine: SlicerEngine, rows: List<String>) {
        _uiState.update { state ->
            state.copy(sidebarSettings = state.sidebarSettings.withRows(engine, rows))
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (!stateStore.saveSidebarSettings(engine, rows)) {
                _uiState.update {
                    it.copy(
                        statusMessage = "The sidebar's settings could not be saved; " +
                            "they will not survive a restart",
                    )
                }
            }
        }
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
        // An edit here is an app override like any other: the key joins the same set
        // the Cura fields write to, which is what the profile block counts and what
        // "Reset all app overrides" puts back.
        val machine = current.settings
            .copy(overriddenSettingKeys = current.settings.overriddenSettingKeys + key)
        _uiState.update { state ->
            state.withoutPublishedSlice("Settings changed; slice again to export G-code").copy(
                prusaSettings = changed,
                settings = machine,
            )
        }
        persistPrusaSettings(changed)
        persistSettings(machine, workspaceMutationGeneration.incrementAndGet())
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
        // An app override, like a Cura field's edit: the set is what the profile
        // block counts and what "Reset all app overrides" restores.
        val machine = current.settings
            .copy(overriddenSettingKeys = current.settings.overriddenSettingKeys + key)
        _uiState.update { state ->
            state.withoutPublishedSlice("Settings changed; slice again to export G-code").copy(
                orcaSettings = changed,
                settings = machine,
            )
        }
        persistOrcaSettings(changed)
        persistSettings(machine, workspaceMutationGeneration.incrementAndGet())
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
    /**
     * Parses the layer preview for the artifact on screen when this process did not keep one:
     * after a restart the Layers tab would otherwise show the model instead of the file's own
     * layers. The parse is the same one the slice runs, folds to the device's budget, and never
     * samples - a layer is drawn whole or named.
     */
    fun ensureLayerPreview() {
        val current = _uiState.value
        if (current.layerPreview != null || current.isBusy) return
        val path = current.gcodePath ?: return
        val file = File(path)
        if (!file.isFile) return
        val dialect = when (current.sliceEngine) {
            SlicerEngine.PRUSA -> GcodeDialect.PRUSA
            SlicerEngine.ORCA -> GcodeDialect.ORCA
            else -> GcodeDialect.CURA
        }
        if (!layerPreviewParse.compareAndSet(false, true)) return
        viewModelScope.launch {
            val preview = runCatching {
                withContext(Dispatchers.IO) {
                    GcodeLayerPreviewParser.parse(
                        file,
                        dialect,
                        PreviewMemoryPolicy.layerSegments(getApplication()),
                    )
                }
            }.getOrNull()
            layerPreviewParse.set(false)
            if (preview != null) {
                _uiState.update { state ->
                    if (state.gcodePath == path) state.copy(layerPreview = preview) else state
                }
            } else {
                Log.w("TrioSlicer", "layer preview: could not read " + file.name + " for the Layers tab")
            }
        }
    }

    /** True while a restarted session's layer preview is being read. */
    val layerPreviewParsing: Boolean get() = layerPreviewParse.get()

    /** Chooses the nozzle-path preview's memory profile; stored so it survives a restart. */
    fun setPreviewMemoryProfile(profile: PreviewMemoryProfile) {
        if (_uiState.value.previewMemoryProfile == profile) return
        _uiState.update { it.copy(previewMemoryProfile = profile) }
        viewModelScope.launch(Dispatchers.IO) { stateStore.savePreviewMemoryProfile(profile.name) }
    }

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

    /**
     * Puts every app override back to what the imported PrusaSlicer preset set: its values,
     * its G-code and the machine envelope it left. The weaker of the two resets its sheet
     * offers - "Reset to engine defaults" is the stronger one and goes to PrusaSlicer's own
     * defaults - so the two are named for what they do.
     */
    fun resetPrusaToSnapshot() {
        if (_uiState.value.isBusy) return
        val baseline = importedPrusaBaseline ?: return
        // The engine's own settings carry no override set of their own: the app's one
        // set lives on the machine settings, and the baseline holds it as the import
        // left it - which is what "cleared" means here.
        val machine = baseline.machine.withRecomputedDerived()
        val settings = baseline.settings
        persistSettings(machine, workspaceMutationGeneration.incrementAndGet())
        persistPrusaSettings(settings)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { stateStore.savePrusaGcode(baseline.startGcode, baseline.endGcode) }
                .onFailure(::showOperationFailure)
        }
        _uiState.update {
            it.withoutPublishedSlice().copy(
                settings = machine,
                prusaSettings = settings,
                prusaStartGcode = baseline.startGcode,
                prusaEndGcode = baseline.endGcode,
                statusMessage = "App overrides cleared; the values imported from " +
                    baseline.profileName + " are active",
            )
        }
    }

    /** The same for OrcaSlicer, which keeps no G-code of its own in this app. */
    fun resetOrcaToSnapshot() {
        if (_uiState.value.isBusy) return
        val baseline = importedOrcaBaseline ?: return
        val machine = baseline.machine.withRecomputedDerived()
        val settings = baseline.settings
        persistSettings(machine, workspaceMutationGeneration.incrementAndGet())
        persistOrcaSettings(settings)
        _uiState.update {
            it.withoutPublishedSlice().copy(
                settings = machine,
                orcaSettings = settings,
                statusMessage = "App overrides cleared; the values imported from " +
                    baseline.profileName + " are active",
            )
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
                // The CAD document's view goes with the plate: the design file and the running
                // engine stay, so asking the agent for a change brings the document back.
                cadBodies.clear()
                cadPartIds.clear()
                cadDerivedPlateIds.clear()
                // The engine's design still holds its bodies; the composer does not. The next
                // delta must not be applied as an incremental patch.
                cadComposerComplete = false
                _uiState.update { current ->
                    // The plate is empty, so every joint and every previewed half the
                    // session holds belongs to an object that is gone.
                    current.withoutPublishedSlice().copy(
                        models = emptyList(),
                        selectedModelId = null,
                        cadDocument = null,
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
                    // A removed half takes its pair with it, and with the pair the source
                    // files its joints were recorded from. Deleting them here rather than
                    // when the panel next opens keeps the model directory to what the plate
                    // actually references.
                    releasePairSources(state.liveSnapRecord, next.liveSnapRecord)
                }
                _uiState.update { commit(it) }
                dropFaceSelection(removed.name + " was removed from the plate")
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
                // A CAD document's split halves are geometry derived from the document's
                // previous shape: a later document change must not paste over them, and
                // Rebuild plate must know which objects to take away.
                if (state.cadDocument?.isDocumentObject(model.id) == true) {
                    cadDerivedPlateIds.addAll(objects.map { it.id })
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
                    // The pair this split replaced is gone with the halves it was recorded on,
                    // so the source files it owned go too. The new pair starts with none: it
                    // has not been jointed yet.
                    releasePairSources(state.liveSnapRecord, null)
                }
                // The plate the history was recorded against no longer exists: every step
                // in it is a placement of the whole model on the pre-split plate, and
                // undoing one would put that placement on a half. The commit drops the
                // button; this is what makes the forgotten step unreachable.
                placementHistory.clear()
                _uiState.update { commit(it) }
                dropFaceSelection("the plate was split into two halves")
                // The split is what defines the assembly axis, so the flow lands in
                // the joint maker with the pair the split just made - on this line
                // only, where the plate really did change. A split that was refused
                // (busy, plate empty, plane outside the model) or that failed never
                // reaches here, and the stale-plate return above steps over it.
                // setSnapActive is the tool's own way in, so the engine check, the
                // session reset and the refusal line are the same ones its button
                // goes through rather than a second copy that could disagree.
                setSnapActive(true)
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
     *
     * [linear] is the orientation the mesh's own frame sits in, for a jointed
     * half that already had one: the split's halves are unrotated, but a half
     * the user has rotated since carries that rotation here, and Apply must put
     * the re-framed mesh back on the plate the way it stood. Centring still
     * happens on the bed, so only the orientation travels.
     */
    private fun cutHalfObject(
        name: String,
        half: StlMesh,
        stagedFile: File,
        state: MainUiState,
        linear: List<Double> = ModelPlacement.IDENTITY,
    ): PlateObject {
        val placement = ModelPlacement.centeredOnBed(
            mesh = half,
            bedWidthMm = state.settings.machineWidthMm,
            bedDepthMm = state.settings.machineDepthMm,
            originAtCenter = state.settings.originAtCenter,
        ).copy(linear = linear)
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
     * The pair's own halves, in the assembled frame every joint on it is measured in.
     *
     * A pair that carries a record has its own source files: the halves as they were BEFORE
     * the first joint was applied. That is the whole reason the record exists - a pocket that
     * has been cut cannot be un-cut - so a preview or an apply that began from the meshes on
     * the plate would union the beam a second time and cut the pocket again. A pair with no
     * record is being jointed for the first time, and the halves the plate holds ARE its
     * source; they are unplaced into the pair's own frame so both cases hand back the same
     * thing.
     *
     * Null when a recorded source cannot be read. The caller reports that and treats the pair
     * as one with no record, never as a failed preview.
     */
    private suspend fun pairOwnHalves(state: MainUiState): Pair<StlMesh, StlMesh>? {
        val lowFit = state.snapLowFitHalf ?: return null
        val highFit = state.snapHighFitHalf ?: return null
        val record = state.liveSnapRecord
        if (record == null) {
            // Walking two meshes, not reading files: the plate's own halves are already here.
            return withContext(Dispatchers.Default) {
                val low = lowFit.unplace(lowFit.mesh, lowFit.mesh.displayName) ?: return@withContext null
                val high = highFit.unplace(highFit.mesh, highFit.mesh.displayName) ?: return@withContext null
                low to high
            }
        }
        return withContext(Dispatchers.IO) {
            val low = readPairSource(record.lowSourcePath, lowFit.mesh.displayName)
                ?: return@withContext null
            val high = readPairSource(record.highSourcePath, highFit.mesh.displayName)
                ?: return@withContext null
            low to high
        }
    }

    /**
     * The bundled library shapes, read through the app's own assets.
     *
     * A shape is read, made watertight, clipped to its graft box and measured
     * once and then kept in the state, because everything downstream - the
     * preview, the ghost, the readout - is arithmetic over the meshes it
     * returns. A shape the engine cannot prepare throws with the shape named,
     * which is the panel's own sentence rather than a joint that is not there.
     */
    private suspend fun libraryPairsFor(
        shapes: Collection<SnapLibraryShape>,
        known: Map<SnapLibraryShape, SnapLibraryPair>,
    ): Map<SnapLibraryShape, SnapLibraryPair> {
        val missing = shapes.filterNot { known.containsKey(it) }
        if (missing.isEmpty()) return known
        return withContext(Dispatchers.IO) {
            val loaded = LinkedHashMap(known)
            for (shape in missing) loaded[shape] = SnapLibrary.load(shape, ::libraryMesh)
            loaded
        }
    }

    /**
     * One library STL: the app's asset, copied into the app's own files
     * directory the first time it is asked for, because [StlParser] reads files
     * and the packaged assets are compressed. The copy is the library's own
     * bytes and belongs to the install, not to any model: nothing deletes it.
     */
    private fun libraryMesh(file: String): StlMesh {
        val directory = File(app.filesDir, SnapLibrary.DIRECTORY).apply { mkdirs() }
        val target = File(directory, file)
        if (!target.isFile || target.length() == 0L) {
            val scratch = File(directory, file + ".part")
            app.assets.open(SnapLibrary.DIRECTORY + "/" + file).use { input ->
                scratch.outputStream().use { output -> input.copyTo(output) }
            }
            if (!scratch.renameTo(target)) {
                scratch.copyTo(target, overwrite = true)
                scratch.delete()
            }
        }
        return StlParser.parse(
            file = target,
            displayName = file,
            maxTriangles = MeshTriangleLimits.current(),
        )
    }

    /** One recorded source half, or null when it cannot be read. */
    private fun readPairSource(path: String, displayName: String): StlMesh? = runCatching {
        StlParser.parse(
            file = File(path),
            displayName = displayName,
            maxTriangles = MeshTriangleLimits.current(),
        )
    }.getOrNull()

    /**
     * A staging path for one of the pair's OWN halves.
     *
     * The same directory and the same one-shot naming as [stagedModelFile], under a name of
     * its own: these files are not an object's file and must never be deleted by the cleanup
     * that takes a replaced object's staged copy. The pair owns them, and they go when it does
     * - see [releasePairSources].
     */
    private fun pairSourceFile(): File =
        File(File(app.filesDir, "models").apply { mkdirs() }, "pair-${System.nanoTime()}.stl")

    /** Deletes the files one pair owns; the pair they belonged to is gone. */
    private fun deletePairSources(paths: List<String>) {
        paths.forEach { path -> runCatching { File(path).takeIf { it.isFile }?.delete() } }
    }

    /**
     * The files of a pair that did not survive: [before] is the record the state held, and
     * [after] the one that replaced it - the same two paths while the pair is the same pair,
     * and nothing when the pair died.
     *
     * Every path that replaces the plate or the pair goes through this, so a record's files
     * follow the rule an object's staged file already follows: unreferenced means deleted.
     */
    private fun releasePairSources(before: SnapRecord?, after: SnapRecord?) {
        if (before == null) return
        if (after != null && before.lowSourcePath == after.lowSourcePath &&
            before.highSourcePath == after.highSourcePath
        ) {
            return
        }
        deletePairSources(before.sourcePaths)
    }

    /**
     * What an apply leaves behind: the source files the pair owns, the panel values the joints
     * were built at, and the joints themselves - each anchor read out of the plate's
     * coordinates into the pair's own frame, because the packer moves the halves between
     * sessions and a saved plate point would land somewhere else on the seam.
     */
    private fun recordOfPair(
        state: MainUiState,
        lowSourcePath: String,
        highSourcePath: String,
    ): SnapRecord = SnapRecord(
        lowSourcePath = lowSourcePath,
        highSourcePath = highSourcePath,
        scale = state.snapScale,
        fullJoint = state.snapFullJoint,
        socketRamp = state.snapSocketRamp,
        hookLengthMm = state.snapHookLengthMm,
        hookThicknessMm = state.snapHookThicknessMm,
        hookLipMm = state.snapHookLipMm,
        joints = state.snapJoints.mapIndexed { index, spec ->
            val local = state.snapAnchorHalfFor(spec)?.toLocal(spec.anchorPointMm)
                ?: throw IllegalStateException(
                    "Joint " + (index + 1) + " is not on either half any more - " +
                        SNAP_PAIR_STALE_MESSAGE,
                )
            SnapJointRecord(
                anchorLocalMm = local,
                depthMm = spec.depthMm,
                onLowHalf = spec.anchorHalfId == state.snapLowHalfId,
                beamHalf = spec.beamHalf,
                tightness = spec.tightness,
                barbs = spec.barbs,
                facing = spec.facing,
                style = spec.style,
                libraryShape = spec.libraryShape,
                lipEngagementMm = spec.lipEngagementMm,
                lipToothHeightMm = spec.lipToothHeightMm,
                lipWallMm = spec.lipWallMm,
                lipRecessExtraMm = spec.lipRecessExtraMm,
                lipCutWidthMm = spec.lipCutWidthMm,
                lipCutDepthMm = spec.lipCutDepthMm,
                // The cuts are read through the INSERT - the mate - which is the
                // half whose ledge ring they open, whether or not the tap landed
                // on it.
                lipCuts = spec.lipCuts.mapNotNull { cut ->
                    val face = if (spec.beamHalf == SnapJoint.JointHalf.LOW) {
                        state.snapHighFitHalf
                    } else {
                        state.snapLowFitHalf
                    }
                    face?.toLocal(cut)
                },
                lipCutDirections = spec.lipCutDirections.map { direction ->
                    val face = if (spec.beamHalf == SnapJoint.JointHalf.LOW) {
                        state.snapHighFitHalf
                    } else {
                        state.snapLowFitHalf
                    }
                    if (direction.length() > 0f) face?.toLocalDirection(direction) ?: direction else direction
                },
            )
        },
    )

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
        // A pair that was jointed in an earlier session carries its own record: the source
        // halves its joints were built from, and the joints themselves. Opening the tool on
        // those is the point of the record - a joint that has been applied is editable, not
        // final. A record whose files have gone is dropped rather than trusted: the panel
        // says so, and the pair opens with no joints, which is what the plate can still
        // honestly offer.
        val recorded = state.liveSnapRecord
        val usable = recorded != null && recorded.sourcePaths.all { File(it).isFile }
        val record = recorded?.takeIf { usable }
        val restored = record?.let(state::restoredJoints).orEmpty()
        if (recorded != null && !usable) {
            Diagnostics.failure(
                "snap fit",
                IllegalStateException("the pair's recorded source halves are gone"),
            )
            viewModelScope.launch(Dispatchers.IO) { deletePairSources(recorded.sourcePaths) }
        }
        Diagnostics.info("snap fit", "opened over the split pair; Manifold " + MeshBoolean.engineVersion)
        _uiState.update {
            it.copy(
                snapActive = true,
                snapJoints = restored,
                snapSelectedJoint = restored.indices.firstOrNull() ?: -1,
                snapMovingJoint = false,
                snapMoveAxis = null,
                snapMixedFacing = false,
                snapFacing = SnapFacing.SAME,
                snapBarbs = 1,
                // The style and the lip's sizes come back as the panel's defaults too,
                // so the control the pair was built with is the one the panel shows.
                snapStyle = restored.firstOrNull { it.style == SnapJointStyle.WALL_LIP }?.style
                    ?: restored.firstOrNull { it.style == SnapJointStyle.LIBRARY_HOOK }?.style
                    ?: SnapJointStyle.CANTILEVER,
                // The picker comes back on the shape the pair was built with, so
                // the control the user set is the one the panel shows.
                snapLibraryShape = restored.firstOrNull { it.style == SnapJointStyle.LIBRARY_HOOK }
                    ?.libraryShape ?: it.snapLibraryShape,
                snapLipEngagementMm = restored.firstOrNull { it.style == SnapJointStyle.WALL_LIP }
                    ?.lipEngagementMm,
                snapLipToothHeightMm = restored.firstOrNull { it.style == SnapJointStyle.WALL_LIP }
                    ?.lipToothHeightMm,
                snapLipWallMm = restored.firstOrNull { it.style == SnapJointStyle.WALL_LIP }?.lipWallMm,
                snapLipDepthMm = restored.firstOrNull { it.style == SnapJointStyle.WALL_LIP }
                    ?.lipRecessExtraMm,
                snapLipCutDepthMm = restored.firstOrNull { it.style == SnapJointStyle.WALL_LIP }
                    ?.lipCutDepthMm,
                snapSocketRamp = record?.socketRamp ?: false,
                snapScale = record?.scale ?: 1f,
                snapFullJoint = record?.fullJoint ?: false,
                snapHookLengthMm = record?.hookLengthMm,
                snapHookThicknessMm = record?.hookThicknessMm,
                snapHookLipMm = record?.hookLipMm,
                snapPreview = null,
                snapFailure = if (recorded != null && !usable) SNAP_RECORD_LOST_MESSAGE else null,
                snapRecord = if (recorded != null && !usable) null else it.snapRecord,
            )
        }
        // The restored joints are a list, not yet geometry: the panel's Apply needs the
        // booleans over the pair's source, exactly as a tapped joint would schedule them.
        // Without this an opened record showed its joints with Join disabled, because
        // nothing had built them.
        if (restored.isNotEmpty()) previewSnapJoint()
    }

    /**
     * A tap on the model: it PLACES a joint when the panel is armed for one, and
     * otherwise SELECTS the joint nearest the tap. Nothing is added by a tap
     * alone, and a joint is no longer MOVED by one: the move is a mode with the
     * plate's own arrows on the hook, and [dragSnapJointAxis] is what moves it.
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
     *
     * A DRAG while "Cut wall" is armed is not this: it is one stroke, one cut,
     * and [placeSnapCutStroke] below is where it lands.
     */
    fun placeSnapJoint(point: Vec3, tappedHalfId: String? = null) {
        val state = _uiState.value
        if (!state.snapActive) return
        when {
            state.snapCuttingWall -> {
                val at = state.snapJoints.indexOfFirst { it.style == SnapJointStyle.WALL_LIP }
                if (at < 0) {
                    _uiState.update { it.copy(snapCuttingWall = false) }
                    return
                }
                val spec = state.snapJoints[at]
                val joints = state.snapJoints.toMutableList().also {
                    it[at] = spec.withCut(cutPlace(state, spec, point, tappedHalfId), null)
                }
                _uiState.update {
                    it.copy(snapJoints = joints, snapCuttingWall = false, snapFailure = null)
                }
                previewSnapJoint()
            }
            // A joint being moved is moved by its drag, never by a tap: the mode
            // is up with the arrows on the hook, and a tap that did nothing used
            // to move the joint to wherever it landed - which is the hard-to-aim
            // tap this replaced. Move again, another joint, or a closed panel
            // ends the mode; a tap is not one of them.
            state.snapMovingJoint -> Unit
            state.snapAddingJoint -> {
                val index = state.snapJoints.size
                val spec = SnapJointSpec(
                    anchorPointMm = point,
                    anchorHalfId = tappedHalfId,
                    beamHalf = state.snapBeamHalf,
                    tightness = if (index % 2 == 0) SnapTightness.LOOSE else SnapTightness.TIGHT,
                    barbs = state.snapBarbs,
                    facing = state.snapFacing,
                    style = state.snapStyle,
                    libraryShape = state.snapLibraryShape,
                    lipEngagementMm = state.snapLipEngagementMm,
                    lipToothHeightMm = state.snapLipToothHeightMm,
                    lipWallMm = state.snapLipWallMm,
                    lipRecessExtraMm = state.snapLipDepthMm,
                )
                _uiState.update {
                    it.copy(
                        snapJoints = it.snapJoints + spec,
                        snapSelectedJoint = index,
                        // One tap, one joint: the control has to be pressed again for the
                        // next one, which is what makes a stray tap harmless.
                        snapAddingJoint = false,
                        // A joint that has just been placed is the one the panel acts on,
                        // so a move that was up belongs to a joint that is no longer it.
                        snapMovingJoint = false,
                        snapMoveAxis = null,
                        snapFailure = null,
                    )
                }
                previewSnapJoint()
            }
            else -> {
                // Nothing armed: a tap belongs to the model - it turns and moves it - so the
                // only thing it may do to the joints is select the one it landed nearest.
                val nearest = state.nearestSnapJoint(point)
                if (nearest >= 0) {
                    _uiState.update { it.copy(snapSelectedJoint = nearest, snapFailure = null) }
                }
            }
        }
    }

    /**
     * A stroke across the wall with "Cut wall" armed: one cut where the stroke
     * began, whose slit runs ACROSS the stroke. The finger draws the line the
     * slit lies perpendicular to, so the direction is the user's own rather than
     * the solver's inward bisector; it is read on the plate like the tap is and
     * carried into the pair's frame with the cut it belongs to.
     *
     * One stroke is one cut: it disarms like the tap, it is previewed at once,
     * and it is listed with a Remove beside it like any other cut.
     */
    fun placeSnapCutStroke(point: Vec3, direction: Vec3, tappedHalfId: String? = null) {
        val state = _uiState.value
        if (!state.snapActive || !state.snapCuttingWall) return
        if (!point.isFinite() || !direction.isFinite() || direction.length() <= 0f) return
        val at = state.snapJoints.indexOfFirst { it.style == SnapJointStyle.WALL_LIP }
        if (at < 0) {
            _uiState.update { it.copy(snapCuttingWall = false) }
            return
        }
        val spec = state.snapJoints[at]
        val joints = state.snapJoints.toMutableList().also {
            it[at] = spec.withCut(cutPlace(state, spec, point, tappedHalfId), direction)
        }
        _uiState.update {
            it.copy(snapJoints = joints, snapCuttingWall = false, snapFailure = null)
        }
        previewSnapJoint()
    }

    /**
     * Arms the next tap to place a joint, and disarms the move it is mutually exclusive with.
     *
     * One tap in the viewer does one thing: the panel decides which, and the two arms are
     * never both up. Add is a deliberate press because a tap alone used to place a joint
     * wherever it landed - including the taps that were meant to turn the model.
     */
    fun addSnapJoint() {
        val state = _uiState.value
        if (!state.snapActive) return
        _uiState.update {
            it.copy(snapAddingJoint = true, snapMovingJoint = false, snapMoveAxis = null, snapFailure = null)
        }
    }

    /** Takes back an armed Add or Move without touching the joints. */
    fun cancelSnapPlacement() {
        _uiState.update { it.copy(snapAddingJoint = false, snapMovingJoint = false, snapMoveAxis = null) }
    }

    /**
     * Which joint the panel's controls act on.
     *
     * Picking another joint ends the move: the arrows belong to the joint they
     * were put on, and leaving them over a different one would drag the wrong
     * hook.
     */
    fun selectSnapJoint(index: Int) {
        val state = _uiState.value
        if (index !in state.snapJoints.indices) return
        if (state.snapMovingJoint && index != state.snapSelectedJoint) stopSnapMove()
        _uiState.update { it.copy(snapSelectedJoint = index, snapFailure = null) }
    }

    /**
     * Moves the selected joint: the plate's own move arrows go up on it and stay
     * up until the control is pressed again, another joint is picked, a joint is
     * placed, or the panel closes.
     *
     * The mode reads the seam's own edge once, here, because every sample of the
     * drag that follows is dropped onto the same contour.
     */
    fun moveSnapJoint(index: Int) {
        val state = _uiState.value
        if (index !in state.snapJoints.indices) return
        // Move is a MODE, not a one-shot: pressing it again is how the user
        // stops, and the arrows go with it.
        if (state.snapMovingJoint && state.snapSelectedJoint == index) {
            stopSnapMove()
            return
        }
        _uiState.update {
            it.copy(
                snapSelectedJoint = index,
                snapMovingJoint = true,
                // Add and Move are one control between them: a tap cannot both place a
                // joint and move one, so arming either takes the other down.
                snapAddingJoint = false,
                snapMoveAxis = null,
                snapFailure = null,
            )
        }
    }

    /**
     * One of the joint's own arrows went down: that axis is the live one from
     * here until it comes up, and the joint's place at the grab is the baseline
     * every sample of the drag is measured from.
     */
    fun beginSnapJointAxis(axis: ModelPlacement.Axis) {
        val state = _uiState.value
        if (!state.snapActive || !state.snapMovingJoint) return
        val spec = state.snapSelectedSpec ?: return
        snapAxisBaseline = spec.anchorPointMm
        snapDepthBaseline = spec.depthMm
        _uiState.update { it.copy(snapMoveAxis = axis, snapFailure = null) }
    }

    /**
     * The held arrow's own millimetres: the joint is put at the place the grab
     * began, plus that many millimetres along THAT plate axis - and nothing else
     * about it moves. The other two coordinates are the baseline's own, exactly,
     * so the axis under the finger is the only axis that carries the joint.
     *
     * The arrow along the SEAM'S own axis is the depth instead: it moves the
     * joint along the axis the two halves meet on, which is the one number the
     * build reads as how deep the joint sits. The panel says which arrow that is.
     *
     * The geometry follows through the preview's own debounce, which every
     * sample re-arms, so a drag does not boolean the halves once a frame.
     */
    fun dragSnapJointAxis(axis: ModelPlacement.Axis, millimetres: Float) {
        if (!millimetres.isFinite()) return
        val before = _uiState.value
        if (!before.snapActive || !before.snapMovingJoint) return
        if (before.snapMoveAxis != axis) return
        val moved = if (axis == before.snapAxis) {
            // Dragging the arrow the two halves meet on lifts the joint out of
            // the part or sinks it in: along the assembly direction is proud,
            // against it is deep.
            before.snapJointSunkTo(
                clampedSnapDepth(before, (snapDepthBaseline ?: 0f) - millimetres),
            )
        } else {
            val baseline = snapAxisBaseline ?: return
            before.snapJointAt(baseline + SnapFit.axisDirection(axis) * millimetres)
        } ?: return
        if (moved.snapJoints == before.snapJoints) return
        // A state that changed under this sample - another joint picked, the
        // mode put away - keeps its own truth rather than being overwritten by
        // a stale drag.
        _uiState.update { current ->
            if (current.snapJoints == before.snapJoints && current.snapMoveAxis == axis) moved else current
        }
        previewSnapJoint()
    }

    /**
     * The depth held inside the two halves' own material.
     *
     * Sinking deeper than the joint's own half - or lifting it past the mate's -
     * carries the joint's own plane out of the part, where its features have
     * nothing to hold on to and a piece of it is left standing in air. No
     * closedness, seat or pair check anywhere would see that, so the arrow stops
     * at the material rather than promising what the part cannot hold.
     */
    private fun clampedSnapDepth(state: MainUiState, wanted: Float): Float {
        if (!wanted.isFinite()) return 0f
        val spec = state.snapSelectedSpec ?: return wanted
        val low = state.snapLowFitHalf ?: return wanted
        val high = state.snapHighFitHalf ?: return wanted
        val lowSpan = SnapFit.spanAlongAxis(low, state.snapAxis) ?: return wanted
        val highSpan = SnapFit.spanAlongAxis(high, state.snapAxis) ?: return wanted
        val seam = low.faceMm
        val beamIsLow = spec.beamHalf == SnapJoint.JointHalf.LOW
        val own = if (beamIsLow) lowSpan else highSpan
        val mate = if (beamIsLow) highSpan else lowSpan
        // Sunk goes away from the mate, into the joint's own half; proud goes the
        // other way, into the mate's.
        val deeper = if (beamIsLow) seam - own.start else own.endInclusive - seam
        val prouder = if (beamIsLow) mate.endInclusive - seam else seam - mate.start
        return wanted.coerceIn(-maxOf(prouder, 0f), maxOf(deeper, 0f))
    }

    /** The arrow came up: the axis goes dark, and where it was left is built. */
    fun endSnapJointAxis() {
        snapAxisBaseline = null
        snapDepthBaseline = null
        if (_uiState.value.snapMoveAxis == null) return
        _uiState.update { it.copy(snapMoveAxis = null) }
        previewSnapJoint()
    }

    /** Puts the move away: the mode, the arrows and the axis that was held. */
    private fun stopSnapMove() {
        snapAxisBaseline = null
        snapDepthBaseline = null
        _uiState.update { it.endSnapMove() }
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
                snapMoveAxis = null,
                snapAddingJoint = false,
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
     * Which joint the selected one is, and what the next one will be: the
     * cantilever or the wall lip.
     *
     * Rebuilding is what changes the geometry here rather than a re-tune, so the
     * preview follows: a lip joint is a different shape on the halves, not the
     * same joint with another number in it.
     */
    fun setSnapStyle(style: SnapJointStyle) {
        val state = _uiState.value
        // The list is rebuilt by the choice, so a joint being moved may not be
        // the one the panel acts on afterwards: the mode ends with the switch.
        stopSnapMove()
        if (style == SnapJointStyle.LIBRARY_HOOK) {
            // The library hook is placed by tapping like the cantilever, so the
            // cantilever's own joints stay out of the way rather than being
            // deleted - switching back brings them out again - and the library's
            // are kept when the style moves on.
            val joints = state.snapJoints.filter { it.style == SnapJointStyle.LIBRARY_HOOK }
            _uiState.update {
                it.copy(
                    snapJoints = joints,
                    snapStyle = style,
                    snapSelectedJoint = if (joints.isEmpty()) -1 else 0,
                    snapFailure = null,
                )
            }
            previewSnapJoint()
            return
        }
        if (style == SnapJointStyle.WALL_LIP) {
            // The lip is the whole seam: one feature around the contour, so it is
            // one joint on the list and no tap places it. The cantilever's tapped
            // joints belong to the other style and are kept out of the way, not
            // deleted: switching back brings them out again.
            val joints = state.snapJoints.filter { it.style == SnapJointStyle.WALL_LIP }
                .ifEmpty { listOf(ringJointSpec(state)) }
            _uiState.update {
                it.copy(snapJoints = joints, snapStyle = style, snapSelectedJoint = 0, snapFailure = null)
            }
        } else {
            val joints = state.snapJoints.filter { it.style == SnapJointStyle.CANTILEVER }
            _uiState.update {
                it.copy(
                    snapJoints = joints,
                    snapStyle = style,
                    snapSelectedJoint = if (joints.isEmpty()) -1 else 0,
                    snapFailure = null,
                )
            }
        }
        previewSnapJoint()
    }

    /**
     * Which bundled shape the picker is on: what the next tapped joint is
     * grafted from, and what the selected library joint is re-cut to, the way
     * the style and the teeth steppers work.
     *
     * The shape's files are read, clipped to the graft box and measured here -
     * once per shape per process - because everything downstream, the ghost
     * included, is then arithmetic over meshes.
     */
    fun setSnapLibraryShape(shape: SnapLibraryShape) {
        if (!_uiState.value.snapActive) return
        val state = _uiState.value
        val target = state.snapSelectedJoint.takeIf { it in state.snapJoints.indices }
            ?.takeIf { state.snapJoints[it].style == SnapJointStyle.LIBRARY_HOOK }
        val joints = if (target != null) {
            state.snapJoints.toMutableList().also { it[target] = it[target].copy(libraryShape = shape) }
        } else {
            state.snapJoints
        }
        _uiState.update { it.copy(snapJoints = joints, snapLibraryShape = shape, snapFailure = null) }
        previewSnapJoint()
    }

    /**
     * The one lip joint: the ring runs the seam's own contour, so all it needs is
     * a point on the pair to read the mating plane from, and the low half's own
     * bounds give it one without a tap.
     */
    private fun ringJointSpec(state: MainUiState): SnapJointSpec {
        val low = state.snapLowFitHalf
        val anchor = if (low == null) {
            Vec3(0f, 0f, 0f)
        } else {
            Vec3(
                (low.mesh.bounds.minX + low.mesh.bounds.maxX) * 0.5f,
                (low.mesh.bounds.minY + low.mesh.bounds.maxY) * 0.5f,
                low.faceMm,
            )
        }
        return SnapJointSpec(
            anchorPointMm = anchor,
            anchorHalfId = state.snapLowHalfId,
            // The lip has one clearance, so the two tightness steps do not apply
            // to it: a single seated click is the whole joint.
            tightness = SnapTightness.LOOSE,
            barbs = 1,
            facing = SnapFacing.SAME,
            style = SnapJointStyle.WALL_LIP,
            // Which half carries the tooth - and so which one carries the
            // embossed ledge the cuts open - is the material's answer first and
            // the panel's choice second, never the split's own low/high naming.
            beamHalf = lipDefaultBeamHalf(state),
            lipEngagementMm = state.snapLipEngagementMm,
            lipToothHeightMm = state.snapLipToothHeightMm,
            lipWallMm = state.snapLipWallMm,
            lipRecessExtraMm = state.snapLipDepthMm,
            lipCutWidthMm = state.snapLipCutWidthMm,
            lipCutDepthMm = state.snapLipCutDepthMm,
        )
    }

    /**
     * Which half the lip's tooth goes in when the ring is first put on the seam:
     * the material's own answer. The ledge has to stand on solid material, so the
     * insert is a half a probe joint can build with the tooth in the OTHER one,
     * and a tray whose wall is its own skin cannot be the insert at all. Two
     * plain solid halves build either way - the case where only the user can say
     * which one is the insert - and there the panel's own choice is kept, with
     * the row and the readout naming the half the cuts will land on.
     */
    private fun lipDefaultBeamHalf(state: MainUiState): SnapJoint.JointHalf {
        val low = state.snapLowFitHalf
        val high = state.snapHighFitHalf
        val fallback = state.snapBeamHalf
        if (low == null || high == null || !state.snapAvailable) return fallback
        val anchor = Vec3(low.mesh.bounds.centerX, low.mesh.bounds.centerY, low.faceMm)
        val probe = SnapJointSpec(
            anchorPointMm = anchor,
            anchorHalfId = state.snapLowHalfId,
            beamHalf = SnapJoint.JointHalf.LOW,
            tightness = SnapTightness.LOOSE,
            barbs = 1,
            facing = SnapFacing.SAME,
            style = SnapJointStyle.WALL_LIP,
        )
        val parameters = state.snapParametersFor(probe)
        fun buildsWithToothIn(half: SnapJoint.JointHalf): Boolean =
            SnapJoint.build(
                axis = state.snapAxis,
                anchorMm = anchor,
                scale = state.snapScale,
                lowHalf = low,
                highHalf = high,
                beamHalf = half,
                parameters = parameters,
                style = SnapJointStyle.WALL_LIP,
            ) is SnapJoint.Either.Placed
        val lowWorks = buildsWithToothIn(SnapJoint.JointHalf.LOW)
        val highWorks = buildsWithToothIn(SnapJoint.JointHalf.HIGH)
        return when {
            lowWorks && !highWorks -> SnapJoint.JointHalf.LOW
            highWorks && !lowWorks -> SnapJoint.JointHalf.HIGH
            else -> fallback
        }
    }

    /**
     * Which half the lip's embossed insert is: the one whose ledge ring the cuts
     * open, and so the one the tooth does NOT go in. Choosing it is the user's
     * where the two halves cannot be told apart - a plain solid pair - and where
     * the chosen half cannot carry the ledge the build refuses with a sentence
     * rather than opening a ring that is the part's own skin.
     */
    fun setSnapLipInsertHalf(half: SnapJoint.JointHalf) {
        val state = _uiState.value
        val tooth = if (half == SnapJoint.JointHalf.LOW) SnapJoint.JointHalf.HIGH else SnapJoint.JointHalf.LOW
        val target = snapSizeTargetIndex(state)
        val joints = if (target in state.snapJoints.indices) {
            state.snapJoints.toMutableList().also { moved ->
                val spec = moved[target]
                // The cuts are kept on the insert's plate copy, so a flip of
                // which half that is carries every one of them across: their
                // place on the seam does not move with the choice.
                val from = if (spec.beamHalf == SnapJoint.JointHalf.LOW) state.snapHighFitHalf
                else state.snapLowFitHalf
                val to = if (half == SnapJoint.JointHalf.LOW) state.snapLowFitHalf else state.snapHighFitHalf
                moved[target] = spec.copy(
                    beamHalf = tooth,
                    lipCuts = reanchorCuts(spec.lipCuts, from, to),
                    lipCutDirections = reanchorDirections(spec.lipCutDirections, from, to),
                )
            }
        } else {
            state.snapJoints
        }
        _uiState.update { it.copy(snapJoints = joints, snapBeamHalf = tooth, snapFailure = null) }
        previewSnapJoint()
    }

    /** [points], moved from one half's plate copy to the other's, through the pair's own frame. */
    private fun reanchorCuts(points: List<Vec3>, from: SnapFitHalf?, to: SnapFitHalf?): List<Vec3> {
        if (from == null || to == null || from === to) return points
        return points.map { point -> from.toLocal(point)?.let { to.toPlate(it) } ?: point }
    }

    /** The same for the stroke directions, which a half's placement may also turn. */
    private fun reanchorDirections(directions: List<Vec3>, from: SnapFitHalf?, to: SnapFitHalf?): List<Vec3> {
        if (from == null || to == null || from === to) return directions
        return directions.map { direction ->
            if (direction.length() <= 0f) direction else to.toPlateDirection(from.toLocalDirection(direction))
        }
    }

    /**
     * A cut's own place, read in the half the insert is. The cut is taken out of
     * the INSERT's ledge ring, so a point tapped on the other half - or on the
     * plate where that half stands - is carried into the insert's frame first.
     * Without this the cut would land somewhere else along the seam, and two
     * taps on the same spot of the ring would not be the same cut.
     */
    private fun cutPlace(
        state: MainUiState,
        spec: SnapJointSpec,
        point: Vec3,
        tappedHalfId: String?,
    ): Vec3 {
        val insert = spec.cutHalf(state) ?: return point
        val tapped = when (tappedHalfId) {
            state.snapLowHalfId -> state.snapLowFitHalf
            state.snapHighHalfId -> state.snapHighFitHalf
            else -> null
        }
        if (tapped == null || tapped === insert) return point
        return tapped.toLocal(point)?.let { insert.toPlate(it) } ?: point
    }

    /**
     * The wall lip's engagement in millimetres: how far the tooth and its ledge
     * overlap, which is the whole hold. Null hands the dimension back to the
     * scale.
     */
    fun setSnapLipEngagement(millimetres: Float?) {
        if (millimetres != null && !millimetres.isFinite()) return
        val state = _uiState.value
        val target = snapSizeTargetIndex(state)
        val joints = if (target in state.snapJoints.indices) {
            state.snapJoints.toMutableList().also {
                it[target] = it[target].copy(lipEngagementMm = millimetres)
            }
        } else {
            state.snapJoints
        }
        _uiState.update { it.copy(snapJoints = joints, snapLipEngagementMm = millimetres, snapFailure = null) }
        // The readout is the built joint, and the strain line is what the user
        // judges the setting by, so a size that moved has to be built again - the
        // debounce in previewSnapJoint is what keeps a drag from stuttering.
        previewSnapJoint()
    }

    /**
     * The joint a style-level size belongs to: the selected one, or - the lip has
     * exactly one joint on the list and no tap places it - that ring. A panel whose
     * selection has gone to -1 still has to edit the joint it is showing, or the
     * sizes it writes are ones the build never reads.
     */
    private fun snapSizeTargetIndex(state: MainUiState): Int =
        state.snapSelectedJoint.takeIf { it in state.snapJoints.indices }
            ?: state.snapJoints.indexOfFirst { it.style == SnapJointStyle.WALL_LIP }

    /** The wall lip's tooth height in millimetres; null hands it back to the scale. */
    fun setSnapLipToothHeight(millimetres: Float?) {
        if (millimetres != null && !millimetres.isFinite()) return
        val state = _uiState.value
        val target = snapSizeTargetIndex(state)
        val joints = if (target in state.snapJoints.indices) {
            state.snapJoints.toMutableList().also {
                it[target] = it[target].copy(lipToothHeightMm = millimetres)
            }
        } else {
            state.snapJoints
        }
        _uiState.update { it.copy(snapJoints = joints, snapLipToothHeightMm = millimetres, snapFailure = null) }
        // The readout is the built joint, and the strain line is what the user
        // judges the setting by, so a size that moved has to be built again - the
        // debounce in previewSnapJoint is what keeps a drag from stuttering.
        previewSnapJoint()
    }

    /**
     * The wall the lip's ring leaves where the part is solid, in millimetres;
     * null hands it back to the scale. A thinner wall bends more easily and
     * closes with less effort, a thicker one is stiffer, and the strain readout
     * is computed over whatever this ends up being.
     */
    fun setSnapLipWall(millimetres: Float?) {
        if (millimetres != null && !millimetres.isFinite()) return
        val state = _uiState.value
        val target = snapSizeTargetIndex(state)
        val joints = if (target in state.snapJoints.indices) {
            state.snapJoints.toMutableList().also {
                it[target] = it[target].copy(lipWallMm = millimetres)
            }
        } else {
            state.snapJoints
        }
        _uiState.update { it.copy(snapJoints = joints, snapLipWallMm = millimetres, snapFailure = null) }
        // The readout is the built joint, and the strain line is what the user
        // judges the setting by, so a size that moved has to be built again - the
        // debounce in previewSnapJoint is what keeps a drag from stuttering.
        previewSnapJoint()
    }

    /**
     * How far the lip's recess floor is cut past the step the tooth and its ramp
     * need, in millimetres; null hands it back to the scale. The free length is
     * measured from that floor, so the strain falls as this grows.
     */
    fun setSnapLipDepth(millimetres: Float?) {
        if (millimetres != null && !millimetres.isFinite()) return
        val state = _uiState.value
        val target = snapSizeTargetIndex(state)
        val joints = if (target in state.snapJoints.indices) {
            state.snapJoints.toMutableList().also {
                it[target] = it[target].copy(lipRecessExtraMm = millimetres)
            }
        } else {
            state.snapJoints
        }
        _uiState.update { it.copy(snapJoints = joints, snapLipDepthMm = millimetres, snapFailure = null) }
        // The readout is the built joint, and the strain line is what the user
        // judges the setting by, so a size that moved has to be built again - the
        // debounce in previewSnapJoint is what keeps a drag from stuttering.
        previewSnapJoint()
    }

    /**
     * How wide a slit a cut takes out of the lip's ring, in millimetres; null
     * hands it back to the scale. A cut is what gives the wall an end to bend
     * from, and this is how much of it goes.
     */
    fun setSnapLipCutWidth(millimetres: Float?) {
        if (millimetres != null && !millimetres.isFinite()) return
        val state = _uiState.value
        val joints = if (state.snapSelectedJoint in state.snapJoints.indices) {
            state.snapJoints.toMutableList().also {
                it[state.snapSelectedJoint] = it[state.snapSelectedJoint].copy(lipCutWidthMm = millimetres)
            }
        } else {
            state.snapJoints
        }
        _uiState.update { it.copy(snapJoints = joints, snapLipCutWidthMm = millimetres, snapFailure = null) }
        previewSnapJoint()
    }

    /**
     * How deep into the insert's face the cuts' slit floors would run, in
     * millimetres; null hands it to the ring's own root, which is the slit that
     * would sever the ledge. The part's own surface is the limit either way: a
     * cut past the roof the emboss stands on would groove the model's own face,
     * so the geometry stops there and the readout says whether that left the
     * ring grooved or parted.
     */
    fun setSnapLipCutDepth(millimetres: Float?) {
        if (millimetres != null && !millimetres.isFinite()) return
        val state = _uiState.value
        val target = snapSizeTargetIndex(state)
        val joints = if (target in state.snapJoints.indices) {
            state.snapJoints.toMutableList().also {
                it[target] = it[target].copy(lipCutDepthMm = millimetres)
            }
        } else {
            state.snapJoints
        }
        _uiState.update { it.copy(snapJoints = joints, snapLipCutDepthMm = millimetres, snapFailure = null) }
        previewSnapJoint()
    }

    /** Arms the next tap on the model to cut the lip's ring where it lands. */
    fun cutWall() {
        if (!_uiState.value.snapActive) return
        _uiState.update { it.copy(snapCuttingWall = true, snapFailure = null) }
    }

    /** Puts the arming away without cutting anything. */
    fun cancelCutWall() {
        _uiState.update { it.copy(snapCuttingWall = false) }
    }

    /** Takes one of the lip's cuts off the list, by its place in it. */
    fun removeLipCut(index: Int) {
        val state = _uiState.value
        val at = state.snapJoints.indexOfFirst { it.style == SnapJointStyle.WALL_LIP }
        if (at < 0) return
        val spec = state.snapJoints[at]
        if (index !in spec.lipCuts.indices) return
        val joints = state.snapJoints.toMutableList().also { it[at] = spec.withoutCut(index) }
        _uiState.update { it.copy(snapJoints = joints, snapFailure = null) }
        previewSnapJoint()
    }

    /**
     * One cut at every bend of the seam's contour, which is what the user asked
     * for by name: the ring's corners are where it is stiffest, and a cut at each
     * of them is what turns the O into the brackets he drew.
     */
    fun cutEveryBend() {
        val state = _uiState.value
        val at = state.snapJoints.indexOfFirst { it.style == SnapJointStyle.WALL_LIP }
        if (at < 0) return
        val spec = state.snapJoints[at]
        val low = state.snapLowFitHalf
        val high = state.snapHighFitHalf
        if (low == null || high == null) return
        val forward = spec.beamHalf == SnapJoint.JointHalf.LOW
        val tooth = if (forward) low else high
        val mate = if (forward) high else low
        val axis = SnapFit.axisDirection(state.snapAxis) ?: return
        // The tooth's half is walked the way the builder walks it: when the lip
        // is built with the tooth in the far half the axis is reversed and that
        // half's face is negated with it, and the anchor is a point on ITS own
        // mating plane rather than the low half's - otherwise a lip whose tooth
        // is in the upper half has no cap to read and no bends to cut.
        val sign = if (forward) 1f else -1f
        val lipFit = tooth.copy(faceMm = tooth.faceMm * sign)
        val mateFit = mate.copy(faceMm = mate.faceMm * sign)
        val centre = tooth.toLocal(
            Vec3(tooth.mesh.bounds.centerX, tooth.mesh.bounds.centerY, tooth.mesh.bounds.centerZ),
        ) ?: return
        val bends = SnapLip.bendPoints(
            axis = if (forward) axis else axis * -1f,
            anchorMm = Vec3(centre.x, centre.y, lipFit.faceMm),
            scale = state.snapScale,
            lipHalf = lipFit,
            mateHalf = mateFit,
            parameters = state.snapParametersFor(spec),
        )
        if (bends.isEmpty()) {
            _uiState.update {
                it.copy(snapFailure = "The seam's own edge has no bends to cut on this pair.")
            }
            return
        }
        // The bend points are the seam's own, in the pair's shared frame; the
        // cuts are kept on the INSERT's plate copy, so each one is carried there
        // through the mate half.
        val points = bends.map { mate.toPlate(it) }
        val joints = state.snapJoints.toMutableList().also { it[at] = spec.withCuts(points) }
        _uiState.update { it.copy(snapJoints = joints, snapCuttingWall = false, snapFailure = null) }
        previewSnapJoint()
    }

    /**
     * The cuts one lip joint carries, in the pair's own frame the halves are
     * handed to the builder in: the spec keeps them on the plate, which the
     * packer moves, and the build measures in the frame it cannot.
     */
    private fun cutsFor(
        spec: SnapJointSpec,
        state: MainUiState,
        low: SnapFitHalf,
        high: SnapFitHalf,
    ): List<Vec3> {
        if (spec.lipCuts.isEmpty()) return emptyList()
        // The cuts live on the INSERT - the mate, the half opposite the tooth -
        // because that is whose ledge ring they open.
        val face = if (spec.beamHalf == SnapJoint.JointHalf.LOW) high else low
        return spec.lipCuts.mapNotNull { face.toLocal(it) }
    }

    /**
     * The direction each cut's slit runs in, in the pair's own frame the halves
     * are handed to the builder in, lined up with [cutsFor]. A zero entry is the
     * automatic inward bisector; a stroke's direction is carried through the half
     * the tooth is in, exactly as its own cut point is.
     */
    private fun cutDirectionsFor(
        spec: SnapJointSpec,
        state: MainUiState,
        low: SnapFitHalf,
        high: SnapFitHalf,
    ): List<Vec3> {
        if (spec.lipCutDirections.size != spec.lipCuts.size) return emptyList()
        if (spec.lipCutDirections.isEmpty()) return emptyList()
        val face = if (spec.beamHalf == SnapJoint.JointHalf.LOW) high else low
        return spec.lipCutDirections.map { direction ->
            if (direction.length() <= 0f) direction else face.toLocalDirection(direction)
        }
    }

    /**
     * One more cut on the lip's ring: a tap adds the point and leaves the slit to
     * the automatic inward bisector, a stroke adds its own direction beside it.
     * Once any cut carries a direction the two lists are kept the same length, a
     * zero vector standing for the automatic one, so a cut and its direction
     * cannot drift apart.
     */
    private fun SnapJointSpec.withCut(point: Vec3, direction: Vec3?): SnapJointSpec {
        val directions = if (direction == null && lipCutDirections.isEmpty()) {
            emptyList()
        } else {
            ArrayList<Vec3>(lipCuts.size + 1).apply {
                addAll(lipCutDirections)
                repeat(lipCuts.size - lipCutDirections.size) { add(Vec3(0f, 0f, 0f)) }
                add(direction ?: Vec3(0f, 0f, 0f))
            }
        }
        return copy(lipCuts = lipCuts + point, lipCutDirections = directions)
    }

    /** The cuts the every-bend action adds: all of them take the automatic direction. */
    private fun SnapJointSpec.withCuts(points: List<Vec3>): SnapJointSpec {
        if (points.isEmpty()) return this
        val directions = if (lipCutDirections.isEmpty()) {
            emptyList()
        } else {
            ArrayList<Vec3>(lipCutDirections.size + points.size).apply {
                addAll(lipCutDirections)
                repeat(points.size) { add(Vec3(0f, 0f, 0f)) }
            }
        }
        return copy(lipCuts = lipCuts + points, lipCutDirections = directions)
    }

    /** The half a lip's cuts are taken from: the mate, the embossed insert. */
    private fun SnapJointSpec.cutHalf(state: MainUiState): SnapFitHalf? =
        if (beamHalf == SnapJoint.JointHalf.LOW) state.snapHighFitHalf else state.snapLowFitHalf

    /** One cut off the list, and its direction with it while the lists are lined up. */
    private fun SnapJointSpec.withoutCut(index: Int): SnapJointSpec {
        if (index !in lipCuts.indices) return this
        val cuts = lipCuts.toMutableList().also { it.removeAt(index) }
        val directions = if (lipCutDirections.size == lipCuts.size) {
            lipCutDirections.toMutableList().also { it.removeAt(index) }
        } else {
            lipCutDirections
        }
        return copy(lipCuts = cuts, lipCutDirections = directions)
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
        // New joints arrive with the last one selected, so a move that was up
        // belongs to a joint the panel no longer acts on.
        stopSnapMove()
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
            style = state.snapStyle,
            lipEngagementMm = state.snapLipEngagementMm,
            lipToothHeightMm = state.snapLipToothHeightMm,
            lipWallMm = state.snapLipWallMm,
            lipRecessExtraMm = state.snapLipDepthMm,
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
            style = state.snapStyle,
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
                style = state.snapStyle,
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
                        style = state.snapStyle,
                        lipEngagementMm = state.snapLipEngagementMm,
                        lipToothHeightMm = state.snapLipToothHeightMm,
                        lipWallMm = state.snapLipWallMm,
                        lipRecessExtraMm = state.snapLipDepthMm,
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
        // The booleans run on the pair's OWN halves - the recorded source when it has one, so
        // that re-previewing a pair which already carries a joint rebuilds it from the plain
        // halves instead of stacking a second beam on the first. Those halves go back onto the
        // plate through the fit's own transform, which is the frame the rest of this screen
        // reads anchors, spans and probes in. A pair with no record is previewed from the
        // halves the plate holds, exactly as it always was: they ARE its source.
        val recorded = state.liveSnapRecord
        val own = recorded?.let { pairOwnHalves(state) }
        if (recorded != null && own == null) {
            // The files the record names cannot be read: keep the pair, drop the joints, and
            // say why. An unreadable source is not a broken plate.
            withContext(Dispatchers.IO) { deletePairSources(recorded.sourcePaths) }
            _uiState.update {
                it.copy(
                    snapJoints = emptyList(),
                    snapRecord = null,
                    snapPreview = null,
                    snapFailure = SNAP_RECORD_LOST_MESSAGE,
                )
            }
            return
        }
        val lowBasis = if (own == null) lowFit else lowFit.withOwnMesh(own.first, lowHalf.name)
        val highBasis = if (own == null) highFit else highFit.withOwnMesh(own.second, highHalf.name)
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
            val lowReady = SnapFitGate.prepare(lowBasis.mesh, SnapFitGate.NativeEngine, lowHalf.name)
            if (lowReady is SnapFitGate.Result.Refused) {
                return@prepared SnapPreviewResult.Refused(lowReady.reason)
            }
            val highReady = SnapFitGate.prepare(highBasis.mesh, SnapFitGate.NativeEngine, highHalf.name)
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
                lowHalf = lowBasis.copy(mesh = lowPrepared.mesh),
                highHalf = highBasis.copy(mesh = highPrepared.mesh),
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
        // The bundled shapes this build needs, read through the app's own assets
        // and clipped here - once per shape per process. Everything after this is
        // arithmetic over meshes, which is what lets the ghost be a lazy value.
        val libraryShapes = specs.filter { it.style == SnapJointStyle.LIBRARY_HOOK }
            .map { it.libraryShape }.toSet()
        val libraries = if (libraryShapes.isEmpty()) {
            state.snapLibraryPairs
        } else {
            libraryPairsFor(libraryShapes, state.snapLibraryPairs)
        }
        if (libraries.size != state.snapLibraryPairs.size) {
            _uiState.update { it.copy(snapLibraryPairs = libraries) }
        }
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
                libraries = libraries,
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

    /**
     * [fit], with [ownMesh] - that half in the pair's own frame - in place of the mesh the
     * plate holds. The fit's own transform and face are kept, so everything measured lands
     * where the plate has the half standing.
     */
    private fun SnapFitHalf.withOwnMesh(ownMesh: StlMesh, name: String): SnapFitHalf =
        copy(mesh = place(ownMesh, name))

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
        /** The bundled shapes this build needs, by shape; see [libraryPairsFor]. */
        libraries: Map<SnapLibraryShape, SnapLibraryPair>,
    ): SnapPlacement {
        val engine = SnapFitGate.NativeEngine
        // A joint's own parameters: the hook controls the user has typed, and
        // then its own clearance step, its own teeth, its own facing and the
        // pair's socket ramp. Nothing else differs between two joints.
        fun parametersFor(spec: SnapJointSpec): SnapFitParameters = hookParameters.copy(
            // How deep THIS joint sits along the seam's own axis. Missing from
            // this list when the depth arrow shipped, it was a number the panel
            // showed and the build never read: the joint was staged on the
            // mating plane however far the arrow had carried it.
            depthMm = spec.depthMm,
            barbCount = spec.barbs,
            facing = spec.facing,
            socketRamp = state.snapSocketRamp,
            // The lip's four sizes, each falling back to the panel's own when the
            // joint carries none - which is what a joint restored from a record
            // written before that size existed does. Wall and depth were missing
            // from this list when they shipped, so their sliders moved, the panel's
            // readout kept the old joint, and Apply staged the old geometry.
            lipEngagementOverrideMm = spec.lipEngagementMm ?: state.snapLipEngagementMm,
            lipToothHeightOverrideMm = spec.lipToothHeightMm ?: state.snapLipToothHeightMm,
            lipWallOverrideMm = spec.lipWallMm ?: state.snapLipWallMm,
            lipCutWidthOverrideMm = spec.lipCutWidthMm ?: state.snapLipCutWidthMm,
            lipRecessExtraOverrideMm = spec.lipRecessExtraMm ?: state.snapLipDepthMm,
            lipCutDepthMm = spec.lipCutDepthMm ?: state.snapLipCutDepthMm,
        ).tightenedBy(spec.tightness.stepMm)

        // The joint's own place in the list travels with it: a joint that failed is named by
        // the number the panel shows, and the ones that stand keep their own numbers.
        val built = ArrayList<Pair<Int, SnapJoint.Placement>>(specs.size)
        val placed = ArrayList<SnapPlacedJoint>(specs.size)
        val notices = ArrayList<String>()
        // What each joint that could not be built said, by the joint it was. One joint the
        // material will not take is that joint's business: the others are placed, the panel
        // names the one that is not, and Apply refuses until it is moved or removed - the
        // alternative, which this did, is dropping the whole preview while the list still
        // showed every joint as if they were all there.
        val failed = ArrayList<SnapJointFailure>()
        var lowMesh = ready.lowHalf.mesh
        var highMesh = ready.highHalf.mesh
        for (index in specs.indices) {
            val spec = specs[index]
            val parameters = parametersFor(spec)
            // Every joint is built on the halves AS THEY NOW STAND: the joints before it are
            // part of the material this one roots in, cuts through and shares a face with.
            //
            // Built on the pair's own untouched halves instead - which is what this did - each
            // joint came out right on its own and each result REPLACED the last, because a
            // placement carries the mesh it was built from. The panel listed every joint and
            // the geometry carried only the last one: two hooks became one, silently.
            val lowRunning = ready.lowHalf.copy(mesh = lowMesh)
            val highRunning = ready.highHalf.copy(mesh = highMesh)
            val placement = when (
                val result = SnapJoint.build(
                    axis = axis,
                    anchorMm = anchors[index],
                    scale = scale,
                    lowHalf = lowRunning,
                    highHalf = highRunning,
                    beamHalf = spec.beamHalf,
                    parameters = parameters,
                    requested = if (fullJoint) SnapFitRung.FULL else null,
                    style = spec.style,
                    cuts = if (spec.style == SnapJointStyle.WALL_LIP) {
                        cutsFor(spec, state, ready.lowHalf, ready.highHalf)
                    } else {
                        emptyList()
                    },
                    cutDirections = if (spec.style == SnapJointStyle.WALL_LIP) {
                        cutDirectionsFor(spec, state, ready.lowHalf, ready.highHalf)
                    } else {
                        emptyList()
                    },
                    library = libraries[spec.libraryShape],
                )
            ) {
                is SnapJoint.Either.Placed -> result.placement
                is SnapJoint.Either.Flipped -> {
                    notices += "Joint " + (index + 1) + ": " + result.why.summary
                    result.placement
                }
                is SnapJoint.Either.Failed -> {
                    failed += SnapJointFailure(index, result.failure.summary)
                    continue
                }
            }
            // The full joint's pad is contoured to the material it stands on,
            // and when the wall is too thin for a rim the ladder's lesser rung is
            // used and says so: a pad that collapses to nothing is not a pad.
            val contoured = contouredOrLesser(
                placement, lowRunning, highRunning, anchors[index], axis, scale, spec.beamHalf, parameters,
            )
                ?: run {
                    failed += SnapJointFailure(
                        index,
                        SnapPad.TOO_THIN_REASON + ", and the lesser joints do not fit either",
                    )
                    continue
                }
            val joint = contoured.joint
            // The library pair's booleans are a third shape again. The hook is a
            // union into the half the user chose. The mate's own material inside
            // the graft box is REPLACED by the catch's own shape there: the box
            // minus the catch comes out first - that is the space the author's
            // hooks run in, so a solid mate is opened exactly where the hooks need
            // it - and the catch is unioned in after it, because the catch's walls
            // and its ledge stand inside that very space. Unioning the housing
            // without freeing the space would bury the hooks; freeing it without
            // the housing would leave the ledge off.
            if (joint.style == SnapJointStyle.LIBRARY_HOOK) {
                val hookHalfMesh = contoured.beamMesh
                val mateMesh = contoured.socketMesh
                val hooked = engine.union(hookHalfMesh, joint.unionSolid)
                if (hooked !is MeshBoolean.Result.Success) {
                    failed += SnapJointFailure(
                        index,
                        "could not have the library hook joined on: " +
                            (hooked as MeshBoolean.Result.Failure).reason,
                    )
                    continue
                }
                val catchVoid = joint.registrationRecess
                val freed: MeshBoolean.Result = if (catchVoid.triangleCount == 0) {
                    // No void for this shape - the round catch is a solid, not a
                    // housing - so the mate keeps its own material and the catch
                    // is unioned straight on.
                    held(mateMesh)
                } else {
                    engine.subtract(mateMesh, catchVoid)
                }
                if (freed !is MeshBoolean.Result.Success) {
                    failed += SnapJointFailure(
                        index,
                        "could not have the library catch's own room cut into it: " +
                            (freed as MeshBoolean.Result.Failure).reason,
                    )
                    continue
                }
                val caught = engine.union(freed.mesh, joint.registrationSolid)
                if (caught !is MeshBoolean.Result.Success) {
                    failed += SnapJointFailure(
                        index,
                        "could not have the library catch joined on: " +
                            (caught as MeshBoolean.Result.Failure).reason,
                    )
                    continue
                }
                val hookSeat = seatVolume(hookHalfMesh, joint.unionSolid)
                val catchSeat = seatVolume(freed.mesh, joint.registrationSolid)
                // The two applied halves have to close on each other. A housing
                // catch's own void is what makes room for the hooks, so the
                // rectangular shapes are checked rather than assumed; a round
                // catch is a bead unioned on as it stands, and on a seam whose
                // own material is where the bead goes the two printed halves
                // would intersect - which no closedness check anywhere would
                // notice.
                // The two applied halves have to close on each other, and the
                // question is asked in the pair's OWN assembled frame: on the
                // plate the packer has already moved the halves apart, so the
                // same two meshes side by side intersect in nothing whatever
                // their real shapes are. Each half's graft is carried across
                // through the two fits' own transforms, and the clash is
                // measured as what the other half takes OUT of it - not as an
                // intersection, because the two are seated on one coplanar face,
                // which is exactly the case the engine cannot read an
                // intersection back from. A boolean that fails is its own
                // refusal rather than a silent pass.
                val hookIsLowHalf = contoured.beamMesh === lowRunning.mesh
                val hookFit = if (hookIsLowHalf) ready.lowHalf else ready.highHalf
                val mateFit = if (hookIsLowHalf) ready.highHalf else ready.lowHalf
                val catchInHookPlace = remap(
                    joint.registrationSolid, mateFit, hookFit, SnapLibrary.CATCH_NAME,
                )
                val hookInMatePlace = remap(joint.unionSolid, hookFit, mateFit, SnapLibrary.HOOK_NAME)
                val clash = clashVolume(contoured.beamMesh, catchInHookPlace, engine)
                    ?: clashVolume(contoured.socketMesh, hookInMatePlace, engine)
                val clashReason = clashFailure
                val failure = when {
                    clash == null ->
                        "could not be checked for the two halves closing on each other: " +
                            clashReason
                    clash > MIN_FEATURE_MM3 ->
                        "would leave the two halves overlapping by " + millimetres(clash) +
                            " mm3: the library's " +
                            (SnapLibrary.shapeOf(joint.dimensions)?.label ?: "shape") +
                            " pair needs room where it lands - a hollow or open half - and this " +
                            "one has its own material there. Tap a spot where the seam's two " +
                            "halves are open, or use a shape that stands proud of the seam."
                    hookSeat < MIN_FEATURE_MM3 ->
                        "has no material of its own half under the library hook's plate, so the " +
                            "hook would stand in the open; tap where the seam's own surface is, " +
                            "or move the joint."
                    catchSeat < MIN_FEATURE_MM3 ->
                        "has no material of the mate under the library catch, so the catch would " +
                            "stand in the open; tap where the other half's own surface is, or " +
                            "move the joint."
                    else -> null
                }
                if (failure != null) {
                    failed += SnapJointFailure(index, failure)
                    continue
                }
                if (Diagnostics.isEnabled()) {
                    Diagnostics.info(
                        "snap fit",
                        "joint " + (index + 1) + "/" + specs.size + " library " +
                            (SnapLibrary.shapeOf(joint.dimensions)?.label ?: "") +
                            " at " + millimetres(joint.dimensions.scale.toDouble()) + "x" +
                            ": hook +" + millimetres((hooked.volumeMm3 - MeshVolume.of(hookHalfMesh))) +
                            " mm3 (seat " + millimetres(hookSeat) + " mm3), catch " +
                            millimetres(caught.volumeMm3 - MeshVolume.of(mateMesh)) +
                            " mm3 (seat " + millimetres(catchSeat) + " mm3), clearance " +
                            millimetres(joint.dimensions.libraryGapMm.toDouble()) + " mm",
                    )
                }
                val hookIsLow = contoured.beamMesh === lowRunning.mesh
                built += index to contoured
                if (hookIsLow) {
                    lowMesh = hooked.mesh
                    highMesh = caught.mesh
                } else {
                    lowMesh = caught.mesh
                    highMesh = hooked.mesh
                }
                placed += SnapPlacedJoint(
                    spec = spec,
                    joint = joint,
                    beamInChosenHalf = placement.chosenHalfCarriesBeam,
                    beamHalfName = if (hookIsLow) lowName else highName,
                    socketHalfName = if (hookIsLow) highName else lowName,
                )
                continue
            }
            // The two styles' booleans are not the same shape: the cantilever
            // path below, so the cantilever's four calls stay exactly what they were.
            if (joint.style == SnapJointStyle.WALL_LIP) {
                var beamGain = 0.0
                var failure: String? = null
                // The recess first, then the tooth inside it: the recess is what
                // frees the wall the tooth stands on, so a tooth unioned before it
                // would be cut away with everything else in its way. This half is
                // never cut: its ring is rooted in its own wall, which is the
                // part's outer skin.
                val hollowed = engine.subtract(contoured.beamMesh, joint.subtractSolid)
                if (hollowed !is MeshBoolean.Result.Success) {
                    failed += SnapJointFailure(
                        index,
                        "could not have its recess cut: " + (hollowed as MeshBoolean.Result.Failure).reason,
                    )
                    continue
                }
                val toothed = engine.union(hollowed.mesh, joint.unionSolid)
                if (toothed !is MeshBoolean.Result.Success) {
                    failed += SnapJointFailure(
                        index,
                        "could not have its tooth joined on: " +
                            (toothed as MeshBoolean.Result.Failure).reason,
                    )
                    continue
                }
                // The ledge goes on the mate, and the cuts come out of the LEDGE:
                // the insert's embossed ring is a protrusion on solid material, so
                // it can be opened, while the tooth's own wall is the part's skin
                // and must stay one unbroken surface. The cuts come after the ledge
                // is unioned on, or a slit taken before it would be bridged by the
                // ledge standing over it.
                val ledged = engine.union(contoured.socketMesh, joint.registrationSolid)
                if (ledged !is MeshBoolean.Result.Success) {
                    failed += SnapJointFailure(
                        index,
                        "could not have its ledge joined on: " +
                            (ledged as MeshBoolean.Result.Failure).reason,
                    )
                    continue
                }
                val cut = joint.cutSolid
                val slitted: MeshBoolean.Result = if (cut == null || cut.triangleCount == 0) {
                    ledged
                } else {
                    engine.subtract(ledged.mesh, cut)
                }
                if (slitted !is MeshBoolean.Result.Success) {
                    failed += SnapJointFailure(
                        index,
                        "could not have its cuts taken out of the insert's ring: " +
                            (slitted as MeshBoolean.Result.Failure).reason,
                    )
                    continue
                }
                beamGain = toothed.volumeMm3 - hollowed.volumeMm3
                // How much of the insert the ledge really holds on to, and how much
                // of it stands out in the open: a ledge unioned into thin air is a
                // floating block, and one that shares nothing with the insert is
                // worse.
                val ledgeGain = ledged.volumeMm3 - MeshVolume.of(contoured.socketMesh)
                val ledgeSeat = seatVolume(contoured.socketMesh, joint.registrationSolid)
                val cutLoss = if (cut == null || cut.triangleCount == 0) {
                    0.0
                } else {
                    ledged.volumeMm3 - slitted.volumeMm3
                }
                failure = when {
                    beamGain < MIN_FEATURE_MM3 ->
                        "has no wall where it was placed for its tooth to root in; " +
                            "tap another spot on the seam, or move it."
                    ledgeGain < MIN_FEATURE_MM3 ->
                        "the wall lip's ring would stand in the open: the half chosen as the " +
                            "insert has no material inboard of the seam there. The cuts land on " +
                            "the insert's embossed ring and never on the half whose own wall is " +
                            "the part's skin, so choose the other half, or place the joint where " +
                            "the insert is solid."
                    ledgeSeat < MIN_FEATURE_MM3 ->
                        "the wall lip's ring has nothing of the insert to hold on to; " +
                            "move the joint where the insert is solid."
                    cut != null && cut.triangleCount != 0 && cutLoss < MIN_FEATURE_MM3 ->
                        "the cuts would take nothing out of the insert's ring; " +
                            "the ring they are aimed at is not there."
                    else -> null
                }
                if (Diagnostics.isEnabled()) {
                    Diagnostics.info(
                        "snap fit",
                        "joint " + (index + 1) + "/" + specs.size + " wall lip" +
                            ", " + specs[index].tightness.label.lowercase() + " clearance" +
                            ": tooth +" + millimetres(beamGain) +
                            " mm3, ledge +" + millimetres(ledgeGain) +
                            " mm3 (seat " + millimetres(ledgeSeat) +
                            " mm3, cuts -" + millimetres(cutLoss) +
                            " mm3), wall " + millimetres(joint.dimensions.wallThicknessMm.toDouble()) +
                            " mm free " + millimetres(joint.dimensions.wallFreeLengthMm.toDouble()) +
                            " mm, engagement " + millimetres(joint.dimensions.lipEngagementMm.toDouble()) +
                            " mm, strain " +
                            millimetres(joint.dimensions.lipStrainPercent.toDouble()) + "%",
                    )
                }
                if (failure != null) {
                    failed += SnapJointFailure(index, failure)
                    continue
                }
                // Which half carries the tooth decides which running mesh takes
                // what: the tooth's half is never cut, and the mate carries the
                // ledge with the cuts taken out of it. Both carry the joints
                // before them forward.
                val toothIsLow = contoured.beamMesh === lowRunning.mesh
                built += index to contoured
                if (toothIsLow) {
                    lowMesh = toothed.mesh
                    highMesh = slitted.mesh
                } else {
                    lowMesh = slitted.mesh
                    highMesh = toothed.mesh
                }
                placed += SnapPlacedJoint(
                    spec = spec,
                    joint = joint,
                    beamInChosenHalf = placement.chosenHalfCarriesBeam,
                    beamHalfName = if (toothIsLow) lowName else highName,
                    socketHalfName = if (toothIsLow) highName else lowName,
                )
                continue
            }
            // The cantilever, unchanged: one union into the beam's half, the pad
            // fused on after it, and the pocket and recess cut from the mate.
            val withStep = joint.rung == SnapFitRung.FULL
                val union = engine.union(contoured.beamMesh, joint.unionSolid)
                if (union !is MeshBoolean.Result.Success) {
                    failed += SnapJointFailure(
                        index,
                        "could not be joined on: " + (union as MeshBoolean.Result.Failure).reason,
                    )
                    continue
                }
                // The whole-seam registration step, when this rung carries one. Its
                // solids are separate meshes because one mesh holding two
                // overlapping shells is not manifold, so the engine is what fuses
                // the boss on and cuts the matching recess out.
                val registered = if (withStep) {
                    val stepped = engine.union(union.mesh, joint.registrationSolid)
                    if (stepped !is MeshBoolean.Result.Success) {
                        failed += SnapJointFailure(
                            index,
                            "could not have its registration step joined on: " +
                                (stepped as MeshBoolean.Result.Failure).reason,
                        )
                        continue
                    }
                    stepped
                } else {
                    union
                }
                val socket = engine.subtract(contoured.socketMesh, joint.subtractSolid)
                if (socket !is MeshBoolean.Result.Success) {
                    failed += SnapJointFailure(
                        index,
                        "could not have its pocket cut: " + (socket as MeshBoolean.Result.Failure).reason,
                    )
                    continue
                }
                val recessed = if (withStep) {
                    val hollowed = engine.subtract(socket.mesh, joint.registrationRecess)
                    if (hollowed !is MeshBoolean.Result.Success) {
                        failed += SnapJointFailure(
                            index,
                            "could not have its matching recess cut: " +
                                (hollowed as MeshBoolean.Result.Failure).reason,
                        )
                        continue
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
                val beamGain = union.volumeMm3 - MeshVolume.of(contoured.beamMesh)
                val pocketLoss = MeshVolume.of(contoured.socketMesh) - socket.volumeMm3
                val padGain = registered.volumeMm3 - union.volumeMm3
                val recessLoss = socket.volumeMm3 - recessed.volumeMm3
                val failure = when {
                    beamGain < MIN_FEATURE_MM3 ->
                        "has no material where it was placed for its beam to root in; " +
                            "tap another spot on the seam, or move it."
                    pocketLoss < MIN_FEATURE_MM3 ->
                        "the matching pocket would cut nothing out of the other half; " +
                            "tap another spot, or move the joints apart."
                    withStep && recessLoss < MIN_FEATURE_MM3 ->
                        "the matching recess would cut nothing out of the other half."
                    withStep && padGain < MIN_FEATURE_MM3 ->
                        "the whole-seam pad has nothing to hold on to here."
                    else -> null
                }
                if (failure != null) {
                    failed += SnapJointFailure(index, failure)
                    continue
                }

                // Which half carries the beam decides which running mesh takes what,
                // and where each half's mating face now is on the plate.
                val beamIsLow = contoured.beamMesh === lowRunning.mesh
                built += index to contoured
                if (beamIsLow) {
                    lowMesh = registered.mesh
                    highMesh = recessed.mesh
                } else {
                    lowMesh = recessed.mesh
                    highMesh = registered.mesh
                }
            placed += SnapPlacedJoint(
                spec = spec,
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
                        " mm3 (seat " + millimetres(seatVolume(contoured.beamMesh, joint.unionSolid)) +
                        " mm3), pocket -" + millimetres(pocketLoss) +
                        " mm3, pad +" + millimetres(padGain) +
                        " mm3, recess -" + millimetres(recessLoss) +
                        " mm3, deflection room " + millimetres(joint.dimensions.deflectionRoomMm.toDouble()) +
                        " mm",
                )
            }
        }

        // The guard, once every joint that could be built has been: two joints whose pockets
        // cross, or leave no printable wall between them, would silently cut each other away -
        // and a joint that has been cut away still passes every closedness check there is.
        // This is the set's answer rather than one joint's, so it still stops the preview
        // whole; a joint that could not be built at all is that joint's own sentence.
        if (built.size > 1) {
            val wall = built.maxOf { it.second.joint.dimensions.beamThicknessMm }
            val conflicts = SnapLayout.conflicts(
                built.map { SnapLayout.footprintOf(it.second.joint) },
                wall,
            )
            if (conflicts.isNotEmpty()) {
                return SnapPlacement.Refused(
                    conflicts.joinToString("; ") +
                        ". Move a joint, remove one, or clear them and spread them again.",
                )
            }
        }
        // Nothing at all could be built: the panel gets the sentence it always got, naming the
        // first joint that refused, rather than an empty preview with no joints in it.
        if (placed.isEmpty()) {
            val first = failed.first()
            return SnapPlacement.Refused(
                "Joint " + (first.index + 1) + " " + first.reason,
            )
        }

        val lowPreview = lowMesh.copy(displayName = lowName)
        val highPreview = highMesh.copy(displayName = highName)
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
            failed = failed,
        )
        // A joint that could not be built is said out loud, naming it, while the ones that
        // could are the geometry on the plate. Apply refuses while any of this stands.
        notices += failed.map { "Joint " + (it.index + 1) + " " + it.reason }
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
        /** The two halves as this joint's own booleans will leave them - see placeEveryJoint. */
        lowHalf: SnapFitHalf,
        highHalf: SnapFitHalf,
        /** The joint's own tap, in the halves' own coordinates. */
        anchorMm: Vec3,
        axis: ModelPlacement.Axis,
        scale: Float,
        beamHalf: SnapJoint.JointHalf,
        parameters: SnapFitParameters,
    ): SnapJoint.Placement? {
        if (base.joint.rung != SnapFitRung.FULL) return base
        val beamFit = if (base.beamMesh === lowHalf.mesh) lowHalf else highHalf
        val socketFit = if (base.beamMesh === lowHalf.mesh) highHalf else lowHalf
        val contoured = SnapPad.contour(base.joint, beamFit, socketFit)
        if (contoured != null) return base.copy(joint = contoured)
        for (rung in listOf(SnapFitRung.SIMPLE, SnapFitRung.MINIMAL)) {
            val lesser = SnapJoint.build(
                axis = axis,
                anchorMm = anchorMm,
                scale = scale,
                lowHalf = lowHalf,
                highHalf = highHalf,
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

    /** The material two solids share, or 0 when the engine would not answer. */

    /** The engine's reason for the last failed clash measurement, for the refusal's sentence. */
    private var clashFailure: String = "the engine refused the measurement"

    /**
     * How much of [mesh] the other half's own material would take away, or null
     * when the engine would not answer.
     */
    private fun clashVolume(mesh: StlMesh, other: StlMesh, engine: SnapFitGate.Engine): Double? {
        if (other.triangleCount == 0) return 0.0
        return when (val cut = engine.subtract(mesh, other)) {
            is MeshBoolean.Result.Success -> MeshVolume.of(mesh) - cut.volumeMm3
            is MeshBoolean.Result.Failure -> {
                clashFailure = cut.reason
                null
            }
        }
    }

    /**
     * [mesh], given where [from] stands on the plate, as it would stand in [to]'s
     * place: the two halves' own coordinates are ONE frame - the pair's own
     * assembled one - which is what makes the two grafts comparable at all.
     */
    private fun remap(mesh: StlMesh, from: SnapFitHalf, to: SnapFitHalf, name: String): StlMesh {
        if (from.isInPlace && to.isInPlace) return mesh
        val builder = MeshSolidBuilder(name)
        val vertices = mesh.interleavedVertices
        val points = FloatArray(9)
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            for (corner in 0 until 3) {
                val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                val own = from.toLocal(Vec3(vertices[at], vertices[at + 1], vertices[at + 2]))
                val placed = if (own == null) {
                    Vec3(vertices[at], vertices[at + 1], vertices[at + 2])
                } else {
                    to.toPlate(own)
                }
                points[corner * 3] = placed.x
                points[corner * 3 + 1] = placed.y
                points[corner * 3 + 2] = placed.z
            }
            builder.addTriangle(
                points[0], points[1], points[2],
                points[3], points[4], points[5],
                points[6], points[7], points[8],
            )
        }
        return builder.build()
    }

    /** A running mesh as a successful no-op: what a boolean that changed nothing would have said. */
    private fun held(mesh: StlMesh): MeshBoolean.Result.Success = MeshBoolean.Result.Success(
        mesh = mesh,
        status = SnapFitGate.NO_ERROR,
        closed = true,
        volumeMm3 = MeshVolume.of(mesh),
        millis = 0.0,
    )

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
        val specs = state.snapJoints
        val preview = state.snapShownMeshes
        // Two ways to have something to apply: a previewed joint, and a pair whose every joint
        // has been removed - the second is the way back to the plain halves, and it needs no
        // preview because there is nothing to build, only the pair's own source to put back.
        val puttingPlainHalvesBack = state.snapPlainHalvesInstead
        if (preview == null && !puttingPlainHalvesBack) {
            val reason = state.snapBlockedReason
                ?: "The joint preview is still being built; try again in a moment"
            showSnapFailure(IllegalStateException(reason))
            return
        }
        // A preview standing with a joint the material refused is not a pair to write: the
        // geometry carries the joints that were built and the record would carry the list.
        // The panel disables the button for this; the command says no as well, because a
        // button is not the only way in.
        if (state.snapFailedJoints.isNotEmpty()) {
            showSnapFailure(IllegalStateException(state.snapBlockedReason.orEmpty()))
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
            // The pair files this apply itself creates, so a failure that never commits the
            // record does not leave two orphans behind. A pair that already has its source
            // keeps the files it has.
            val writtenSources = ArrayList<File>(2)
            runCatching {
                // The pair goes back into its OWN frame before it is staged: the
                // assembled frame the joints are measured in. Staged as the plate
                // has them, each new object's own frame keeps the packer's
                // separation, and the tool cannot work on the pair it has just
                // made. See SnapApply.stage.
                val fitLow = state.snapLowFitHalf
                val fitHigh = state.snapHighFitHalf
                if (fitLow == null || fitHigh == null) {
                    throw IllegalStateException(SNAP_PAIR_STALE_MESSAGE)
                }
                // The unjointed halves this build starts from. The recorded source when the
                // pair has one and the build has a preview; otherwise the halves on the plate,
                // unplaced into the pair's frame - which is also what the pair files are first
                // written from.
                val record = state.liveSnapRecord
                val own = if (record == null || preview == null) {
                    pairOwnHalves(state) ?: throw IllegalStateException(SNAP_RECORD_LOST_MESSAGE)
                } else {
                    null
                }
                val sourcePaths = if (record != null) {
                    record.lowSourcePath to record.highSourcePath
                } else {
                    withContext(Dispatchers.IO) {
                        val lowSource = pairSourceFile()
                        val highSource = pairSourceFile()
                        writtenSources += lowSource
                        writtenSources += highSource
                        StlMeshWriter.writeBinary(
                            own!!.first.copy(displayName = lowSource.name),
                            lowSource,
                        )
                        StlMeshWriter.writeBinary(
                            own.second.copy(displayName = highSource.name),
                            highSource,
                        )
                        lowSource.absolutePath to highSource.absolutePath
                    }
                }
                // With every joint removed there is nothing to boolean: the pair's own halves
                // go back on the plate as the objects, which is what makes the source the one
                // stage of record. Otherwise the previewed meshes are staged, exactly as
                // before.
                val lowMesh = preview?.lowMesh
                    ?: own?.let { fitLow.place(it.first, low.name) }
                    ?: throw IllegalStateException(SNAP_RECORD_LOST_MESSAGE)
                val highMesh = preview?.highMesh
                    ?: own?.let { fitHigh.place(it.second, high.name) }
                    ?: throw IllegalStateException(SNAP_RECORD_LOST_MESSAGE)
                val nextRecord = recordOfPair(state, sourcePaths.first, sourcePaths.second)
                val applied = withContext(Dispatchers.IO) {
                    val pair = SnapApply.stage(
                        lowHalf = fitLow,
                        highHalf = fitHigh,
                        lowMesh = lowMesh,
                        highMesh = highMesh,
                        lowName = low.name,
                        highName = high.name,
                    )
                    listOf(
                        Triple(low.name, pair.lowMesh, pair.lowLinear),
                        Triple(high.name, pair.highMesh, pair.highLinear),
                    ).map { (name, mesh, linear) ->
                        val file = stagedModelFile()
                        staged += file
                        StlMeshWriter.writeBinary(mesh.copy(displayName = name), file)
                        cutHalfObject(name, mesh, file, state, linear)
                    } to pair
                }
                val objects = applied.first
                val stagedPair = applied.second
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
                    runCatching { writtenSources.forEach { it.delete() } }
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = "The plate changed while the joint was being applied; " +
                                "nothing was changed",
                        )
                    }
                    return@runCatching
                }
                val message = if (puttingPlainHalvesBack) {
                    "Removed every joint from " + low.name + " and " + high.name +
                        "; the plain halves are back - slice again to export G-code"
                } else {
                    "Joined " + low.name + " and " + high.name +
                        " with a snap fit; slice again to export G-code"
                }
                val commit = { current: MainUiState ->
                    current.withoutPublishedSlice(message).copy(
                        models = packed,
                        selectedModelId = lowObject.id,
                        isBusy = false,
                    ).afterPlateReplaced().withSnapPair(
                        // The pair is the halves just staged, so the tool can be
                        // opened again to change the joints on the same pair.
                        // Apply stages the previewed meshes through
                        // [SnapApply.stage], so both new objects' own frames are
                        // the pair's own assembled frame again, and the faces
                        // recorded here are the planes that frame carries - a
                        // measurement of the new meshes would read the beam's own
                        // tip as the beam half's face.
                        lowHalfId = lowObject.id,
                        highHalfId = highObject.id,
                        axis = state.snapAxis,
                        lowFaceMm = stagedPair.lowFaceMm,
                        highFaceMm = stagedPair.highFaceMm,
                        // And with them the record: the pair's own unjointed source, which is
                        // what the next session rebuilds an edited joint from, and the joints
                        // that are on the plate now.
                        record = nextRecord,
                    )
                }
                // Apply replaces the two split halves with the jointed pair: the derived
                // layout now consists of the jointed objects, so the tracked ids move with
                // them. Without this, Rebuild plate filters nothing and pastes a whole copy of
                // the part beside the joined halves.
                val replacedHalves = setOf(low.id, high.id)
                if (cadDerivedPlateIds.removeAll(replacedHalves)) {
                    cadDerivedPlateIds.addAll(listOf(lowObject.id, highObject.id))
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
                dropFaceSelection("a snap fit was applied to the plate")
            }.onFailure { error ->
                runCatching { staged.forEach { it.delete() } }
                // The pair files this apply wrote are not referenced by any state yet, so
                // they go with the objects it staged. A pair that already had its source
                // keeps it: those paths were reused, not rewritten.
                runCatching { writtenSources.forEach { it.delete() } }
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
        // One tool owns a touch: arming a paint mode disarms face selection, so a stroke can
        // never pick a face. The selection itself (its tint and prompt block) is a different
        // fact and stays until the user clears it or the document changes under it.
        _uiState.update { state ->
            val modes = PlateModes(paint = state.paintMode, faceSelect = state.faceSelectMode)
                .withPaint(mode)
            state.copy(paintMode = modes.paint, faceSelectMode = modes.faceSelect)
        }
    }

    // ---- Face selection: a tap on the plate says which face the agent acts on ---------------
    //
    // The plate's tap is resolved against the engine's own B-rep: the picker answers in plate
    // coordinates, the plate object's placement is undone to the document's coordinates, and
    // model.faces says which face is there. The same face is then (a) selected in the engine
    // with select.set so the engine and the plate agree, (b) tinted amber by the plate's own
    // renderer, and (c) written into the next message's SELECTED FACE block.

    /**
     * The camera the face was picked through.
     *
     * A face is not a screen position, so an orbit keeps the selection; the words describing
     * which way it faces are re-derived from this orientation when the HUD or the prompt block
     * asks, and the tinted triangles never move with the camera.
     */
    @Volatile
    private var faceSelectionOrientation: ViewerOrientation? = null

    /** True while a tap's face is being resolved against the engine. */
    @Volatile
    private var faceSelectionBusy = false

    /**
     * Arms face picking on the plate.
     *
     * Only a live CAD document has engine faces. A mesh, a stale plate or a stopped engine each
     * get a sentence that names the reason, so the mode is never simply missing. Arming ends any
     * support-paint mode through [PlateModes]: a tap is one tool or the other, never both.
     */
    fun startFaceSelectMode() {
        val state = _uiState.value
        val document = state.cadDocument
        if (document == null) {
            val what = state.selectedModel?.name?.let { "the imported mesh \"" + it + "\"" }
                ?: "an empty plate"
            _uiState.update {
                it.copy(
                    statusMessage = "Face selection needs a live CAD document; the plate holds " +
                        what + ", and an imported mesh has no engine faces. Import > Open CAD " +
                        "document opens one.",
                )
            }
            return
        }
        if (document.converted) {
            _uiState.update {
                it.copy(
                    statusMessage = "Face selection needs the live document: the plate holds " +
                        "converted STL parts. Press Rebuild plate in the CAD agent sheet to take " +
                        "the document again, then select the face.",
                )
            }
            return
        }
        if (document.derivedStale) {
            _uiState.update {
                it.copy(
                    statusMessage = "Face selection needs the current document shape: " +
                        (document.staleReason ?: "press Rebuild plate in the CAD agent sheet first"),
                )
            }
            return
        }
        if (!SolidCraftEngine.isReady()) {
            _uiState.update {
                it.copy(
                    statusMessage = "Face selection needs the CAD engine: press Connect agent " +
                        "in the CAD agent sheet, then select the face again.",
                )
            }
            return
        }
        // The first part used to be force-selected here: the pick was once gated on the selected
        // model, so this was the workaround that made its faces reachable. SurfacePickGate now
        // accepts a face tap on any part, and silently moving the user's selection is not the
        // mode's business.
        _uiState.update {
            it.copy(
                faceSelectMode = true,
                paintMode = SupportPaintMode.NONE,
                smartInfillPicking = false,
                statusMessage = "Face selection: tap a face of " + document.name +
                    " on the plate - the agent will be told which one it is",
            )
        }
    }

    /** Ends face picking. A selected face stays selected: its tint and its prompt block remain. */
    fun exitFaceSelectMode() {
        _uiState.update { it.copy(faceSelectMode = false) }
    }

    /** Drops the selected face with one tap, tint and engine selection together. */
    fun clearFaceSelection() {
        val previous = _uiState.value.selectedFace ?: return
        _uiState.update { it.copy(selectedFace = null, faceTintTriangles = null, faceTintMesh = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { EngineFaceQuery { SolidCraftEngine.session() }.clear() }
        }
        _uiState.update {
            it.copy(
                statusMessage = "Face selection cleared: " + previous.name + " on " +
                    previous.body + " is no longer selected",
            )
        }
    }

    /**
     * Resolves one tap on the plate to the engine face under it.
     *
     * Runs off the main thread: model.faces and select.set are engine calls. The tap's triangle
     * names the body, the plate object's placement is undone to the document's coordinates, and
     * the matched face is tinted and selected. A tap on another object is named rather than
     * silently treated as the document.
     */
    fun selectFaceAt(hit: MeshPicker.Hit, orientation: ViewerOrientation?) {
        val state = _uiState.value
        if (!state.faceSelectMode || faceSelectionBusy) return
        val document = state.cadDocument ?: return
        if (document.converted) return
        val plateObject = state.models.getOrNull(hit.objectIndex) ?: return
        val body = document.bodyOf(plateObject.id)
        if (body == null) {
            _uiState.update {
                it.copy(
                    statusMessage = "That tap was on " + plateObject.name + ", not on the CAD " +
                        "document " + document.name + ". Select a face on one of its parts.",
                )
            }
            return
        }
        // A MeshPicker hit is already object-local: objectHit() subtracts the object's first
        // scene triangle, and every other consumer (paint, joints, the docs on Hit) treats
        // triangleIndex that way. Subtracting the scene offset again here rejected every part
        // that was not plate model 0 - the audit's VIEW-4. The offset below is only for the
        // tint buffer, which is one buffer over the whole scene.
        val sceneOffset = state.models.take(hit.objectIndex).sumOf { it.mesh.triangleCount }
        val bodyMesh = cadBodies.mesh(body)
        val localTriangle = hit.triangleIndex
        if (bodyMesh == null || localTriangle !in 0 until bodyMesh.triangleCount) {
            _uiState.update {
                it.copy(statusMessage = "That tap did not land on a body of the CAD document.")
            }
            return
        }
        faceSelectionBusy = true
        faceSelectionOrientation = orientation
        viewModelScope.launch {
            try {
                val query = EngineFaceQuery { SolidCraftEngine.session() }
                val pick = withContext(Dispatchers.IO) {
                    FaceSelectionFlow.select(
                        query = query,
                        body = body,
                        bodyMesh = bodyMesh,
                        displayMesh = plateObject.sourceMesh,
                        placement = plateObject.placement,
                        hit = hit,
                        localTriangleIndex = localTriangle,
                        orientation = orientation,
                    )
                } ?: throw IllegalStateException(
                    "the engine's face list has no face under that point",
                )
                val confirmed = pick.engineAgreed
                val sceneTriangles = IntArray(pick.triangles.size) { index ->
                    sceneOffset + pick.triangles[index]
                }
                _uiState.update {
                    it.copy(
                        selectedFace = pick.face,
                        faceTintTriangles = sceneTriangles,
                        faceTintMesh = plateObject.sourceMesh,
                        faceSelectMode = true,
                        statusMessage = if (confirmed) {
                            "Selected " + pick.face.hudLine()
                        } else {
                            "Selected " + pick.face.hudLine() +
                                " - the engine did not confirm its own selection, so the plate " +
                                "and the engine may disagree about it"
                        },
                    )
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        statusMessage = "Could not select that face: " +
                            (error.message ?: error.javaClass.simpleName).take(200),
                    )
                }
            } finally {
                faceSelectionBusy = false
            }
        }
    }

    /**
     * Re-resolves the selected face after a repaint, by the engine's own face NAME.
     *
     * A name that survived the edit (a box's top face after a taller box) keeps the selection:
     * its new index, area and centroid are taken from the engine's new report and its triangles
     * are re-tinted. A name the edit removed - or a body that went with it - drops the selection
     * with [FaceSelectionKeeper.goneSentence], so the plate never keeps an amber tint on
     * triangles that now belong to something else and never silently points at a different face.
     */
    private suspend fun reapplySelectedFace(session: SolidCraftSession) {
        val state = _uiState.value
        val previous = state.selectedFace ?: return
        val document = state.cadDocument ?: return
        if (document.converted) {
            dropSelectedFace(previous, "the document was converted to STL")
            return
        }
        val objectId = document.parts.firstOrNull { it.body == previous.body }?.objectId
        if (objectId == null) {
            dropSelectedFace(previous, "the body " + previous.body + " is no longer part of the document")
            return
        }
        val plateObject = state.models.firstOrNull { it.id == objectId }
        if (plateObject == null) {
            dropSelectedFace(previous, "the CAD part left the plate")
            return
        }
        val bodyMesh = cadBodies.mesh(previous.body)
        if (bodyMesh == null) {
            dropSelectedFace(previous, "the body " + previous.body + " is no longer in the document")
            return
        }
        val faces = withContext(Dispatchers.IO) {
            runCatching { EngineFaceQuery { session }.faces(previous.body) }.getOrNull()
        }
        if (faces == null) {
            // The engine did not answer the one question that could clear or keep the tint.
            // Keeping it would tint triangles the edit already replaced; drop it and say so.
            dropSelectedFace(previous, "the engine did not answer the face list after the edit")
            return
        }
        val map = plateObject.placement.documentPlacement(plateObject.sourceMesh)
        when (val outcome = FaceSelectionKeeper.reapply(
            previous = previous,
            body = previous.body,
            bodyMesh = bodyMesh,
            faces = faces,
            map = map,
            orientation = faceSelectionOrientation,
        )) {
            is FaceReapply.Retinted -> {
                val objectIndex = state.models.indexOfFirst { it.id == objectId }.coerceAtLeast(0)
                val sceneOffset = state.models.take(objectIndex).sumOf { it.mesh.triangleCount }
                val sceneTriangles = IntArray(outcome.triangles.size) { index ->
                    sceneOffset + outcome.triangles[index]
                }
                _uiState.update {
                    it.copy(
                        selectedFace = outcome.face,
                        faceTintTriangles = sceneTriangles,
                        faceTintMesh = plateObject.sourceMesh,
                    )
                }
                // The engine's selection follows its own face to its new index.
                withContext(Dispatchers.IO) {
                    runCatching {
                        EngineFaceQuery { session }.select(
                            outcome.face.body,
                            outcome.face.index,
                            outcome.face.pickPoint,
                        )
                    }
                }
            }
            is FaceReapply.Dropped -> _uiState.update {
                it.copy(
                    selectedFace = null,
                    faceTintTriangles = null,
                    faceTintMesh = null,
                    statusMessage = outcome.sentence,
                )
            }
        }
    }

    /** Drops a selection whose face did not survive, with the sentence naming it. */
    private fun dropSelectedFace(previous: SelectedFace, reason: String) {
        _uiState.update {
            it.copy(
                selectedFace = null,
                faceTintTriangles = null,
                faceTintMesh = null,
                statusMessage = faceClearedSentence(previous, reason),
            )
        }
    }

    /** Drops any face selection because the plate or the document changed under it. */
    private fun dropFaceSelection(reason: String) {
        val previous = _uiState.value.selectedFace
        _uiState.update { state ->
            val sentence = previous?.let { faceClearedSentence(it, reason) }
            state.copy(
                selectedFace = null,
                faceTintTriangles = null,
                faceTintMesh = null,
                faceSelectMode = false,
                statusMessage = when {
                    sentence == null -> state.statusMessage
                    state.statusMessage.isNullOrBlank() -> sentence
                    state.statusMessage.contains(sentence) -> state.statusMessage
                    else -> state.statusMessage + " " + sentence
                },
            )
        }
        if (previous != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { EngineFaceQuery { SolidCraftEngine.session() }.clear() }
            }
        }
    }

    /** The one shape a cleared selection's status line takes: it names the face and the reason. */
    private fun faceClearedSentence(face: SelectedFace, reason: String): String =
        "Face selection cleared: " + face.name + " on " + face.body + " - " + reason + "."

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

    private fun sliceModelCore() {
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

    // --- BumpMesh texture studio ----------------------------------------------------------

    /**
     * Opens the native studio for the selected plate object.
     *
     * It textures a plate mesh, not the CAD document: a live document's plate object is the
     * engine's display tessellation and would lose the texture on the next repaint, so that
     * case refuses by name and asks for Convert instead. A converted part is an ordinary STL
     * object and works like any import.
     */
    fun openBumpMeshStudio() {
        val state = _uiState.value
        if (state.isBusy) return
        val model = state.selectedModel
        val document = state.cadDocument
        val refusal = bumpMeshStudioLiveDocumentRefusal(
            modelId = model?.id,
            documentLoaded = document != null,
            documentConverted = document?.converted == true,
        )
        if (refusal != null) {
            _uiState.update { it.copy(statusMessage = refusal) }
            return
        }
        val limit = MeshTriangleLimits.current()
        // The pinned library manifest (101 textures) is the gallery; the host's own scan is the
        // fallback when a build has not staged the library.
        val textures = BumpMeshTextureCatalog.load(getApplication())
            .ifEmpty { bumpMeshEngine.textures() }
        bumpMeshEngine.warmUp()
        _uiState.update {
            // A region painted earlier is carried in only while it still belongs to this object
            // and this mesh; anything else would name triangles of a mesh that is not on screen.
            it.copy(
                bumpMeshStudio = bumpMeshStudioOpened(model, limit, textures, existing = it.bumpMeshStudio),
                textureUndoLabel = textureUndoHistory.railLabel,
                textureUndoCount = textureUndoHistory.size,
            )
        }
    }

    /** Closes the sheet; a running apply keeps running only if the user did not Cancel. */
    fun closeBumpMeshStudio() {
        _uiState.update {
            it.copy(bumpMeshStudio = it.bumpMeshStudio.copy(open = false))
        }
    }

    private fun updateBumpMeshStudio(
        transform: (BumpMeshStudioState) -> BumpMeshStudioState,
    ) {
        _uiState.update { it.copy(bumpMeshStudio = transform(it.bumpMeshStudio)) }
    }

    fun bumpMeshSearch(value: String) = updateBumpMeshStudio { it.withSearch(value) }

    fun bumpMeshCategory(value: String?) = updateBumpMeshStudio { it.withCategory(value) }

    fun bumpMeshTexture(texture: BumpMeshTextureRef) =
        updateBumpMeshStudio { it.withTexture(texture) }

    fun bumpMeshMapping(mode: BumpMeshMappingMode) = updateBumpMeshStudio { it.withMapping(mode) }

    fun bumpMeshTileMm(value: Float) = updateBumpMeshStudio { it.withTileMm(value) }

    fun bumpMeshHeightMm(value: Float) = updateBumpMeshStudio { it.withHeightMm(value) }

    fun bumpMeshSmoothing(value: Float) = updateBumpMeshStudio { it.withSmoothing(value) }

    fun bumpMeshRefineMm(value: Float) = updateBumpMeshStudio { it.withRefineMm(value) }

    fun bumpMeshWholeObject(whole: Boolean) = updateBumpMeshStudio { it.withWholeObject(whole) }

    fun bumpMeshTier(tier: BumpMeshTier) {
        updateBumpMeshStudio { it.withTier(tier) }
    }

    fun bumpMeshConfirmTier() = updateBumpMeshStudio { it.withTierConfirmed() }

    /**
     * Raises the app's own mesh triangle limit to what the chosen tier needs.
     *
     * The button is explicit because the limit is the user's: the studio never raises it by
     * itself, and the alternative - letting the page decimate to the lower limit - is the
     * silent cap the user asked us to refuse instead.
     */
    fun bumpMeshRaiseLimit() {
        val studio = _uiState.value.bumpMeshStudio
        val required = studio.limitCheck.required
        val saved = MeshTriangleLimits.save(getApplication(), required)
        updateBumpMeshStudio { it.withLimit(saved) }
        _uiState.update {
            it.copy(
                statusMessage = "Mesh triangle limit raised to " +
                    MeshTriangleLimits.formatCount(saved) + " for the texture studio.",
            )
        }
    }

    /**
     * Closes the sheet and arms the plate's dedicated texture-region brush.
     *
     * This is NOT the support-paint brush: the region is the studio's own set of triangle
     * indices, painted by its own mode and bar, and it never reads or writes the user's support
     * paint. The support brush is defused here only so the two tools cannot both own a touch.
     */
    fun bumpMeshArmPaint() {
        val state = _uiState.value
        val model = state.selectedModel ?: return
        if (state.isBusy) return
        setPaintMode(SupportPaintMode.NONE)
        _uiState.update { current ->
            val studio = current.bumpMeshStudio
            val sameMesh = studio.objectId == model.id &&
                studio.maskMeshTriangles == model.sourceMesh.triangleCount
            val region = if (sameMesh) {
                studio.paintedTriangles
            } else {
                // A different object or a new triangle numbering: the old region cannot name
                // this mesh's faces.
                emptySet()
            }
            current.copy(
                bumpMeshStudio = studio.copy(
                    open = false,
                    objectId = model.id,
                    sourceName = model.name,
                    sourceTriangles = model.sourceMesh.triangleCount,
                    paintedTriangles = region,
                    maskMeshTriangles = model.sourceMesh.triangleCount,
                ).withPainting(true),
                statusMessage = "Texture region: drag on the model to choose which faces get " +
                    "the texture. Done returns to the studio.",
            )
        }
    }

    /** The region bar's Draw / Erase choice; it only changes which way a stroke goes. */
    fun bumpMeshRegionBrush(brush: TextureRegionBrush) =
        updateBumpMeshStudio { it.withRegionBrush(brush) }

    /**
     * One stroke from the plate's texture-region brush.
     *
     * The brush is expanded on the object the finger hit, but only the studio's own object is
     * painted: a region is a fact about that mesh's triangle numbering, so a stroke on a
     * neighbour is ignored rather than silently stored against the wrong mesh.
     */
    fun textureRegionAt(hit: MeshPicker.Hit) {
        val snapshot = _uiState.value
        val studio = snapshot.bumpMeshStudio
        if (!studio.painting || snapshot.isBusy) return
        val target = snapshot.models.getOrNull(hit.objectIndex) ?: return
        if (studio.objectId != target.id) return
        val erase = studio.regionBrush == TextureRegionBrush.ERASE
        // The radius is the brush's own setting; reading it changes no support data.
        val radiusMm = target.supportPaint.brushRadiusMm.toFloat()
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
                val model = current.models.firstOrNull { it.id == target.id } ?: return@update current
                current.copy(
                    bumpMeshStudio = current.bumpMeshStudio.withRegionStroke(triangles, erase),
                    selectedModelId = model.id,
                )
            }
        }
    }

    /**
     * The region bar's Done: the brush goes away and the studio comes back with the region it
     * kept, on Painted region so Apply is one press away.
     */
    fun bumpMeshFinishPaint() {
        val state = _uiState.value
        if (!state.bumpMeshStudio.painting) return
        setPaintMode(SupportPaintMode.NONE)
        _uiState.update {
            val opened = bumpMeshStudioAfterPaintDone(it.bumpMeshStudio)
            it.copy(
                bumpMeshStudio = opened,
                statusMessage = if (opened.paintedTriangles.isEmpty()) {
                    "No region painted; the studio is still on Whole object."
                } else {
                    MeshTriangleLimits.formatCount(opened.paintedTriangles.size) +
                        " painted triangles are the studio's region."
                },
            )
        }
    }

    /**
     * Applies the sheet's settings in place.
     *
     * The selected plate object keeps its id, name and placement; only its source mesh (and so
     * its displayed mesh) is replaced. The previous object is pushed onto the bounded texture
     * undo stack, the paint is cleared because the mesh's triangle numbering just changed, and
     * the published slice is invalidated so the plate tells the user to slice again.
     */
    fun applyBumpMeshTexture() {
        val snapshot = _uiState.value
        val studio = snapshot.bumpMeshStudio
        val model = snapshot.selectedModel
        if (studio.applying) return
        if (model == null) {
            updateBumpMeshStudio { it.failed("Select a plate object first.") }
            return
        }
        studio.applyBlockedReason?.let { reason ->
            updateBumpMeshStudio { it.failed(reason) }
            return
        }
        val unavailable = bumpMeshEngine.unavailableReason
        if (unavailable != null) {
            updateBumpMeshStudio { it.failed(unavailable) }
            return
        }
        val texture = studio.texture ?: return
        // The include mask is the studio's own region, never the support paint's triangles.
        val mask = if (studio.wholeObject) {
            null
        } else if (studio.objectId != model.id || studio.maskStale) {
            null
        } else {
            textureRegionMaskOf(studio.paintedTriangles, model.sourceMesh.triangleCount)
        }
        if (!studio.wholeObject && mask == null) {
            updateBumpMeshStudio {
                it.failed(
                    if (studio.maskStale) {
                        "The painted region belonged to a previous mesh; paint it again."
                    } else {
                        "Paint a region on the plate first, or switch back to Whole object."
                    },
                )
            }
            return
        }
        if (!beginOperation("Texturing " + model.name + "…")) return
        updateBumpMeshStudio { it.starting() }
        bumpMeshApplyJob = viewModelScope.launch {
            try {
                val sourceStl = withContext(Dispatchers.IO) {
                    val directory = java.io.File(app.cacheDir, "bumpmesh-source")
                    directory.mkdirs()
                    val file = java.io.File(directory, "studio-" + System.nanoTime() + ".stl")
                    StlMeshWriter.writeBinary(model.sourceMesh, file)
                    file
                }
                val request = BumpMeshApplyRequest(
                    sourceStl = sourceStl,
                    sourceTriangles = model.sourceMesh.triangleCount,
                    texture = texture,
                    mapping = studio.mapping,
                    tileMm = studio.tileMm,
                    heightMm = studio.heightMm,
                    smoothing = studio.smoothing,
                    refineMm = studio.refineMm,
                    maxTriangles = studio.tier.outputCap,
                    mask = mask,
                )
                val result = bumpMeshEngine.apply(request) { phase, fraction ->
                    // The engine may report from its own thread; the state flow is the one
                    // thread-safe channel back to Compose.
                    _uiState.update { state ->
                        state.copy(
                            bumpMeshStudio = state.bumpMeshStudio.progressed(phase, fraction),
                        )
                    }
                }
                val parsed = withContext(Dispatchers.IO) {
                    StlParser.parse(
                        file = result.stl,
                        displayName = model.name,
                        maxTriangles = maxOf(MeshTriangleLimits.current(), result.triangles),
                    )
                }
                val current = _uiState.value
                val stillThere = current.models.firstOrNull { it.id == model.id }
                if (stillThere == null) {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            bumpMeshStudio = it.bumpMeshStudio.failed(
                                "That object left the plate while the texture was running; " +
                                    "nothing was changed.",
                            ),
                        )
                    }
                    return@launch
                }
                // The user's support paint is deliberately untouched by a texture apply: it
                // is their slicer data, not ours to clear, and the requirement is that it
                // survives entering, painting, applying and undoing the studio.
                val replaced = plateObjectWithTextureMesh(stillThere, parsed)
                textureUndoHistory.push(
                    TextureUndoEntry(
                        objectId = stillThere.id,
                        textureName = texture.displayName,
                        before = stillThere,
                        appliedSourceMesh = parsed,
                    ),
                )
                val repair = result.repairWarnings.firstOrNull()
                val resultLine = "Applied " + texture.displayName + " · " +
                    MeshTriangleLimits.formatCount(result.triangles) + " triangles"
                _uiState.update { state ->
                    state.withModel(stillThere.id) { replaced }
                        .withoutPublishedSlice(
                            resultLine + " to " + replaced.name +
                                " · slice again to export G-code",
                        )
                        .copy(
                            isBusy = false,
                            bumpMeshStudio = state.bumpMeshStudio.finished(resultLine, repair),
                            textureUndoLabel = textureUndoHistory.railLabel,
                            textureUndoCount = textureUndoHistory.size,
                        )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _uiState.update {
                    it.copy(isBusy = false, bumpMeshStudio = it.bumpMeshStudio.cancelled())
                }
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        bumpMeshStudio = it.bumpMeshStudio.failed(
                            error.message ?: "The texture apply failed",
                        ),
                    )
                }
            } finally {
                bumpMeshApplyJob = null
            }
        }
    }

    /** Stops the running apply; the sheet says the mesh was not changed. */
    fun cancelBumpMeshApply() {
        bumpMeshEngine.cancel()
        bumpMeshApplyJob?.cancel()
        bumpMeshApplyJob = null
        _uiState.update {
            it.copy(isBusy = false, bumpMeshStudio = it.bumpMeshStudio.cancelled())
        }
    }

    // ---- Mesh -> STEP: the Mesh-Mix-S2C port's UI half -----------------------------------
    //
    // One on-device conversion at a time, in a sheet that survives rotation: the engine seam is
    // MeshToStepEngine and the only converter in the app is the engine lane's. The result is a
    // cache file; the human saves it with the same SAVE route the CAD exports use, and the
    // status line names where it went.

    /** Opens the sheet on the selected plate object; a live CAD document's own body refuses. */
    fun openMeshToStep() {
        val state = _uiState.value
        if (state.isBusy) return
        val model = state.selectedModel
        val document = state.cadDocument
        val refusal = meshToStepRefusal(
            modelId = model?.id,
            documentLoaded = document != null,
            documentConverted = document?.converted == true,
        )
        if (refusal != null) {
            _uiState.update { it.copy(statusMessage = refusal) }
            return
        }
        _uiState.update { it.copy(meshToStep = meshToStepOpened(model)) }
    }

    /**
     * Closing the sheet cancels a running conversion: an invisible CPU-heavy job must not keep
     * running behind the plate. A finished result is simply forgotten - the cache file stays.
     */
    fun closeMeshToStep() {
        meshToStepEngine.cancel()
        meshToStepJob?.cancel()
        meshToStepJob = null
        _uiState.update {
            it.copy(isBusy = false, meshToStep = it.meshToStep.closed())
        }
    }

    fun meshToStepPreset(value: MeshToStepPreset) =
        updateMeshToStep { it.withPreset(value) }

    fun meshToStepQuality(value: Float) =
        updateMeshToStep { it.withQuality(value) }

    fun meshToStepUnit(value: MeshToStepUnit) =
        updateMeshToStep { it.withUnit(value) }

    fun meshToStepRepair(value: MeshToStepRepairMode) =
        updateMeshToStep { it.withRepair(value) }

    fun meshToStepMergeOverlaps(value: Boolean) =
        updateMeshToStep { it.withMergeOverlaps(value) }

    /**
     * Starts the conversion.
     *
     * Two refusals are by name: a second Start while one runs, and a plate that is not a mesh
     * (a live CAD document's own part, which must be Converted first). The converting state is
     * set before the engine call, so the sheet's Cancel exists from the first second.
     */
    fun startMeshToStep() {
        val snapshot = _uiState.value
        if (snapshot.meshToStep.converting) {
            updateMeshToStep {
                it.copy(error = "A Mesh -> STEP conversion is already running - wait for it or Cancel it.")
            }
            return
        }
        if (snapshot.isBusy) {
            updateMeshToStep {
                it.copy(error = "Another operation is already running - wait for it to finish.")
            }
            return
        }
        val model = snapshot.selectedModel
        val document = snapshot.cadDocument
        val refusal = meshToStepRefusal(
            modelId = model?.id,
            documentLoaded = document != null,
            documentConverted = document?.converted == true,
        )
        if (refusal != null) {
            updateMeshToStep { it.failed(refusal) }
            _uiState.update { it.copy(statusMessage = refusal) }
            return
        }
        val target = model ?: return
        if (!beginOperation("Converting " + target.name + " to STEP…")) return
        val sheet = snapshot.meshToStep
        updateMeshToStep { it.starting() }
        meshToStepJob = viewModelScope.launch {
            try {
                val sourceStl = withContext(Dispatchers.IO) {
                    val directory = File(app.cacheDir, "mesh-to-step")
                    directory.mkdirs()
                    val file = File(directory, "source-" + System.nanoTime() + ".stl")
                    StlMeshWriter.writeBinary(target.sourceMesh, file)
                    file
                }
                // convert() is blocking CPU/process work: it must never run on the main
                // thread. The engine's progress callback is still delivered on the main thread
                // (its contract), and the state flow is the one channel back to Compose.
                val result = withContext(Dispatchers.IO) {
                    meshToStepEngine.convert(meshToStepRequestFor(sourceStl, sheet)) { progress ->
                        _uiState.update { state ->
                            state.copy(
                                meshToStep = state.meshToStep.progressed(
                                    progress.stage,
                                    progress.fraction,
                                    progress.message,
                                ),
                            )
                        }
                    }
                }
                val info = meshToStepResultInfo(result)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        meshToStep = it.meshToStep.succeeded(info),
                        statusMessage = "Converted " + target.name + " to STEP (" + info.summaryLine +
                            ") · choose Save STEP to keep the file",
                    )
                }
            } catch (cancelled: CancellationException) {
                _uiState.update {
                    it.copy(isBusy = false, meshToStep = it.meshToStep.cancelled())
                }
            } catch (error: Throwable) {
                val alreadyCancelled = !_uiState.value.meshToStep.converting
                _uiState.update { state ->
                    if (alreadyCancelled) {
                        state.copy(isBusy = false)
                    } else {
                        val sentence = error.message ?: "The Mesh -> STEP conversion failed."
                        state.copy(
                            isBusy = false,
                            meshToStep = state.meshToStep.failed(sentence),
                            statusMessage = "Mesh -> STEP failed: " + sentence,
                        )
                    }
                }
            } finally {
                meshToStepJob = null
            }
        }
    }

    /** Kills the engine process and puts the sheet back to a clean cancelled state at once. */
    fun cancelMeshToStep() {
        meshToStepEngine.cancel()
        meshToStepJob?.cancel()
        meshToStepJob = null
        _uiState.update {
            it.copy(isBusy = false, meshToStep = it.meshToStep.cancelled())
        }
    }

    /**
     * Copies the engine's cache STEP to the user's own SAVE destination and says where it went.
     *
     * The engine never touches SAF: the file is ours to copy, and the status sentence names the
     * destination name it was given.
     */
    fun saveMeshToStep(uri: Uri) {
        val result = _uiState.value.meshToStep.result ?: return
        val path = result.stepPath ?: return
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val source = File(path)
                    check(source.isFile && source.length() > 0L) {
                        "The engine's STEP file is gone from the cache; convert the mesh again."
                    }
                    app.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Unable to open the STEP destination")
                    displayName(uri) to source.length()
                }
                _uiState.update {
                    it.copy(
                        meshToStep = it.meshToStep.saved(saved.first, uri.toString()),
                        statusMessage = "Saved STEP to " + saved.first + " (" +
                            formatFileSize(saved.second) + ") · open it as a CAD document from the sheet",
                    )
                }
            } catch (error: Throwable) {
                showOperationFailure(error)
            }
        }
    }

    /** Opens the STEP the user just saved through the existing SolidCraft/Open-CAD route. */
    fun openSavedMeshToStepAsCad() {
        val savedUri = _uiState.value.meshToStep.savedUri ?: return
        // The press is the answer to the sheet; the document route reports its own status on
        // the plate, so the sheet must not stay on top of the result it just opened.
        updateMeshToStep { it.closed() }
        openCadDocument(Uri.parse(savedUri))
    }

    private fun updateMeshToStep(
        transform: (MeshToStepState) -> MeshToStepState,
    ) {
        _uiState.update { it.copy(meshToStep = transform(it.meshToStep)) }
    }

    /**
     * Takes the top texture back off the selected object.
     *
     * The step is only used while the object still carries the mesh that apply installed; an
     * import, a split or another apply since then drops it with a named sentence instead of
     * pasting an older texture over a mesh it does not belong to.
     */
    fun undoTextureChange() {
        val entry = textureUndoHistory.pop() ?: return
        val state = _uiState.value
        val current = state.models.firstOrNull { it.id == entry.objectId }
        if (current == null) {
            _uiState.update {
                it.copy(
                    bumpMeshStudio = it.bumpMeshStudio.copy(
                        paintedTriangles = emptySet(),
                        maskMeshTriangles = 0,
                        painting = false,
                    ),
                    textureUndoLabel = textureUndoHistory.railLabel,
                    textureUndoCount = textureUndoHistory.size,
                    statusMessage = "That object is no longer on the plate; the texture undo " +
                        "step was dropped.",
                )
            }
            return
        }
        if (current.sourceMesh !== entry.appliedSourceMesh) {
            _uiState.update {
                it.copy(
                    // The undo will not run, but a region painted against the current mesh is
                    // still this mesh's; it stays. The studio's mask is only dropped when the
                    // mesh actually changes under it.
                    textureUndoLabel = textureUndoHistory.railLabel,
                    textureUndoCount = textureUndoHistory.size,
                    statusMessage = "The mesh changed since this texture; undoing it would " +
                        "replace a different mesh, so the step was dropped.",
                )
            }
            return
        }
        _uiState.update { snapshot ->
            snapshot.withModel(entry.objectId) { entry.before }
                .withoutPublishedSlice(
                    "Undid texture " + entry.textureName + " on " + entry.before.name +
                        " · slice again to export G-code",
                )
                .copy(
                    bumpMeshStudio = snapshot.bumpMeshStudio.copy(
                        paintedTriangles = emptySet(),
                        maskMeshTriangles = 0,
                        painting = false,
                    ),
                    textureUndoLabel = textureUndoHistory.railLabel,
                    textureUndoCount = textureUndoHistory.size,
                )
        }
    }

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
                    // PrusaSlicer's and OrcaSlicer's snapshots are not tied to Cura's
                    // pending import, so they come back whatever else is being restored.
                    val prusaSnapshot = stateStore.prusaSnapshotBaseline()
                    val orcaSnapshot = stateStore.orcaSnapshotBaseline()
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
                        prusaSnapshot = prusaSnapshot,
                        orcaSnapshot = orcaSnapshot,
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
                // Whatever else was restored, the two engines' snapshots come back with
                // it: their profile blocks show the imported file again, and their resets
                // can still put its values back.
                importedPrusaBaseline = restored.prusaSnapshot
                importedOrcaBaseline = restored.orcaSnapshot
                if (restored.prusaSnapshot != null || restored.orcaSnapshot != null) {
                    _uiState.update { state ->
                        state.copy(
                            prusaSnapshot = restored.prusaSnapshot?.let { baseline ->
                                EngineSnapshot(
                                    name = baseline.profileName,
                                    source = baseline.profileSource,
                                    importedValues = baseline.importedValues,
                                    notSentKeys = baseline.unmappedKeys,
                                )
                            },
                            orcaSnapshot = restored.orcaSnapshot?.let { baseline ->
                                EngineSnapshot(
                                    name = baseline.profileName,
                                    source = baseline.profileSource,
                                    importedValues = baseline.importedValues,
                                    notSentKeys = baseline.refusedKeys,
                                )
                            },
                        )
                    }
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
        // The record the descriptor carried, when it carried one: the pair's own source halves
        // and the joints built on them, which is what lets a joint be opened and edited in
        // this session rather than only added to. Null - no record, no joints - for every
        // descriptor written before this existed.
        val record = pair?.let { saved ->
            val lowSource = saved.lowSourcePath?.takeIf { it.isNotBlank() } ?: return@let null
            val highSource = saved.highSourcePath?.takeIf { it.isNotBlank() } ?: return@let null
            SnapRecord(
                lowSourcePath = lowSource,
                highSourcePath = highSource,
                scale = saved.scale,
                fullJoint = saved.fullJoint,
                socketRamp = saved.socketRamp,
                hookLengthMm = saved.hookLengthMm,
                hookThicknessMm = saved.hookThicknessMm,
                hookLipMm = saved.hookLipMm,
                joints = saved.joints.map { joint ->
                    SnapJointRecord(
                        anchorLocalMm = Vec3(joint.anchorXMm, joint.anchorYMm, joint.anchorZMm),
                        depthMm = joint.depthMm,
                        onLowHalf = joint.onLowHalf,
                        beamHalf = joint.beamHalf,
                        tightness = joint.tightness,
                        barbs = joint.barbs,
                        facing = joint.facing,
                        style = joint.style,
                        lipEngagementMm = joint.lipEngagementMm,
                        lipToothHeightMm = joint.lipToothHeightMm,
                    )
                },
            )
        }
        // A record whose pair did not survive the restore has no owner any more, and its
        // files would sit in the model directory for good: the pair is the only thing that
        // can rebuild from them.
        if (record == null) {
            snapshot.snap?.let { saved ->
                val lowSource = saved.lowSourcePath
                val highSource = saved.highSourcePath
                if (!lowSource.isNullOrBlank() && !highSource.isNullOrBlank()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        deletePairSources(listOf(lowSource, highSource))
                    }
                }
            }
        }
        // The CAD document the descriptor held, when its design file is still there. The plate
        // objects that were restored ARE the document's parts (or its converted STLs), one file
        // each; the design is the document, and the engine is started again by Connect.
        val restoredCad = snapshot.cad?.let { saved ->
            if (!File(saved.designPath).isFile) return@let null
            fun restoredParts(parts: List<WorkspaceStateStore.CadState.PartState>): List<CadPart> =
                parts.mapNotNull { part ->
                    part.takeIf { savedPart -> objects.any { it.id == savedPart.objectId } }
                        ?.let { CadPart(body = it.body, objectId = it.objectId) }
                }
            val legacyObjectId = saved.displayObjectId
                ?.takeIf { id -> objects.any { it.id == id } }
                ?: objects.firstOrNull { it.sourcePath == saved.displayMeshPath }?.id
            CadDocument(
                name = saved.name,
                designPath = saved.designPath,
                sourcePath = saved.sourcePath,
                displayObjectId = legacyObjectId,
                displayMeshPath = saved.displayMeshPath,
                parts = restoredParts(saved.parts),
                converted = saved.converted,
                convertedRevision = saved.convertedRevision,
                convertedParts = restoredParts(saved.convertedParts),
                derivedStale = saved.derivedStale,
                staleReason = saved.staleReason,
                displayRevision = saved.displayRevision,
                triangleCount = saved.triangleCount,
                bodyCount = saved.bodyCount,
            )
        }
        cadDerivedPlateIds.clear()
        snapshot.cad?.derivedObjectIds.orEmpty().forEach { id ->
            if (objects.any { it.id == id }) cadDerivedPlateIds.add(id)
        }
        if (restoredCad != null) {
            cadPartIds.clear()
            for (part in restoredCad.parts) cadPartIds[part.body] = part.objectId
        }
        _uiState.update { current ->
            current.withoutPublishedSlice()
                .withModels(objects)
                .copy(
                    isBusy = false,
                    cadDocument = restoredCad,
                    statusMessage = when {
                        restoredCad != null -> (
                            if (restoredCad.converted) {
                                "Restored CAD document ${restoredCad.name} · " +
                                    "${restoredCad.convertedParts.size} converted parts on the " +
                                    "plate · slice or convert it again; connect the CAD agent to " +
                                    "edit the design"
                            } else {
                                "Restored CAD document ${restoredCad.name} · " +
                                    "${restoredCad.parts.size} parts · connect the CAD agent to " +
                                    "work on it; Slice needs Convert to STL"
                            }
                            ) + if (derivedLayoutOnPlate(restoredCad)) {
                                " · the plate keeps a split layout made from its previous shape; " +
                                    "press Rebuild plate to take the document's current shape"
                            } else {
                                ""
                            }
                        objects.size > 1 ->
                            "Restored ${objects.size} models on the plate; slice again to create validated G-code"
                        else ->
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
                    record = record,
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
            cad = state.cadDocument?.let { document ->
                WorkspaceStateStore.CadState(
                    name = document.name,
                    designPath = document.designPath,
                    sourcePath = document.sourcePath,
                    displayMeshPath = document.displayMeshPath,
                    displayRevision = document.displayRevision,
                    triangleCount = document.triangleCount,
                    bodyCount = document.bodyCount,
                    displayObjectId = document.displayObjectId,
                    parts = document.parts.map {
                        WorkspaceStateStore.CadState.PartState(it.body, it.objectId)
                    },
                    converted = document.converted,
                    convertedRevision = document.convertedRevision,
                    convertedParts = document.convertedParts.map {
                        WorkspaceStateStore.CadState.PartState(it.body, it.objectId)
                    },
                    derivedStale = document.derivedStale,
                    staleReason = document.staleReason,
                    derivedObjectIds = cadDerivedPlateIds.toList(),
                )
            },
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
        // The pair's record travels with it: the joints on it are only editable next session
        // if the halves they were built from do too. A record whose files have gone is not
        // written - there would be nothing to rebuild the joints from, and the pair alone is
        // what the plate can still honestly offer.
        val record = liveSnapRecord?.takeIf { saved -> saved.sourcePaths.all { File(it).isFile } }
        return WorkspaceStateStore.SnapState(
            lowHalfId = low,
            highHalfId = high,
            axis = snapAxis,
            lowFaceMm = lowFace,
            highFaceMm = highFace,
            lowSourcePath = record?.lowSourcePath,
            highSourcePath = record?.highSourcePath,
            scale = record?.scale ?: 1f,
            fullJoint = record?.fullJoint ?: false,
            socketRamp = record?.socketRamp ?: false,
            hookLengthMm = record?.hookLengthMm,
            hookThicknessMm = record?.hookThicknessMm,
            hookLipMm = record?.hookLipMm,
            joints = record?.joints?.map { joint ->
                WorkspaceStateStore.SnapState.JointState(
                    anchorXMm = joint.anchorLocalMm.x,
                    anchorYMm = joint.anchorLocalMm.y,
                    anchorZMm = joint.anchorLocalMm.z,
                    onLowHalf = joint.onLowHalf,
                    beamHalf = joint.beamHalf,
                    tightness = joint.tightness,
                    barbs = joint.barbs,
                    facing = joint.facing,
                    style = joint.style,
                    // Every one of the lip's own choices, not just the first two:
                    // the wall, the depth, the cut width and depth and the cuts
                    // themselves are what the next session rebuilds the ring from,
                    // and a workspace that drops them silently re-tunes the joint.
                    lipEngagementMm = joint.lipEngagementMm,
                    lipToothHeightMm = joint.lipToothHeightMm,
                    lipWallMm = joint.lipWallMm,
                    lipRecessExtraMm = joint.lipRecessExtraMm,
                    lipCutWidthMm = joint.lipCutWidthMm,
                    lipCutDepthMm = joint.lipCutDepthMm,
                    lipCuts = joint.lipCuts,
                    lipCutDirections = joint.lipCutDirections,
                )
            }.orEmpty(),
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
        // An imported model is a different plate: a face picked on the previous CAD document is
        // not a face of anything on this one, so the selection goes with a sentence naming it.
        dropFaceSelection("a model was imported")
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
    // The queue itself is declared above init: its collector can run before this point.

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
