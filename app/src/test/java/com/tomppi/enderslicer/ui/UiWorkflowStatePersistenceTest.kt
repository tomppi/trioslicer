package com.tomppi.enderslicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiWorkflowStatePersistenceTest {
    @Test
    fun primaryViewerAndSheetStateUsesSaveableIdentity() {
        val source = source("ui/EnderSlicerApp.kt")

        assertTrue(source.contains("var viewerMode by rememberSaveable"))
        assertTrue(source.contains("var selectedLayerIndex by rememberSaveable"))
        assertTrue(source.contains("var lastAutoSelectedResultId by rememberSaveable"))
        assertTrue(
            source.contains(
                "LaunchedEffect(state.sliceResultId, state.layerPreview, nonPlanarSettings, conicalSettings)",
            ),
        )
        assertTrue(source.contains("lastAutoSelectedResultId != resultId"))
        assertTrue(source.contains("state.hasCurrentGcode()"))
    }

    @Test
    fun userEnteredSheetDraftsUseSaveableState() {
        val model = source("ui/ModelToolsSheet.kt")
        val events = source("ui/LayerEventsSheet.kt")
        val meshLimit = source("ui/MeshTriangleLimitSheet.kt")
        val machine = source("ui/MachineSettingsSheet.kt")
        val sharedFields = source("ui/SettingsFields.kt")
        val octoPrint = source("ui/HardenedOctoPrintSheet.kt")

        assertTrue(model.contains("rememberSaveable(placement)"))
        assertFalse(model.contains("LaunchedEffect(placement)"))
        assertTrue(events.contains("rememberSaveable(layer.number, type)"))
        assertTrue(meshLimit.contains("var valueText by rememberSaveable(currentLimit)"))
        assertTrue(sharedFields.contains("var text by rememberSaveable"))
        assertFalse(sharedFields.contains("rememberSaveable(value)"))
        assertTrue(sharedFields.contains("onFocusChanged"))
        assertTrue(machine.contains("StringField"))
        assertTrue(octoPrint.contains("var baseUrl by rememberSaveable(state.config.baseUrl)"))
        assertTrue(octoPrint.contains("var command by rememberSaveable"))
    }

    @Test
    fun immutableSliceIdentityIsPublishedAndClearedWithDerivedState() {
        val source = source("ui/MainViewModel.kt")

        assertTrue(source.contains("sliceResultId = result.artifactId"))
        assertTrue(source.contains("sliceResultId = null"))
        assertTrue(source.contains("Restored "))
        assertTrue(source.contains("slice again to create validated G-code"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
