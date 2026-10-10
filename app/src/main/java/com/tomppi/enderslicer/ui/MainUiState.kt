package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.annotation.AnnotationAnchor
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.engine.LayerEvent
import com.tomppi.enderslicer.model.CuraMachineCatalog
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.data.PlatePreferences
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaPresetOption
import com.tomppi.enderslicer.model.PrusaPresetSelection
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.profile.CuraEngineProfile
import com.tomppi.enderslicer.smartinfill.SmartInfillOverlay
import com.tomppi.enderslicer.supportpaint.SupportPaintMode
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import com.tomppi.enderslicer.viewer.AnnotationOverlay
import com.tomppi.enderslicer.viewer.StlMesh

data class MainUiState(
    val printer: PrinterDefinition,
    val settings: SlicerSettings = SlicerSettings(),
    val prusaSettings: PrusaSliceSettings = PrusaSliceSettings(),
    val extraCuraSettings: Map<String, String> = emptyMap(),
    /** The Cura definition chain the slice uses; the pickable machines are in [curaMachines]. */
    val curaMachineId: String = CuraMachineCatalog.DEFAULT_MACHINE_ID,
    /** Every machine in the bundled Cura tree, loaded off the main thread after start. */
    val curaMachines: List<CuraMachineCatalog.Machine> = emptyList(),
    val extraPrusaSettings: Map<String, String> = emptyMap(),
    /** The PrusaSlicer preset selection, plus the pickers' entries once the bundle is loaded. */
    val prusaPreset: PrusaPresetSelection = PrusaPresetSelection.NONE,
    val prusaPresetPrinters: List<PrusaPresetOption> = emptyList(),
    val prusaPresetPrints: List<PrusaPresetOption> = emptyList(),
    val prusaPresetFilaments: List<PrusaPresetOption> = emptyList(),
    val orcaSettings: OrcaSliceSettings = OrcaSliceSettings(),
    val extraOrcaSettings: Map<String, String> = emptyMap(),
    /** Prusa-imported start/end gcode; the Cura path never sees them. */
    val prusaStartGcode: String = "",
    val prusaEndGcode: String = "",
    /**
     * Every model on the plate, in import order.
     *
     * The plate held exactly one of these until multi-object printing. [selectedModel] is the one
     * the tools, the gestures and the paint brush act on, and [mesh], [modelPath] and
     * [modelPlacement] below answer for it, so the many callers that mean "the model" keep
     * working without knowing there can be several.
     */
    val models: List<PlateObject> = emptyList(),
    /** The object the tools act on. Null exactly when the plate is empty. */
    val selectedModelId: String? = null,
    /** How the plate is laid out and printed. A workflow choice, not a slicing setting. */
    val platePreferences: PlatePreferences = PlatePreferences(),
    /** True while there is a placement change to take back. */
    val canUndoPlacement: Boolean = false,
    /** What undoing would take back, for the button's own label. */
    val undoPlacementLabel: String? = null,
    val paintMode: SupportPaintMode = SupportPaintMode.NONE,

    /**
     * True while the Smart Infill sheet is waiting for a surface tap. A tap is
     * then offered to its handler instead of painting support.
     */
    val smartInfillPicking: Boolean = false,
    /**
     * The Smart Infill boundary conditions drawn on the model, or null when
     * the panel is closed and nothing should be tinted.
     */
    val smartInfillOverlay: SmartInfillOverlay? = null,
    /** True while the annotation tool owns single-finger gestures. */
    val annotationActive: Boolean = false,
    /** Line geometry for the annotation overlay, or null when there is none. */
    val annotationOverlay: AnnotationOverlay? = null,
    /** True when the segment being placed has both ends and can be committed. */
    val annotationCanLockSegment: Boolean = false,
    /** True when the series so far has enough points to commit. */
    val annotationCanLockSeries: Boolean = false,
    /** True when a point has been placed but the segment still needs its other end. */
    val annotationAwaitingSecondPoint: Boolean = false,
    val annotationAnchor: AnnotationAnchor? = null,
    /** Length of the segment being placed. */
    val annotationMeasureMm: Float? = null,
    /** Total length of the series being drawn, including the segment in progress. */
    val annotationChainMm: Float? = null,
    /** Locked series. */
    val annotationChainCount: Int = 0,
    /** Points already committed to the series in progress. */
    val annotationSeriesPoints: Int = 0,
    /** Line width in screen pixels. */
    val annotationThicknessPx: Float = 6f,
    /** Height of the plane points are placed on, in model millimetres. */
    val annotationWorkPlaneZ: Float = 0f,
    /** True while a handle is having its height adjusted on its own. */
    val annotationZAdjusting: Boolean = false,
    val annotationZMin: Float = 0f,
    val annotationZMax: Float = 100f,
    val annotationSavedPath: String? = null,
    val importedSceneTransformAvailable: Boolean = false,
    val importedSceneModelName: String? = null,
    val sliceResultId: String? = null,
    val gcodePath: String? = null,
    /**
     * True when the artifact named by [sliceResultId] and [gcodePath] was verified complete
     * when it was stored. The check runs once on the engine's IO path, so the Compose bodies
     * that call [hasCurrentGcode] never touch the disk.
     */
    val gcodeComplete: Boolean = false,
    val baseGcodePath: String? = null,
    /** Engine that produced the current slice result; gates engine-specific features. */
    val sliceEngine: SlicerEngine? = null,
    val layerPreview: GcodeLayerPreview? = null,
    val layerEvents: List<LayerEvent> = emptyList(),
    val estimatedPrintSeconds: Int? = null,
    val sliceLogPath: String? = null,
    val sliceDurationMilliseconds: Long? = null,
    val profileName: String = "Built-in current Cura settings",
    val profileSource: String = "Cura 5.14.0-alpha.0 / setting version 27 reference",
    val importedRawSettingCount: Int = 0,
    val curaVersion: String? = null,
    val settingVersion: String? = "27",
    val engineProfile: CuraEngineProfile? = null,
    val startGcode: String = "",
    val endGcode: String = "",
    val engineStatus: String = "",
    val engineAvailable: Boolean = false,
    val warnings: List<String> = emptyList(),
    val statusMessage: String = "Import an STL to begin",
    val isBusy: Boolean = false,
    /**
     * How far the running slice has got, when the engine reports it at all.
     *
     * PrusaSlicer and OrcaSlicer report a percentage as they work; CuraEngine's
     * JNI path does not, so this stays null there and the UI shows a spinner
     * instead of a bar. It is only read while [isBusy].
     */
    val sliceProgressPercent: Int? = null,
) {
    /** The object every single-model path means: the selected one, or the only one there is. */
    val selectedModel: PlateObject?
        get() = models.firstOrNull { it.id == selectedModelId } ?: models.firstOrNull()

    /** The selected object's mesh, as drawn and as handed to the engines. */
    val mesh: StlMesh? get() = selectedModel?.mesh

    /** The file the selected object was imported from. */
    val modelPath: String? get() = selectedModel?.sourcePath

    /**
     * The object that hangs deepest below the build plate at Z=0, or null when
     * nothing on the plate does.
     *
     * The slice cuts every object at Z=0, not only the selected one, so the
     * caution has to answer for the whole plate: a part that is not the one
     * being edited still loses whatever is under the bed. Reading the placed
     * meshes is also what makes it survive a restore, which selects the first
     * object on the plate rather than the one that was being worked on.
     */
    val belowBedModel: PlateObject?
        get() = models.filter { it.mesh.bounds.minZ < 0f }.minByOrNull { it.mesh.bounds.minZ }

    /**
     * How much of the plate hangs below the build plate at Z=0, in mm, or 0.0
     * when none of it does.
     *
     * This is the lossy cut the plate warns about: the slice clips the model at
     * Z=0 and prints what is above, so this much of [belowBedModel] will not be
     * printed. It is read from the placed meshes rather than stored, because
     * those meshes are what the viewer draws and what every placement writes -
     * so a drag, a typed value, a lift, an undo, a re-arrange and a restored
     * workspace all answer correctly, and it returns to zero the moment the
     * model is raised again.
     */
    val belowBedCutMm: Double
        get() {
            val minZ = belowBedModel?.mesh?.bounds?.minZ ?: return 0.0
            return if (minZ < 0f) -minZ.toDouble() else 0.0
        }

    /**
     * The one line the plate shows when part of a model is under the bed.
     *
     * The number is the part that will not be printed and the sentence says so
     * plainly: the move is allowed, and this is what it costs. The deepest
     * object is named when it is not the selected one, because this line is
     * drawn under the selected object's own summary as well: without the name
     * the reader would look for the cut on the wrong part. An empty string when
     * nothing hangs below the plate, so a caller can render it unconditionally.
     */
    val belowBedNotice: String
        get() {
            val cutMm = belowBedCutMm
            if (cutMm <= 0.0) return ""
            val deepest = belowBedModel
            val subject = if (deepest == null || deepest.id == selectedModel?.id) {
                "this model"
            } else {
                deepest.name
            }
            return String.format(
                java.util.Locale.US,
                "%.2f mm of %s is below the build plate; that part will not be printed.",
                cutMm,
                subject,
            )
        }

    /** Where the selected object sits on the bed. */
    val modelPlacement: ModelPlacement?
        get() = selectedModel?.placement ?: models.firstOrNull()?.placement

    /** The selected object's paint: the brush belongs to the object it paints. */
    val supportPaint: SupportPaintState
        get() = selectedModel?.supportPaint ?: SupportPaintState()

    /** Adds a model and selects it - a freshly imported object is the one being worked on. */
    fun withModelAdded(model: PlateObject): MainUiState =
        copy(models = models + model, selectedModelId = model.id)

    /**
     * [name], made unique against the plate.
     *
     * Two parts imported from the same file would otherwise carry one name into the plate list
     * and into the engines' object labels. OrcaSlicer appends the instance to every label;
     * PrusaSlicer appends one only when a single model object holds several instances, which is
     * not how a plate of separate parts arrives - so on a Klipper host two parts sharing a name
     * are one cancellable object, and on any printer they are two rows the user cannot tell apart.
     */
    fun uniqueModelName(name: String): String {
        if (models.none { it.name == name }) return name
        var index = 2
        while (models.any { it.name == "$name ($index)" }) index++
        return "$name ($index)"
    }

    /** Replaces the list, keeping the selection when the object it named still exists. */
    fun withModels(next: List<PlateObject>): MainUiState = copy(
        models = next,
        selectedModelId = selectedModelId?.takeIf { id -> next.any { it.id == id } }
            ?: next.firstOrNull()?.id,
    )

    /** Replaces one object by id. */
    fun withModel(id: String, update: (PlateObject) -> PlateObject): MainUiState =
        copy(models = models.map { if (it.id == id) update(it) else it })

    /** Replaces the selected object; a no-op when the plate is empty. */
    fun withSelectedModel(update: (PlateObject) -> PlateObject): MainUiState {
        val target = selectedModel ?: return this
        return withModel(target.id, update)
    }

    /** Takes one object off the plate, selecting another if the removed one was selected. */
    fun withoutModel(id: String): MainUiState = withModels(models.filterNot { it.id == id })

    /** The stored result is exportable; completeness is the cached value from when it was stored. */
    fun hasCurrentGcode(): Boolean = sliceResultId != null && gcodePath != null && gcodeComplete

    /**
     * Drops every artifact of the published slice, and reports why in [statusMessage].
     *
     * Every change to an input the slice was computed from - settings, engine, printer,
     * model, placement, support paint - goes through here, so the export path can never
     * offer G-code that no longer matches the model on the plate.
     */
    fun withoutPublishedSlice(statusMessage: String = this.statusMessage): MainUiState = copy(
        sliceResultId = null,
        gcodePath = null,
        gcodeComplete = false,
        baseGcodePath = null,
        sliceEngine = null,
        layerPreview = null,
        layerEvents = emptyList(),
        estimatedPrintSeconds = null,
        sliceLogPath = null,
        sliceDurationMilliseconds = null,
        statusMessage = statusMessage,
    )
}
