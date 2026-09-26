package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.annotation.AnnotationAnchor
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.engine.LayerEvent
import com.tomppi.enderslicer.model.CuraMachineCatalog
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.OrcaSliceSettings
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
    val mesh: StlMesh? = null,
    val modelPath: String? = null,
    val modelPlacement: ModelPlacement? = null,
    /** True while there is a placement change to take back. */
    val canUndoPlacement: Boolean = false,
    /** What undoing would take back, for the button's own label. */
    val undoPlacementLabel: String? = null,
    val supportPaint: SupportPaintState = SupportPaintState(),
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
