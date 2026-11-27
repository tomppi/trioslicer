package com.tomppi.enderslicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The splat viewer, as source contracts.
 *
 * The renderer is a vendored web package (Aholo, MIT) and the page is an offline asset; the
 * thing a unit test can hold is that the app ships both, that the activity only serves files
 * from its own folder, and that More has a door to the library.
 */
class SplatViewerSourceTest {

    private fun file(path: String): File {
        val candidates = listOf(File(path), File("app/$path"))
        return candidates.firstOrNull(File::isFile) ?: error("missing " + path)
    }

    @Test
    fun theViewerAssetsAreInTheApk() {
        val index = file("src/main/assets/splatviewer/index.html").readText()
        val js = file("src/main/assets/splatviewer/viewer.js").readText()
        val aholo = file("src/main/assets/splatviewer/aholo/index.js")
        assertTrue("the page loads the module", index.contains("viewer.js"))
        assertTrue("the module imports the renderer", js.contains("./aholo/index.js"))
        assertTrue("SOG scenes are loaded", js.contains("SplatFileType.SOG"))
        assertTrue("the settings sheet has a trigger", index.contains("id=") && index.contains("settings"))
        assertTrue("the settings sheet title names it", index.contains("Viewer settings"))
        assertTrue("PLY scenes are loaded", js.contains("SplatFileType.PLY"))
        assertTrue(
            "a SOG's bounds come from its meta.json, so the camera can frame it",
            js.contains("boundsOfSog") && js.contains("DecompressionStream"),
        )
        assertTrue("the render pipeline settings are exposed", js.contains("applyRenderSettings"))
        assertTrue(
            "the tone curve choice reaches the renderer",
            js.contains("ToneMapping") && js.contains("toneMapping"),
        )
        assertTrue("orbit, pan and zoom share one control class", js.contains("class OrbitRig"))
        assertTrue(
            "camera orbit is unlocked 360 degrees with two-finger twist roll",
            js.contains("qSlerp") && js.contains("roll(dAngle)") && !js.contains("-1.45, 1.45"),
        )
        assertTrue(
            "a change asks for redraws and the first frames are warmed up",
            js.contains("requestRedraw") && js.contains("warmupFrames"),
        )
        assertTrue("the renderer's MIT licence ships with it", file("src/main/assets/splatviewer/aholo/LICENSE").length() > 500)
        assertTrue("the vendored renderer is present", aholo.length() > 1_000_000)
    }

    @Test
    fun theActivityServesOnlyItsOwnFolder() {
        val activity = file("src/main/java/com/tomppi/enderslicer/splat/SplatViewerActivity.kt").readText()
        assertTrue("the model is fetched under /splats/", activity.contains("\"/splats/\""))
        assertTrue(
            "a file outside the folder is refused",
            activity.contains("startsWith(directory.path + File.separator)"),
        )
        assertTrue("the page is the offline asset", activity.contains("/assets/splatviewer/index.html"))
        assertTrue("the asset loader origin is the page's", activity.contains("WebViewAssetLoader"))
        assertTrue(
            "local assets are not left to the WebView cache",
            activity.contains("LOAD_NO_CACHE"),
        )
    }

    @Test
    fun theAppShipsTheActivityAndADoorToIt() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("\".splat.SplatViewerActivity\""))
        assertTrue(
            "hardware acceleration is what gives the page a WebGL context",
            manifest.contains("android:name=\".splat.SplatViewerActivity\"") &&
                manifest.contains("android:hardwareAccelerated=\"true\""),
        )
        val app = file("src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt").readText()
        assertTrue("the menu entry is Splat/PLY files", app.contains("title = " + '"' + "Splat/PLY files" + '"'))
        assertTrue(app.contains("onClick = onGaussianSplatting"))
        assertTrue(app.contains("GaussianSplattingSheet("))
        val sheet = file("src/main/java/com/tomppi/enderslicer/ui/GaussianSplattingSheet.kt").readText()
        assertTrue("the viewer is its door", sheet.contains("title = " + '"' + "Splat viewer" + '"'))
        assertTrue(sheet.contains("onClick = onOpenViewer"))
        assertTrue("New scan is removed from the Splat/PLY files menu", !sheet.contains("title = " + '"' + "New scan" + '"'))
        assertTrue(app.contains("SplatLibrarySheet("))
        val library = file("src/main/java/com/tomppi/enderslicer/splat/SplatLibrary.kt").readText()
        assertTrue("imports land in app storage", library.contains("File(context.filesDir, \"splats\")"))
        assertTrue("the MIT renderer is named in the UI", file("src/main/java/com/tomppi/enderslicer/ui/SplatLibrarySheet.kt").readText().contains("Aholo"))
        assertTrue(
            "an imported splat can be deleted, and only after confirmation",
            library.contains("fun delete(") &&
                file("src/main/java/com/tomppi/enderslicer/ui/SplatLibrarySheet.kt").readText()
                    .contains("Delete this splat?"),
        )
    }
}
