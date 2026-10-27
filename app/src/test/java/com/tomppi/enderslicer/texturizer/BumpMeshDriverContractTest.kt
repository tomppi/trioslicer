package com.tomppi.enderslicer.texturizer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The studio driver and the Kotlin hosting class are two halves of one contract; these source-level tests fail when a
 * selector, an event or a public entry point is renamed on one side only.
 */
class BumpMeshDriverContractTest {

    private fun appFile(relative: String): File {
        // JVM unit tests run with the app module as the working directory.
        val file = File(relative)
        assertTrue("missing " + file.absolutePath, file.isFile)
        return file
    }

    private fun driverText(): String = appFile("src/main/res/raw/bumpmesh_studio_driver.js").readText()

    @Test
    fun theDriverDrivesEveryPageControlTheStudioSettingsNeed() {
        val driver = driverText()
        listOf(
            "#stl-file-input",
            "#import-project-input",
            "#texture-file-input",
            "#mapping-mode",
            "#scale-u-val",
            "#scale-v-val",
            "#amplitude-val",
            "#texture-smoothing-val",
            "#refine-length-val",
            "#max-triangles",
            "#export-btn",
            "load-mode-all",
            "load-go-btn",
            "load-dialog",
            "export-progress",
        ).forEach { id -> assertTrue("driver must touch " + id, driver.contains(id)) }
        assertTrue("change events", driver.contains("new Event('change'"))
        assertTrue("input event for the triangle slider", driver.contains("new Event('input'"))
    }

    @Test
    fun theDriverExposesTheCommandSurfaceAndReportsTheNamedEvents() {
        val driver = driverText()
        listOf(
            "window.__BumpMeshStudio",
            "openModel",
            "openProject",
            "configure",
            "exportNow",
            "abort",
            "EnderSlicerStudio",
            "driver-ready",
            "model-loaded",
            "project-loaded",
            "settings-applied",
            "page-progress",
            "onEvent",
            "onWarning",
            "onError",
        ).forEach { token -> assertTrue("driver missing " + token, driver.contains(token)) }
        assertTrue("the export uses the page's own button", driver.contains("button.click()"))
    }

    @Test
    fun theHostInjectsTheRawDriverAndBindsBothJavascriptInterfaces() {
        val host = appFile("src/main/java/com/tomppi/enderslicer/texturizer/BumpMeshStudio.kt").readText()
        listOf(
            "class BumpMeshStudio(",
            "fun warmUp()",
            "fun textures(): List<TextureRef>",
            "suspend fun apply(",
            "fun cancel()",
            "fun close()",
            "data class TextureRef",
            "data class ApplyRequest",
            "data class ApplyResult",
            "enum class MappingMode",
            "bumpmesh_studio_driver",
            "\"EnderSlicerStudio\"",
            "\"EnderSlicerAndroid\"",
            "BumpMeshLibraryManifest",
            "abortAndReload",
            "EVENT_CANCELLED",
        ).forEach { token -> assertTrue("host missing " + token, host.contains(token)) }
        assertEquals(
            "the driver resource name is what the host looks up",
            true,
            host.contains("\"bumpmesh_studio_driver\""),
        )
    }

    @Test
    fun theKotlinSettingsPayloadIsTheJsonTheDriverReads() {
        val json = BumpMeshDriverCommand.settingsJson(
            mappingMode = 5,
            textureAssetPath = "bumpmesh/textures/brick.png",
            tileMm = 12f,
            heightMm = 0.6f,
            smoothing = 0.5f,
            refineMm = 0.4f,
            maxTriangles = 8_000_000,
        )
        assertEquals(
            "{\"mappingMode\":5,\"textureAsset\":\"bumpmesh/textures/brick.png\",\"tileMm\":12," +
                "\"heightMm\":0.6,\"smoothing\":0.5,\"refineMm\":0.4,\"maxTriangles\":8000000}",
            json,
        )
        assertTrue("a quote is escaped", BumpMeshDriverCommand.jsString("a\"b").contains("\\\""))
        assertTrue("a newline is escaped", BumpMeshDriverCommand.jsString("a\nb").contains("\\n"))
    }
}
