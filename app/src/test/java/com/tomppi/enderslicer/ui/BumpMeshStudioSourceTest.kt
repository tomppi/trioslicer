package com.tomppi.enderslicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two routes to BumpMesh, as a source contract.
 *
 * The full WebView page keeps its old menu home and its launch path; the native studio is an
 * additional route beside it. The side rail's Undo row is the user's explicit ask, and it is
 * gated by the texture-undo stack (absent while empty), separate from the placement undo.
 */
class BumpMeshStudioSourceTest {

    private val main = File("src/main/java")

    private fun source(relative: String): String {
        val file = File(main, relative)
        assertTrue("missing " + file.path, file.isFile)
        return file.readText()
    }

    @Test
    fun theFullWebViewRouteKeepsItsMenuHomeAndLaunchPath() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        assertTrue(
            "the full webview row is untouched",
            app.contains("title = \"BumpMesh texturizer\""),
        )
        assertTrue(app.contains("onBumpMesh = ::launchBumpMesh"))
        assertTrue(
            "launchBumpMesh still starts the existing BumpMeshActivity",
            app.contains("BumpMeshActivity::class.java"),
        )
        assertTrue(app.contains("onClick = onBumpMesh,"))
    }

    @Test
    fun theNativeStudioIsAnAdditionalRouteBesideIt() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        assertTrue(
            "a native studio row in the same Experimental card",
            app.contains("title = \"Texture surface\""),
        )
        assertTrue(app.contains("onBumpMeshStudio = viewModel::openBumpMeshStudio"))
        assertTrue(app.contains("onClick = onBumpMeshStudio,"))
        // The plate-side doors: the side rail (next to Model tools) and the plate overflow.
        assertTrue(app.contains("RailButtonLabel(\"Texture surface\")"))
        assertTrue(app.contains("viewModel.openBumpMeshStudio()"))
        // The studio sheet, with the full-web view one press away from inside it.
        assertTrue(app.contains("BumpMeshStudioSheet("))
        assertTrue(app.contains("onOpenWebWorkspace = {"))
    }

    @Test
    fun theRailUndoRowExistsOnlyWhileATextureCanBeTakenBack() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        assertTrue(
            "the row is gated by the stack label",
            app.contains("textureUndoLabel?.let { label ->"),
        )
        assertTrue(app.contains("SESSION_RAIL_TEXTURE_UNDO_TAG"))
        assertTrue(app.contains("onClick = onUndoTexture,"))
        // The row's action is the texture undo, not the transform card's placement undo.
        assertTrue(app.contains("onClick = onUndoTexture,"))
        assertFalse(app.contains("onUndoTexture = viewModel::undoPlacement"))
    }

    @Test
    fun applyInPlaceReplacesTheMeshPushUndoAndClearsThePaint() {
        val viewModel = source("com/tomppi/enderslicer/ui/MainViewModel.kt")
        assertTrue(viewModel.contains("textureUndoHistory.push("))
        assertTrue(viewModel.contains("sourceMesh = parsed,"))
        assertTrue(
            "the paint cannot survive a new triangle numbering",
            viewModel.contains("supportPaint = SupportPaintState("),
        )
        assertTrue(viewModel.contains("bumpMeshStudioOpened("))
        assertTrue(viewModel.contains("MeshTriangleLimits.save(getApplication(), required)"))
        assertTrue(
            "undo restores the whole previous object only when its mesh still matches",
            viewModel.contains("current.sourceMesh !== entry.appliedSourceMesh"),
        )
        assertTrue(viewModel.contains("withModel(entry.objectId) { entry.before }"))
    }

    @Test
    fun theStudioSaysTheMaskIsStickyToTheMeshAndItsTexturesComeFromTheManifest() {
        val sheet = source("com/tomppi/enderslicer/ui/BumpMeshStudioSheet.kt")
        assertTrue(sheet.contains("Paint the region"))
        assertTrue(sheet.contains("Mesh triangle limit"))
        assertTrue(sheet.contains("Open the full BumpMesh workspace"))
        assertTrue(sheet.contains("Raise limit to "))
        val model = source("com/tomppi/enderslicer/ui/BumpMeshStudioModel.kt")
        assertTrue(model.contains("a new mesh (apply, undo, import)"))
        val catalog = source("com/tomppi/enderslicer/ui/BumpMeshTextureCatalog.kt")
        assertTrue(catalog.contains("bumpmesh/library"))
        assertTrue(catalog.contains("manifest.json"))
    }
}
