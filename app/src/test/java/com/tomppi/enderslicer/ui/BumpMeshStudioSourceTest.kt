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
    fun applyInPlaceKeepsTheObjectAndTheSupportPaint() {
        val viewModel = source("com/tomppi/enderslicer/ui/MainViewModel.kt")
        assertTrue(viewModel.contains("textureUndoHistory.push("))
        assertTrue(
            "one helper does the replacement, so a support paint cannot be dropped in passing",
            viewModel.contains("plateObjectWithTextureMesh(stillThere, parsed)"),
        )
        assertTrue(
            "a texture apply never rewrites the user's support paint",
            !viewModel.contains("A new triangle numbering: the old paint cannot name these triangles"),
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
    fun theTextureRegionHasItsOwnBarAndNeverArmsSupportPainting() {
        val viewModel = source("com/tomppi/enderslicer/ui/MainViewModel.kt")
        assertTrue(viewModel.contains("fun bumpMeshArmPaint()"))
        assertTrue(
            "the old support-paint arming is gone",
            !viewModel.contains("setPaintMode(SupportPaintMode.ENFORCER)"),
        )
        assertTrue(
            "the apply mask is the studio's own set, not the model's support paint",
            viewModel.contains("textureRegionMaskOf(studio.paintedTriangles"),
        )
        assertTrue(!viewModel.contains("textureMaskOf(model.supportPaint"))
        assertTrue(viewModel.contains("fun bumpMeshFinishPaint()"))
        assertTrue(viewModel.contains("fun textureRegionAt(hit: MeshPicker.Hit)"))
        assertTrue(viewModel.contains("bumpMeshStudioAfterPaintDone("))

        val viewer = source("com/tomppi/enderslicer/viewer/ModelSurfaceView.kt")
        assertTrue(
            "the region has a tint attribute of its own",
            viewer.contains("aTextureRegionTint"),
        )
        assertTrue(
            "and a colour outside the support palette and the amber face tint",
            viewer.contains("TEXTURE_REGION_COLOR = vec3(0.72, 0.42, 1.0)"),
        )
        assertTrue(viewer.contains("var textureRegionPaintActive: Boolean = false"))
        assertTrue(viewer.contains("var onTextureRegionHit: ((MeshPicker.Hit) -> Unit)? = null"))

        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        assertTrue(app.contains("TextureRegionPaintBar("))
        assertTrue(app.contains("Text(\"Texture region\","))
        assertTrue(
            app.contains("Drag on the model to paint the region; drag beside it to rotate the view."),
        )
        assertTrue(
            app.contains("drag beside it rotates the view; two fingers pan and zoom"),
        )
        val tools = source("com/tomppi/enderslicer/ui/ModelToolsSheet.kt")
        assertTrue(
            tools.contains("drag beside it rotates the view; two fingers pan and zoom"),
        )
        assertTrue(app.contains("triangles painted"))
        assertTrue(app.contains("TEXTURE_REGION_DONE_TAG"))
        assertTrue(app.contains("Text(\"Done\")"))
        assertTrue(app.contains("view.textureRegionPaintActive = state.bumpMeshStudio.painting"))
        assertTrue(app.contains("view.setTextureRegionTint("))
        assertTrue(
            "the studio no longer opens the generic support-paint bar",
            !app.contains("LaunchedEffect(state.bumpMeshStudio.paintArmed)"),
        )
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
