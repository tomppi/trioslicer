package com.tomppi.enderslicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mesh -> STEP as a source contract: the ui half is wired to the engine lane's seam, there is one
 * converter in the app (not a second implementation), both entry points exist, and the result
 * takes the human's SAVE route plus the existing Open-CAD route.
 */
class MeshToStepSourceTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/" + relative),
            File("app/src/main/java/com/tomppi/enderslicer/" + relative),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for " + relative)
        return file.readText()
    }

    @Test
    fun theSheetIsWiredToTheEngineSeamAndNotASecondConverter() {
        val seam = source("ui/MeshToStepEngine.kt")
        assertTrue("the contract's request/result/progress types are the seam", seam.contains("internal data class MeshToStepRequest"))
        assertTrue(seam.contains("internal data class MeshToStepProgress"))
        assertTrue(seam.contains("internal data class MeshToStepResult"))
        assertTrue(seam.contains("internal interface MeshToStepEngine"))
        assertTrue("one factory, one converter", seam.contains("internal object MeshToStepEngines"))
        assertTrue(
            "the factory creates the real adapter, not a fake",
            seam.contains("HostMeshToStepEngine(context.applicationContext)"),
        )
        assertTrue(
            "the ui names the engine lane's class in exactly one adapter",
            seam.contains("private class HostMeshToStepEngine"),
        )
        assertTrue(
            "the adapter imports/names the engine lane's engine",
            seam.contains("com.tomppi.enderslicer.mesh2step.MeshToStepEngine"),
        )
        assertFalse(
            "the temporary ui fake engine is gone; there is one converter",
            seam.contains("UnavailableMeshToStepEngine"),
        )
        val implementations = listOf(
            "ui/MeshToStepEngine.kt",
            "ui/MeshToStepModel.kt",
            "ui/MeshToStepSheet.kt",
            "ui/MainViewModel.kt",
        ).count { source(it).contains(": MeshToStepEngine {") }
        assertTrue("the interface has one implementation point", implementations <= 1)

        val viewModel = source("ui/MainViewModel.kt")
        assertTrue(viewModel.contains("MeshToStepEngines.create("))
        assertTrue(viewModel.contains("meshToStepEngine.convert("))
        assertTrue(viewModel.contains("meshToStepEngine.cancel()"))
        assertTrue(viewModel.contains("meshToStepEngine.close()"))
        assertTrue(viewModel.contains("meshToStepRequestFor("))
        assertTrue(viewModel.contains("meshToStepResultInfo("))
        assertFalse(
            "the ui never runs its own conversion",
            viewModel.contains("OCP") || viewModel.contains("OCCT") && viewModel.contains("MeshToStep"),
        )
    }

    @Test
    fun bothEntryPointsExistAndTheExperimentalRowIsBesideTheOtherEngines() {
        val app = source("ui/EnderSlicerApp.kt")
        assertTrue(app.contains("onMeshToStep = viewModel::openMeshToStep"))
        assertTrue(app.contains("onMeshToStep: () -> Unit"))
        assertTrue(app.contains("title = \"Mesh -> STEP\""))
        assertTrue(app.contains("Convert to STEP (CAD)…"))
        assertTrue(app.contains("MeshToStepSheet("))
        assertTrue(app.contains("meshToStepSavePicker"))
        assertTrue(app.contains("ActivityResultContracts.CreateDocument(\"application/step\")"))
        assertTrue(app.contains("onSaveStep = {"))
        assertTrue(app.contains("meshToStepFileName("))
        assertTrue(app.contains("onOpenAsCad = viewModel::openSavedMeshToStepAsCad"))
        assertTrue(app.contains("Convert to STEP (CAD)…"))
        assertTrue("the plate action is a dropdown item", app.contains("viewModel.openMeshToStep()"))
    }

    @Test
    fun theResultTakesTheSaveRouteAndOpenCadRoute() {
        val viewModel = source("ui/MainViewModel.kt")
        assertTrue(viewModel.contains("fun saveMeshToStep(uri: Uri)"))
        assertTrue(viewModel.contains("fun openSavedMeshToStepAsCad()"))
        assertTrue(viewModel.contains("openCadDocument(Uri.parse(savedUri))"))
        val openAsCad = viewModel
            .substringAfter("fun openSavedMeshToStepAsCad()")
            .substringBefore("private fun updateMeshToStep")
        assertTrue(
            "the sheet closes before the CAD route takes over the plate",
            openAsCad.indexOf("it.closed()") in 0 until openAsCad.indexOf("openCadDocument"),
        )
        assertTrue(
            "the status sentence names where the file went",
            viewModel.contains("\"Saved STEP to \" +"),
        )
        assertTrue(
            "an engine throw becomes the sheet's named failure, never a silent spinner",
            viewModel.contains("Mesh -> STEP failed: "),
        )
        assertTrue(viewModel.contains("state.meshToStep.failed(sentence)"))
    }

    @Test
    fun theTwoRefusalsAreByName() {
        val viewModel = source("ui/MainViewModel.kt")
        assertTrue(viewModel.contains("A Mesh -> STEP conversion is already running"))
        assertTrue(viewModel.contains("meshToStepRefusal("))
        val model = source("ui/MeshToStepModel.kt")
        assertTrue(model.contains("Convert the CAD document first - Mesh -> STEP converts the plate's mesh, not the live document."))
        assertTrue(model.contains("fun meshToStepRefusal("))
    }
}
