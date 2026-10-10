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
import com.tomppi.enderslicer.viewer.GizmoOverlay
import com.tomppi.enderslicer.viewer.SnapFacing
import com.tomppi.enderslicer.viewer.SnapFitHalf
import com.tomppi.enderslicer.viewer.SnapFitParameters
import com.tomppi.enderslicer.viewer.SnapFitRung
import com.tomppi.enderslicer.viewer.SnapGhost
import com.tomppi.enderslicer.viewer.SnapJoint
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.Vec3
import com.tomppi.enderslicer.viewer.spanAlong

/**
 * The name one half of a cut gets, after the way its axis reads: a Z cut leaves
 * a lower and an upper part, an X cut a left and a right one, a Y cut a front
 * and a back one.
 */
fun cutHalfName(base: String, axis: ModelPlacement.Axis, high: Boolean): String = when (axis) {
    ModelPlacement.Axis.Z -> if (high) "$base upper" else "$base lower"
    ModelPlacement.Axis.X -> if (high) "$base right" else "$base left"
    ModelPlacement.Axis.Y -> if (high) "$base back" else "$base front"
}

/**
 * What the snap fit says when the pair a split recorded is not the plate it was
 * recorded on: a half was deleted or replaced, another model was split since,
 * or a restored workspace has different objects. That is an ordinary thing for
 * a plate to do, so it is a sentence rather than an exception.
 */
internal const val SNAP_PAIR_STALE_MESSAGE =
    "The plate changed since the split - split the model again and retry"

/**
 * The two halves a split recorded, as the plate has them now.
 *
 * [Stale] is the missing half, the replaced half and the empty plate alike: the
 * caller has nothing to boolean either way, and the one thing it can say is
 * what [Stale.reason] carries.
 */
sealed interface SnapPairLookup {
    data class Found(val lowIndex: Int, val highIndex: Int) : SnapPairLookup

    object Stale : SnapPairLookup {
        val reason: String get() = SNAP_PAIR_STALE_MESSAGE
    }
}

/**
 * [name] without the half-of-a-cut suffix it already carries.
 *
 * Splitting a model twice - a half re-cut on another axis, or a pair split
 * again - otherwise stacks the suffixes: "part lower" cut along X would leave
 * "part lower left" and "part lower right", and cutting one of those along Z
 * would leave "part lower left lower". The suffix is replaced, not added to,
 * and every object name stays one half away from the one it was cut from.
 */
