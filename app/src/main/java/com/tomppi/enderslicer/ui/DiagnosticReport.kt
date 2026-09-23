package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.SlicerEngine

/**
 * The text of the diagnostic export: the setup the last slice ran with, and the
 * engine's own log for it.
 *
 * The engines' failure messages tell the user to export this, so it has to stand
 * on its own - what the app is, what it was asked to slice with, and what the
 * engine said - without the reader having to ask a second question.
 */
internal fun diagnosticReport(
    state: MainUiState,
    engine: SlicerEngine,
    appVersion: String,
    device: String,
    now: String,
    logText: String?,
): String = buildString {
    appendLine("TrioSlicer diagnostic report")
    appendLine("Generated: " + now)
    appendLine("App: " + appVersion)
    appendLine("Device: " + device)
    appendLine()
    appendLine("Status: " + state.statusMessage)
    appendLine("Engine: " + engine.label + " selected; last slice ran on " + (state.sliceEngine?.label ?: "no engine"))
    appendLine("Profile: " + state.profileName + " [" + state.profileSource + "]")
    appendLine(
        "Machine: " + state.printer.name + ", " + state.printer.widthMm + " x " +
            state.printer.depthMm + " x " + state.printer.heightMm + " mm, " +
            state.printer.nozzleSizeMm + " mm nozzle, " + state.printer.filamentDiameterMm +
            " mm filament, " + state.printer.gcodeFlavor,
    )
    appendLine(
        "Settings: layer " + state.settings.layerHeightMm + " mm (first " +
            state.settings.initialLayerHeightMm + " mm), line " + state.settings.lineWidthMm +
            " mm, walls " + state.settings.wallLineCount + ", infill " +
            state.settings.infillDensityPercent + "% " + state.settings.infillPattern,
    )
    appendLine(
        "Overrides: " + state.extraCuraSettings.size + " Cura, " +
            state.extraPrusaSettings.size + " PrusaSlicer, " +
            state.extraOrcaSettings.size + " OrcaSlicer",
    )
    appendLine("Model: " + (state.modelPath ?: "none"))
    appendLine(
        "Slice: " + (state.sliceDurationMilliseconds?.let { it.toString() + " ms" } ?: "never ran") +
            (state.estimatedPrintSeconds?.let { ", estimated " + (it / 60) + " min" } ?: "") +
            (if (state.hasCurrentGcode()) ", exportable" else ", no exportable G-code"),
    )
    if (state.warnings.isNotEmpty()) {
        appendLine()
        appendLine("Warnings (" + state.warnings.size + "):")
        state.warnings.forEach { appendLine("- " + it) }
    }
    appendLine()
    appendLine("--- " + engine.label + " log ---")
    append(logText ?: "The engine's log is no longer on disk.")
    if (logText != null && !logText.endsWith("\n")) appendLine()
}
