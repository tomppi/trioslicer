package com.tomppi.enderslicer.nativebridge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-contract checks for the Blender MCP engine wiring (see BLENDER_MCP_INTEGRATION.md). */
class BlenderMcpContractTest {
    @Test
    fun applicationBootsEngineAndViewModelRegistersHandoff() {
        val app = source("EnderSlicerApplication.kt")
        val viewModel = source("ui/MainViewModel.kt")
        assertTrue(app.contains("BlenderEngine.ensureStarted(this)"))
        assertTrue(viewModel.contains("BlenderEngine.onStlExported = { file -> importBlenderStl(file) }"))
        assertTrue(viewModel.contains("fun importBlenderStl(file: File)"))
        assertTrue(viewModel.contains("override fun onCleared()"))
        assertTrue(viewModel.contains("BlenderEngine.shutdown()"))
    }

    @Test
    fun engineOwnsLazyAbiSafeLoadAndExportWatch() {
        val bridge = source("nativebridge/BlenderBridge.kt")
        val engine = source("nativebridge/BlenderEngine.kt")
        assertTrue(bridge.contains("ensureLoaded"))
        assertTrue(bridge.contains("UnsatisfiedLinkError"))
        assertTrue(bridge.contains("nativeBlenderStart"))
        assertTrue(engine.contains("FileObserver"))
        assertTrue(engine.contains("CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE"))
        assertTrue(engine.contains("DEFAULT_MCP_PORT = 9876"))
        assertTrue(engine.contains("3.6/config/datafiles"))
        assertTrue(engine.contains("startup/start_blender_mcp.py"))
        assertTrue(engine.contains(".resources-version"))
    }

    @Test
    fun sharedExtractorIsUsedByBothEngines() {
        val prusa = source("engine/PrusaEngineRunner.kt")
        val extractor = source("engine/AssetTreeExtractor.kt")
        assertTrue(extractor.contains("fun copyTree"))
        assertTrue(prusa.contains("AssetTreeExtractor.copyTree"))
    }

    @Test
    fun sdlJavaGlueIsPackagedForJniOnLoad() {
        // libSDL2.so's JNI_OnLoad fails hard if the org.libsdl.app classes are
        // missing from the APK dex: FindClass throws, CheckJNI aborts the
        // process (instant SIGABRT at app start -- seen on device 2026-09-09).
        // Every class the native JNI_OnLoad resolves must be packaged.
        val glueDir = listOf(
            File("app/src/main/java/org/libsdl/app"),
            File("src/main/java/org/libsdl/app"),
        ).firstOrNull { it.isDirectory } ?: error("Missing SDL glue directory")
        val files = glueDir.listFiles { f -> f.isFile && f.name.endsWith(".java") }?.map { it.name }
            ?: error("SDL glue directory is empty")
        for (sourceFile in listOf(
            "SDLActivity.java", "SDLSurface.java", "SDLAudioManager.java",
            "SDLControllerManager.java",
        )) {
            assertTrue("Missing SDL glue source: " + sourceFile, files.contains(sourceFile))
        }
        val activity = File(glueDir, "SDLActivity.java").readText()
        // SDLInputConnection is housed as a package-private class in
        // SDLActivity.java and compiles to its own classfile in the same
        // package, so FindClass("org/libsdl/app/SDLInputConnection") succeeds.
        assertTrue("SDLActivity.java must declare SDLInputConnection", activity.contains("class SDLInputConnection extends BaseInputConnection"))
        assertTrue("SDLActivity.java must declare the org.libsdl.app package", activity.contains("package org.libsdl.app;"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/" + relative),
            File("app/src/main/java/com/tomppi/enderslicer/" + relative),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to find source file " + relative)
    }
}
