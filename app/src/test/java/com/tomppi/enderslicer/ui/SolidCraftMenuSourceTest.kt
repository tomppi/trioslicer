package com.tomppi.enderslicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wiring of 1.9.7's three surfaces, pinned where a unit test can see it.
 *
 * The menu, the plate's two buttons and the files destination are composables over a ViewModel
 * that needs a device; their *rules* live in [com.tomppi.enderslicer.solidcraft.CadSliceGate],
 * [com.tomppi.enderslicer.solidcraft.CadPartPublish] and [com.tomppi.enderslicer.solidcraft.CadConvert]
 * and are tested there. What this test holds is the part no pure object can: that the plate reads
 * the gate from the state, that the menu offers every item the user asked for, and that nothing
 * but the human's own press converts.
 */
class SolidCraftMenuSourceTest {

    private fun source(relative: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("the app module was not found from " + File("").absolutePath)
        return File(root, relative).readText()
    }

    private fun app(): String = source("app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt")

    private fun viewModel(): String =
        source("app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt")

    private fun state(): String = source("app/src/main/java/com/tomppi/enderslicer/ui/MainUiState.kt")

    @Test
    fun theSolidCraftMenuOffersTheDesignsTheExportsAndTheConvertPress() {
        val text = app()
        assertTrue(text.contains("label = \"SolidCraft\""))
        for (item in listOf(
            "New design",
            "Open STEP/IGES/3MF/OBJ/glTF…",
            "SolidCraft files",
            "Save design",
            "Export STEP",
            "Export 3MF",
            "Export STL",
            "Convert to STL",
        )) {
            assertTrue("the SolidCraft menu must offer " + item, text.contains(item))
        }
        assertTrue("Convert again is the same item after a conversion", text.contains("state.cadConvertLabel"))
        assertTrue(text.contains("SolidCraftEngine.isReady()"))
        assertTrue(text.contains("solidCraftEngineLine"))
        assertTrue(text.contains("viewModel.connectCadAgent()"))
        assertTrue(text.contains("viewModel.stopCadAgent()"))
        // The old home of these items is gone: one menu, not two.
        assertFalse(text.contains("Plate CAD agent (SolidCraft)"))
    }

    @Test
    fun theFilesScreenIsADestinationWithItsOwnBack() {
        val text = app()
        assertTrue(text.contains("SolidCraftFilesScreen("))
        // Delete refuses the design only while it is the plate's LIVE document: after the human
        // converted it, the plate holds its STLs and the design file can go.
        assertTrue(text.contains("takeIf { !it.converted }?.designPath"))
        assertTrue(text.contains("BackHandler(enabled = solidCraftFilesOpen)"))
        assertTrue(text.contains("viewModel.loadCadDesign(entry.designFile.absolutePath)"))
        assertTrue(text.contains("solidCraftFilesOpen = true"))
    }

    @Test
    fun thePlateReadsTheSliceGateFromTheStateNotFromItsOwnComposableState() {
        val text = app()
        assertTrue(text.contains("state.cadSliceBlockedReason"))
        assertTrue(text.contains("sliceTappable = state.cadNeedsConversion"))
        assertTrue(text.contains("cadConvertLabel = state.cadConvertLabel"))
        assertTrue(text.contains("onConvertCad = viewModel::convertCadToStl"))
        // The greyed-but-tappable Slice is the live document only.
        assertTrue(state().contains("val cadSliceBlockedReason"))
        assertTrue(state().contains("val cadNeedsConversion"))
        assertTrue(state().contains("val cadConvertLabel"))
    }

    @Test
    fun sliceRefusesALiveDocumentByNameAndNeverConverts() {
        val text = viewModel()
        val sliceBody = text.substringAfter("fun sliceModel()").substringBefore("fun ")
        assertTrue(sliceBody.contains("CadSliceGate.reasonFor"))
        assertTrue(sliceBody.contains("sliceModelCore()"))
        assertFalse("Slice must never convert on its own", sliceBody.contains("convertCadToStl"))
        // The old automatic export-to-slice handoff is gone for good.
        assertFalse(text.contains("prepareCadForSlice"))
        assertFalse(text.contains("CadSliceHandoff"))
        val gate = source("app/src/main/java/com/tomppi/enderslicer/solidcraft/CadSliceGate.kt")
        assertTrue(gate.contains("Slice needs an STL - convert the CAD document first"))
    }

    @Test
    fun theConvertPressIsTheOnlyCallerOfTheConversion() {
        val text = viewModel()
        // The menu and both plate buttons call it; nothing in the repaint or slice paths does.
        val repaint = text.substringAfter("private suspend fun repaintCad(").substringBefore("private fun markCadStale")
        assertFalse(repaint.contains("convertCadToStl"))
        assertTrue(app().contains("viewModel.convertCadToStl"))
    }
}
