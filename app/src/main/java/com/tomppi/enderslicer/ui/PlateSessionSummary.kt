package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings

/**
 * The values the Plate screen's "Print session" and "Quick settings" cards show.
 *
 * They belong to the *active* engine: an OrcaSlicer slice reads OrcaSliceSettings, a PrusaSlicer
 * slice PrusaSliceSettings and Cura the app's own SlicerSettings. The cards used to ask only
 * "is this Prusa?", so an Orca slice showed the Cura numbers beside G-code it had not produced.
 */
internal data class PlateSessionSummary(
    val layerHeightMm: Double,
    val infillPercent: Double,
    val infillPattern: String,
    val supportsEnabled: Boolean,
    /** The pattern or placement under the supports switch, in the engine's own vocabulary. */
    val supportsDetail: String,
    /** How the first layer sticks: a brim when one is set, otherwise the skirt. */
    val adhesion: String,
)

internal fun plateSessionSummary(engine: SlicerEngine, state: MainUiState): PlateSessionSummary =
    when (engine) {
        SlicerEngine.PRUSA -> state.prusaSettings.toSummary()
        SlicerEngine.ORCA -> state.orcaSettings.toSummary()
        SlicerEngine.CURA -> state.settings.toSummary()
    }

private fun PrusaSliceSettings.toSummary() = PlateSessionSummary(
    layerHeightMm = layerHeightMm,
    infillPercent = fillDensityPercent,
    infillPattern = infillPatternLabel(fillPattern),
    supportsEnabled = supportMaterial,
    supportsDetail = infillPatternLabel(supportPattern),
    adhesion = adhesionLabel(brimWidthMm, skirtLoops),
)

private fun OrcaSliceSettings.toSummary() = PlateSessionSummary(
    layerHeightMm = layerHeightMm,
    infillPercent = sparseInfillDensityPercent,
    infillPattern = infillPatternLabel(sparseInfillPattern),
    supportsEnabled = supportEnabled,
    supportsDetail = infillPatternLabel(supportBasePattern),
    adhesion = adhesionLabel(brimWidthMm, skirtLoops),
)

private fun SlicerSettings.toSummary() = PlateSessionSummary(
    layerHeightMm = layerHeightMm,
    infillPercent = infillDensityPercent,
    infillPattern = infillPatternLabel(infillPattern),
    supportsEnabled = supportsEnabled,
    supportsDetail = supportPlacement,
    adhesion = adhesionType,
)

/** "Brim 5.0 mm" when a brim is set, "Skirt 1x" otherwise. */
internal fun adhesionLabel(brimWidthMm: Double, skirtLoops: Int): String =
    if (brimWidthMm > 0.0) "Brim %.1f mm".format(brimWidthMm) else "Skirt " + skirtLoops + "x"
