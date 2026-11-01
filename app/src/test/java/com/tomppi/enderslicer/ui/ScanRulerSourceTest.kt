package com.tomppi.enderslicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ScanRuler port as a source contract: the vendored page is served from the APK at the
 * app's own origin, the page's file chooser and Blob downloads reach the system picker and the
 * SAF stream the human chose, and the More row is the one way in. The metrology engine itself
 * is the vendored page; this test pins the host around it.
 */
class ScanRulerSourceTest {

    private fun source(relative: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("the app module was not found from " + File("").absolutePath)
        return File(root, relative).readText()
    }

    private fun activity(): String = source(
        "app/src/main/java/com/tomppi/enderslicer/scanruler/ScanRulerActivity.kt",
    )

    private fun bridge(): String = source(
        "app/src/main/java/com/tomppi/enderslicer/scanruler/ScanRulerBridgeScript.kt",
    )

    private fun app(): String = source("app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt")

    private fun manifest(): String = source("app/src/main/AndroidManifest.xml")

    @Test
    fun thePageIsTheAppsOwnBundledOrigin() {
        val text = activity()
        assertTrue(text.contains("WebViewAssetLoader.Builder()"))
        assertTrue(text.contains("addPathHandler(\"/assets/\""))
        assertTrue(text.contains("WebViewAssetLoader.AssetsPathHandler(this)"))
        assertTrue(text.contains("/assets/scanruler/index.html"))
        assertTrue(text.contains("loadUrl(SCANRULER_URL)"))
        assertTrue(text.contains("settings.javaScriptEnabled = true"))
        assertTrue(text.contains("settings.domStorageEnabled = true"))
        assertTrue(text.contains("settings.allowFileAccess = false"))
        assertTrue(text.contains("settings.allowContentAccess = false"))
        assertTrue(text.contains("settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW"))
        assertTrue("wasm must be named or instantiateStreaming rejects it", text.contains("scanRulerMimeFor"))
        assertTrue(text.contains("application/wasm"))
        assertTrue("the asset handler is not bypassed", text.contains("AssetsPathHandler(this)"))
        assertTrue(text.contains("WebViewAssetLoader.PathHandler"))
        val manifest = manifest()
        assertTrue(manifest.contains(".scanruler.ScanRulerActivity"))
        assertTrue(manifest.contains("android:hardwareAccelerated=\"true\""))
    }

    @Test
    fun nothingOutsideTheBundledOriginIsLoaded() {
        val text = activity()
        assertTrue(text.contains("shouldInterceptRequest"))
        assertTrue(text.contains("isRemoteHttp(request.url)"))
        assertTrue(text.contains("WebResourceResponse("))
        assertTrue(text.contains("403"))
        assertTrue(text.contains("shouldOverrideUrlLoading"))
        assertTrue("only about/blob/data stay inside", text.contains("setOf(\"about\", \"blob\", \"data\")"))
    }

    @Test
    fun thePagesFilePickerOpensTheSystemPickerIncludingMultiple() {
        val text = activity()
        assertTrue(text.contains("override fun onShowFileChooser"))
        assertTrue(text.contains("ActivityResultContracts.OpenMultipleDocuments()"))
        assertTrue(text.contains("ActivityResultContracts.OpenDocument()"))
        assertTrue(text.contains("MODE_OPEN_MULTIPLE"))
        assertTrue(text.contains("deliverFileChooser"))
        assertTrue(text.contains("onReceiveValue"))
    }

    @Test
    fun blobDownloadsAreStreamedIntoTheChosenSafDocument() {
        val text = activity()
        val script = bridge()
        assertTrue(text.contains("ActivityResultContracts.CreateDocument(\"application/octet-stream\")"))
        assertTrue(text.contains("addJavascriptInterface(bridge, JS_BRIDGE_NAME)"))

        assertTrue(text.contains("Base64.decode"))
        assertTrue(text.contains("DocumentsContract.deleteDocument"))
        assertTrue(text.contains("MAX_DOWNLOAD_BYTES"))
        assertTrue(text.contains("beginDownload"))
        assertTrue(text.contains("appendDownloadChunk"))
        assertTrue(text.contains("finishDownload"))
        assertTrue(text.contains("__scanRulerBridgeReady"))
        assertTrue(script.contains("a[download]"))
        assertTrue(script.contains("blob:"))
        assertTrue(script.contains("beginDownload("))
        assertTrue(script.contains("appendDownloadChunk("))
        assertTrue(script.contains("finishDownload("))
        assertTrue(script.contains("__scanRulerBridgeResult"))
        assertTrue("the bytes are tracked even if the URL is revoked", script.contains("createObjectURL"))
        assertTrue(
            "the excluded fridge-bracket sample control is hidden and refused",
            script.contains("hideSampleControl") &&
                script.contains("SAMPLE_LABEL") &&
                script.contains("not shipped in this offline build"),
        )
        assertTrue("a bounded chunk size", script.contains("CHUNK_BYTES = 192 * 1024"))
        assertTrue(text.contains("inject after page load") || text.contains("SCANRULER_BRIDGE_SCRIPT"))
    }

    @Test
    fun loadingErrorsAndBackBehave() {
        val text = activity()
        assertTrue(text.contains("Loading ScanRuler"))
        assertTrue(text.contains("Could not load ScanRuler"))
        assertTrue(text.contains("taking too long to load"))
        assertTrue(text.contains("PAGE_READY_PROBE"))
        assertTrue(text.contains("Retry"))
        assertTrue(text.contains("Close"))
        assertTrue(text.contains("onBackPressedDispatcher.addCallback"))
        assertTrue(text.contains("webView.canGoBack()"))
        assertTrue(text.contains("webView.goBack()"))
        assertTrue(text.contains("removeJavascriptInterface"))
        assertTrue(text.contains("about:blank"))
    }

    @Test
    fun theMoreRowIsTheOneWayInAndThereIsNoPlateHandoff() {
        val text = app()
        assertTrue(text.contains("title = \"ScanRuler (CNC Kitchen)\""))
        assertTrue(text.contains("badge = \"EXP\""))
        assertTrue(text.contains("measure, deviation, wall thickness"))
        assertTrue(text.contains("reverse-engineering to STEP"))
        assertTrue(text.contains("files never") && text.contains("leave it"))
        assertTrue(text.contains("onScanRuler = ::launchScanRuler"))
        assertTrue(text.contains("fun launchScanRuler()"))
        assertTrue(text.contains("ScanRulerActivity::class.java"))
        val launch = text.substringAfter("fun launchScanRuler()").substringBefore("fun launchBumpMesh()")
        assertFalse("no model, no plate, no extras in this release", launch.contains("putExtra"))
        assertFalse(launch.contains("EXTRA_MODEL"))
        // The engine lane excludes samples; the host must not depend on one either.
        assertFalse(activity().contains("samples/"))
        assertFalse(app().contains("fridgeBracket"))
    }
}
