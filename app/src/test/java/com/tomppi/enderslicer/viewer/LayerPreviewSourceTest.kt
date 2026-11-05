package com.tomppi.enderslicer.viewer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Layers tab camera's framing, pinned source level: the renderer draws on a GL thread, so the
 * rule is read from the source the same way [PaintGestureSourceTest] pins the gesture plumbing.
 */
class LayerPreviewSourceTest {

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
    fun theCameraIsToldThePrintHeightFromTheParsedLayers() {
        val view = source("viewer/LayerPreviewSurfaceView.kt")
        // Without this assignment the fit radius ignores Z and the orbit pivot sits on the bed for
        // every print - the camera framed a tall model as if it were flat.
        assertTrue(
            "setPreview takes the maximum layer Z from the parsed layers",
            view.contains("maximumLayerZ = value.layers.maxOfOrNull { it.z } ?: 0f"),
        )
        assertTrue(
            "the fit radius includes the print height",
            view.contains("maximumLayerZ * maximumLayerZ"),
        )
        assertTrue(
            "and the orbit pivot sits halfway up the print",
            view.contains("-maximumLayerZ * 0.5f"),
        )
    }
}
