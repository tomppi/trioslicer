package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The offscreen plate capture draws into its own frame size instead of borrowing the window's.
 *
 * The renderer used to publish the capture's width and height into the surface's viewport fields
 * for the length of the draw and put them back afterwards. A gesture resolved on another thread in
 * that window (a pan, a finger drag, an arrow drag - all convert pixels to millimetres through the
 * viewport height) measured itself against the capture's size, so a drag that overlapped a plate
 * render moved the part by the wrong number. The capture size is a parameter of the draw now; the
 * fields are the on-screen size and only [onSurfaceChanged] writes them.
 */
class PlateCaptureViewportTest {

    @Test
    fun theCapturePassesItsSizeDownInsteadOfWritingTheSurfaceViewport() {
        val capture = viewerSource().substringAfter("fun renderOffscreen()")
            .substringBefore("private fun ensurePreviewUpload()")
        assertTrue(
            "the capture draws through its own frame size",
            capture.contains("drawScene(captureWidth, captureHeight"),
        )
        assertTrue(
            "the GL viewport is the capture's for the capture",
            capture.contains("glViewport(0, 0, captureWidth, captureHeight)"),
        )
        assertFalse(
            "the capture does not rewrite the on-screen width",
            capture.contains("viewportWidth ="),
        )
        assertFalse(
            "the capture does not rewrite the on-screen height",
            capture.contains("viewportHeight ="),
        )
    }

    @Test
    fun onlyTheSurfaceChangeWritesTheOnScreenSize() {
        val source = viewerSource()
        val changed = source.substringAfter("override fun onSurfaceChanged(")
            .substringBefore("override fun onDrawFrame(")
        assertTrue(changed.contains("viewportWidth = max(width, 1)"))
        assertTrue(changed.contains("viewportHeight = max(height, 1)"))
        assertTrue(
            "the on-screen frame is drawn at the surface size",
            source.substringAfter("override fun onDrawFrame(")
                .substringBefore("private fun drawScene(")
                .contains("drawScene(viewportWidth, viewportHeight, publishForPicker = true)"),
        )
    }

    @Test
    fun thePixelSizedOverlaysUseTheFrameTheyAreDrawnInto() {
        val source = viewerSource()
        val scene = source.substringAfter("private fun drawScene(")
            .substringBefore("private fun readPixels(")
        assertTrue("the gizmo ribbon is sized for the frame drawn", scene.contains("drawGizmo(width, height)"))
        assertTrue("the axis triad is sized for the frame drawn", scene.contains("drawAxisTriad(height)"))

        val gizmo = source.substringAfter("private fun drawGizmo(")
            .substringBefore("private fun rebuildColorBuffer(")
        assertTrue(gizmo.contains("viewportWidth = width"))
        assertTrue(gizmo.contains("viewportHeight = height"))

        val triad = source.substringAfter("private fun drawAxisTriad(")
            .substringBefore("private fun buildGrid(")
        assertTrue(triad.contains("viewportHeightPx = height"))
    }

    @Test
    fun theGestureReadersStillConvertAgainstTheOnScreenSize() {
        val source = viewerSource()
        val pan = source.substringAfter("fun panPixels(")
            .substringBefore("fun dragDeltaMm(")
        assertTrue("a pan converts against the on-screen height", pan.contains("max(viewportHeight, 1)"))
        val drag = source.substringAfter("fun dragDeltaMm(")
            .substringBefore("fun setDragOffset(")
        assertTrue("a finger drag converts against the on-screen height", drag.contains("viewportHeightPx = viewportHeight"))
        val axis = source.substringAfter("fun axisDragMillimetres(")
        assertTrue(
            "an arrow drag converts against the on-screen height",
            axis.contains("viewportHeightPx = viewportHeight"),
        )
    }

    private fun viewerSource(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("the app module was not found from " + File("").absolutePath)
        return File(root, "app/src/main/java/com/tomppi/enderslicer/viewer/ModelSurfaceView.kt")
            .readText()
    }
}