fun cutBaseName(name: String): String {
    for (axis in ModelPlacement.Axis.entries) {
        for (high in listOf(false, true)) {
            val suffix = cutHalfName("", axis, high)
            if (suffix.isNotEmpty() && name.endsWith(suffix)) return name.dropLast(suffix.length)
        }
    }
    return name
}

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
     * True while the Split tool is open: the model view previews the cut.
     *
     * A preview only. Nothing is cut until the user commits, and the preview
     * never touches the mesh - the shader discards the far side of the plane.
     */
    val cutActive: Boolean = false,
    /** Which way the cut runs: Z is a cut from the top, X and Y are side cuts. */
    val cutAxis: ModelPlacement.Axis = ModelPlacement.Axis.Z,
    /** Where the cut plane sits along [cutAxis], in build-plate millimetres. */
    val cutOffsetMm: Double = 0.0,

    /**
     * True while the Snap fit tool is open: the model view places a joint by tap.
     *
     * The two halves and the axis are not asked for, they are what the split
     * left behind: [snapLowHalfId] and [snapHighHalfId] name the two objects the
     * split produced, in the order the cut's own axis reads, and a session ends
     * the moment either of them leaves the plate. Nothing here is a second
     * source of truth about which objects were split.
     */
    val snapActive: Boolean = false,
    /** The half on the low side of the split. */
    val snapLowHalfId: String? = null,
    /** The half on the high side of the split. */
    val snapHighHalfId: String? = null,
    /** The axis the pair was split on, which is the assembly direction. */
    val snapAxis: ModelPlacement.Axis = ModelPlacement.Axis.Z,
    /**
     * Each half's own mating face along [snapAxis], in that half's own mesh
     * coordinates: the plane the split ran, which the split knows exactly and
     * the plate's layout cannot move. Carried per half because a half that
     * already carries a joint no longer ends at its face, so re-measuring it
     * after a joint would put the next one in the wrong place.
     */
    val snapLowFaceMm: Float? = null,
    val snapHighFaceMm: Float? = null,
    /**
     * Every joint placed on the pair, in the order they were tapped.
     *
     * A tap ADDS one of these rather than moving a single joint, because a
     * complicated seam needs several and each one is its own decision: the
     * material varies along a seam, so each joint gets its own rung, its own
     * teeth, its own facing and its own clearance step. The list is the truth;
     * the panel's controls act on [snapSelectedJoint].
     */
    val snapJoints: List<SnapJointSpec> = emptyList(),
    /** Which joint the panel acts on, or -1 when there is none. */
    val snapSelectedJoint: Int = -1,
    /** True while the selected joint is waiting for a tap to move it to. */
    val snapMovingJoint: Boolean = false,
    /**
     * True when the joints are allowed to face different ways. Off by default:
     * every joint on a seam faces the same way unless the user says otherwise,
     * and flipping is then a pair-level decision that moves them all together.
     */
    val snapMixedFacing: Boolean = false,
    /** The way a new joint faces, and the way the pair-level flip leaves every joint. */
    val snapFacing: SnapFacing = SnapFacing.SAME,
    /** How many teeth a new joint gets; the stepper also sets the selected joint's. */
    val snapBarbs: Int = 1,
    /** True when every joint's socket mouth is chamfered: the lead-in in the hole. */
    val snapSocketRamp: Boolean = false,
    /** One control for the whole joint: every dimension and clearance scales with it. */
    val snapScale: Float = 1f,
    /** Which half a newly placed joint asks to carry the beam. */
    val snapBeamHalf: SnapJoint.JointHalf = SnapJoint.JointHalf.LOW,
    /**
     * True when the user asked for the full joint whatever the seam's own
     * cross-section says: the ladder's fit checks are then skipped and the pad
     * is built anyway, as the user is responsible for the result.
     */
    val snapFullJoint: Boolean = false,
    /**
     * The hook dimensions the user typed, in millimetres, or null to take the
     * scale's own share. The scale stays the base; each of these overrides its
     * own dimension and is clamped by the material like any other.
     */
    val snapHookLengthMm: Float? = null,
    val snapHookThicknessMm: Float? = null,
    val snapHookLipMm: Float? = null,
    /**
     * The last previewed result: the two booleaned halves and the joint they
     * carry. Null while nothing has been previewed yet.
     */
    val snapPreview: SnapPreview? = null,
    /** Why the last attempt produced nothing, in the user's terms. */
    val snapFailure: String? = null,

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

    /** How far the selected object reaches along [axis], or null when the plate is empty. */
    fun spanAlong(axis: ModelPlacement.Axis): ClosedFloatingPointRange<Float>? =
        mesh?.bounds?.spanAlong(axis)

    /** How far the selected object reaches along [cutAxis], or null when the plate is empty. */
    val cutSpanMm: ClosedFloatingPointRange<Float>?
        get() = spanAlong(cutAxis)

    /**
     * True when the cut plane is strictly inside the selected object, so both
     * halves would have geometry. A plane on or outside a bound leaves one side
     * empty, which is a preview the user can look at but not a split.
     */
    val cutInsideModel: Boolean
        get() {
            val span = cutSpanMm ?: return false
            return cutOffsetMm > span.start && cutOffsetMm < span.endInclusive
        }

    /**
     * What the two halves of the selected object would be called, low side
     * first: a Z cut is lower/upper, an X cut left/right, a Y cut front/back.
     */
    val cutHalfNames: Pair<String, String>
        get() {
            val base = cutBaseName(selectedModel?.name.orEmpty())
            return cutHalfName(base, cutAxis, high = false) to cutHalfName(base, cutAxis, high = true)
        }

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

    // --- The snap fit -------------------------------------------------------

    /** The half of the split on the low side of the assembly axis, or null when it is gone. */
    val snapLowHalf: PlateObject?
        get() = snapLowHalfId?.let { id -> models.firstOrNull { it.id == id } }

    /** The half of the split on the high side, or null when it is gone. */
    val snapHighHalf: PlateObject?
        get() = snapHighHalfId?.let { id -> models.firstOrNull { it.id == id } }

    /**
     * Where the two halves of the split stand in the plate order, or [SnapPairLookup.Stale]
     * when one of them is no longer on the plate.
     *
     * The places matter as much as the objects: the plate's packer lays the
     * plate out with fresh objects, so the parts a commit has to select and
     * remember afterwards are followed by their index in the plate order, not
     * by identity - which is what a lookup by identity got wrong.
     */
    val snapPairIndices: SnapPairLookup
        get() {
            val lowId = snapLowHalfId ?: return SnapPairLookup.Stale
            val highId = snapHighHalfId ?: return SnapPairLookup.Stale
            val lowIndex = models.indexOfFirst { it.id == lowId }
            val highIndex = models.indexOfFirst { it.id == highId }
            if (lowIndex < 0 || highIndex < 0 || lowIndex == highIndex) return SnapPairLookup.Stale
            return SnapPairLookup.Found(lowIndex, highIndex)
        }

    /** True when both halves of the last split are still on the plate. */
    val snapHalvesPresent: Boolean
        get() = snapLowHalfId != null && snapHighHalfId != null &&
            snapLowHalf != null && snapHighHalf != null && snapLowHalfId != snapHighHalfId

    /**
     * True when the Snap fit tool has both halves to work on: a split has
     * happened and neither half has left the plate since.
     */
    val snapAvailable: Boolean get() = snapHalvesPresent

    /**
     * The low half as the joint builder needs it: placed, with its own mating
     * face carried, or null when it is gone or the plane it was split on was
     * never recorded.
     *
     * Null is not fatal here - the panel says so - but it is the one thing a
     * joint cannot be built without: the face is what every feature is measured
     * from, and the placed mesh on its own would put them wherever the packer
     * left the half.
     */
    val snapLowFitHalf: SnapFitHalf? by lazy {
        val half = snapLowHalf ?: return@lazy null
        val face = snapLowFaceMm ?: return@lazy null
        SnapFitHalf.placed(half.sourceMesh, half.mesh, face)
    }

    /** The high half as the joint builder needs it; see [snapLowFitHalf]. */
    val snapHighFitHalf: SnapFitHalf? by lazy {
        val half = snapHighHalf ?: return@lazy null
        val face = snapHighFaceMm ?: return@lazy null
        SnapFitHalf.placed(half.sourceMesh, half.mesh, face)
    }

    /**
     * The tapped point in the pair's own coordinates - the frame the joint is
     * built in, and the frame each half's mating face is carried in.
     *
     * Read through the placement of the half the tap landed on: the plate has
     * moved the halves apart since the split, and the same plate point is a
     * different spot in each half's own frame. Null before the first tap, and
     * when that half is no longer one of the pair.
     */
    val snapAnchorLocalMm: Vec3? by lazy {
        val tapped = snapAnchorPoint ?: return@lazy null
        val half = when (snapAnchorHalfId) {
            snapLowHalfId -> snapLowFitHalf
            snapHighHalfId -> snapHighFitHalf
            else -> null
        } ?: return@lazy null
        half.toLocal(tapped)
    }

    /** The joint the panel's controls act on, or null when none is selected. */
    val snapSelectedSpec: SnapJointSpec?
        get() = snapJoints.getOrNull(snapSelectedJoint)

    /**
     * The selected joint's tapped point on the plate, or null when there is no
     * joint yet. Kept as a property rather than a field: the joint list is the
     * one source of truth about where the joints are.
     */
    val snapAnchorPoint: Vec3?
        get() = snapSelectedSpec?.anchorPointMm

    /** Which half the selected joint's tap landed on, or null. */
    val snapAnchorHalfId: String?
        get() = snapSelectedSpec?.anchorHalfId

    /** The half a spec's tap is read through, or null when that half has left the plate. */
    fun snapAnchorHalfFor(spec: SnapJointSpec): SnapFitHalf? = when (spec.anchorHalfId) {
        snapLowHalfId -> snapLowFitHalf
        snapHighHalfId -> snapHighFitHalf
        else -> null
    }

    /**
     * The parameters one joint is built with: the hook controls the user has
     * typed, that joint's own clearance step, teeth and facing, and the pair's
     * socket-ramp decision. Nothing else differs between joints on a seam.
     */
    fun snapParametersFor(spec: SnapJointSpec): SnapFitParameters = SnapFitParameters(
        beamLengthOverrideMm = snapHookLengthMm,
        beamThicknessOverrideMm = snapHookThicknessMm,
        lipDepthOverrideMm = snapHookLipMm,
        barbCount = spec.barbs,
        facing = spec.facing,
        socketRamp = snapSocketRamp,
    ).tightenedBy(spec.tightness.stepMm)

    /**
     * The preview while everything that produced it is still the current plate
     * and the current settings, null otherwise.
     *
     * [snapPreview] carries the anchor, the scale and the hook dimensions it was
     * built at, so a slider that has moved on is not previewed with, and Apply
     * cannot stage geometry the user did not ask for. It also carries the two
     * halves and their placements, because those ARE the geometry: a half
     * rotated under a standing preview used to leave the preview current, so the
     * model view snapped back to the un-rotated half and Apply committed it.
     *
     * Requiring both halves here is also what keeps the panel honest about a
     * pair that is gone: no halves, no preview, so Join is disabled for the same
     * reason [snapBlockedReason] gives rather than doing nothing when tapped.
     */
    val snapShownMeshes: SnapPreview? by lazy {
        val preview = snapPreview ?: return@lazy null
        val low = snapLowHalf
        val high = snapHighHalf
        preview.takeIf {
            low != null && high != null &&
                it.lowHalfId == low.id && it.highHalfId == high.id &&
                it.lowPlacement == low.placement && it.highPlacement == high.placement &&
                it.specs == snapJoints && it.scale == snapScale && it.fullJoint == snapFullJoint &&
                it.socketRamp == snapSocketRamp && it.hookLengthMm == snapHookLengthMm &&
                it.hookThicknessMm == snapHookThicknessMm && it.hookLipMm == snapHookLipMm
        }
    }

    /**
     * The joint drawn where it will go, or null when the plate already shows it.
     *
     * While the preview stands there is nothing to ghost - the previewed halves
     * *are* the jointed geometry - and it also goes when the settings have moved
     * on enough that the joint can no longer be built at all, because then there
     * is nothing to put in.
     */
    val snapGhost: GizmoOverlay? by lazy {
        if (!snapActive || snapShownMeshes != null) return@lazy null
        val low = snapLowFitHalf ?: return@lazy null
        val high = snapHighFitHalf ?: return@lazy null
        val joints = snapJoints.mapNotNull { spec ->
            val anchor = snapAnchorHalfFor(spec)?.toLocal(spec.anchorPointMm) ?: return@mapNotNull null
            when (
                val built = SnapJoint.build(
                    axis = snapAxis,
                    anchorMm = anchor,
                    scale = snapScale,
                    lowHalf = low,
                    highHalf = high,
                    beamHalf = spec.beamHalf,
                    parameters = snapParametersFor(spec),
                    requested = if (snapFullJoint) SnapFitRung.FULL else null,
                )
            ) {
                is SnapJoint.Either.Placed -> built.placement.joint
                is SnapJoint.Either.Flipped -> built.placement.joint
                is SnapJoint.Either.Failed -> null
            }
        }
        if (joints.isEmpty()) return@lazy null
        SnapGhost.overlay(joints)
    }

    /**
     * Why Apply cannot run yet, or null when it can.
     *
     * A disabled primary action always says what is missing, so this is the same
     * sentence the plate shows rather than a second one invented for the panel.
     */
    val snapBlockedReason: String?
        get() = when {
            snapLowHalfId == null || snapHighHalfId == null ->
                "Split a model first: the snap fit joins the two halves a split made."
            // A pair was recorded and the plate no longer holds it: the halves
            // were deleted, replaced by another split, or restored away.
            !snapHalvesPresent -> SNAP_PAIR_STALE_MESSAGE
            snapLowFaceMm == null || snapHighFaceMm == null ->
                "The plane this pair was split on is not known; split the model again."
            snapJoints.isEmpty() -> "Tap the model to place a joint on the seam."
            else -> null
        }

    /**
     * The split pair a descriptor recorded or a commit just made, as state.
     *
     * One place both paths put the pair, so the tool is offered again on exactly the same
     * terms after a relaunch as it is right after the split: a null id - an older descriptor
     * - is no pair at all, which is what "Split the model first" means.
     */
    fun withSnapPair(
        lowHalfId: String?,
        highHalfId: String?,
        axis: ModelPlacement.Axis,
        lowFaceMm: Float?,
        highFaceMm: Float?,
    ): MainUiState = copy(
        snapLowHalfId = lowHalfId,
        snapHighHalfId = highHalfId,
        snapAxis = axis,
        snapLowFaceMm = lowFaceMm,
        snapHighFaceMm = highFaceMm,
    )

    /**
     * The plate has been replaced around new objects: the placement undo belonged to the
     * objects that just left, and a previewed half is geometry for one of them.
     *
     * Every path that replaces the plate - a split, a joint applied, an arrangement - goes
     * through this, so no path can forget one of the two. The view model clears the history
     * itself; the flags here are what the Undo control reads.
     */
    fun afterPlateReplaced(): MainUiState = withoutSnap().copy(
        canUndoPlacement = false,
        undoPlacementLabel = null,
    )

    /**
     * The snap session, gone: the tool closed, the preview dropped and the
     * failure forgotten.
     *
     * The pair a split recorded stays, so [snapAvailable] still answers for it
     * and a cancelled session can be opened again without splitting twice.
     * Called from every path that replaces or drops a half, because a preview
     * is geometry for objects that may no longer exist.
     */
    fun withoutSnap(): MainUiState = copy(
        snapActive = false,
        snapJoints = emptyList(),
        snapSelectedJoint = -1,
        snapMovingJoint = false,
        snapPreview = null,
        snapFailure = null,
    )

    /**
     * The same session with one joint taken off the seam.
     *
     * The selection follows the list: the joint that takes the removed one's
     * place is selected when the selected one goes, and the rest of the list is
     * untouched - one joint's removal is not the others' business.
     */
    fun withoutSnapJoint(index: Int): MainUiState {
        if (index !in snapJoints.indices) return this
        val joints = snapJoints.toMutableList().also { it.removeAt(index) }
        val selected = when {
            joints.isEmpty() -> -1
            snapSelectedJoint == index -> index.coerceAtMost(joints.size - 1)
            snapSelectedJoint > index -> snapSelectedJoint - 1
            else -> snapSelectedJoint
        }
        return copy(
            snapJoints = joints,
            snapSelectedJoint = selected,
            snapMovingJoint = false,
            snapPreview = null,
            snapFailure = null,
        )
    }

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
