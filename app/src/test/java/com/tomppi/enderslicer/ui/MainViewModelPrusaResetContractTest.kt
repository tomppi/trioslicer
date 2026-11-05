package com.tomppi.enderslicer.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Reset to engine defaults" promises to drop the preset selection and discard the
 * imported snapshot - the PrusaSettingsSheet dialog and the EngineSnapshotCard
 * contract both say so. The sheet carries the two callbacks; these pins hold the
 * view-model routing they are wired to and the call site that connects them: the
 * preset reset goes through the update path (which persists and clears the
 * pickers), and the discard clears the baseline, the templates and the stored copy.
 */
class MainViewModelPrusaResetContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for $relative")
        return file.readText()
    }

    @Test
    fun thePresetResetGoesThroughThePresetUpdatePath() {
        val viewModel = source("ui/MainViewModel.kt")

        assertTrue(
            "the reset reuses the update path, which persists and clears the pickers",
            viewModel.contains("fun resetPrusaPresetSelection() = updatePrusaPreset { PrusaPresetSelection.NONE }"),
        )
    }

    @Test
    fun theSnapshotDiscardClearsTheBaselineTheTemplatesAndTheStoredCopy() {
        val body = source("ui/MainViewModel.kt")
            .substringAfter("fun discardPrusaSnapshot()")
            .substringBefore("\n    fun ")

        assertTrue(body.contains("importedPrusaBaseline = null"))
        assertTrue(body.contains("prusaSnapshot = null"))
        assertTrue(body.contains("clearPrusaSnapshotBaseline()"))
        assertTrue("the templates are cleared on disk too", body.contains("savePrusaGcode(\"\", \"\")"))
    }

    @Test
    fun theSheetCallSiteWiresBothCallbacks() {
        val app = source("ui/EnderSlicerApp.kt")

        assertTrue(app.contains("onResetPresets = viewModel::resetPrusaPresetSelection"))
        assertTrue(app.contains("onDiscardSnapshot = viewModel::discardPrusaSnapshot"))
    }
}
