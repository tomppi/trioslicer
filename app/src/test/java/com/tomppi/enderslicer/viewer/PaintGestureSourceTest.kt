package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The gesture plumbing that makes a brush behave like a tool, not like a mode lock.
 *
 * Pinned here (source level, because a GLSurfaceView gesture test would need a GL context):
 * a finger-down with a brush armed is a held question rather than a stroke, the picker's hit
 * starts the stroke through the same [PaintStrokeGate], a second finger cancels only an
 * undecided gesture, and the two-finger pan branch is still ahead of the brush sample branch.
 */
class PaintGestureSourceTest {

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
    fun aFingerDownWithABrushArmedAsksBeforeItPaints() {
        val viewer = source("viewer/ModelSurfaceView.kt")
        assertTrue(
            "the plain paint modes share the decide-first gate with the surface picker",
            viewer.contains("if (surfacePickActive || painting) {"),
        )
        assertTrue(viewer.contains("private val brushGate = PaintStrokeGate()"))
        assertTrue(viewer.contains("brushProbeToken = brushGate.begin()"))
        assertTrue(
            "the old immediate stroke scheduling is gone",
            !viewer.contains("if (painting) {\n                        pendingPaintCoordinates"),
        )
        assertTrue(
            "later samples paint only after the probe answered on the model",
            viewer.contains("} else if (brushGate.dragging) {"),
        )
        assertTrue(viewer.contains("} else if (brushGate.deciding) {"))
    }

    @Test
    fun theProbeRoutesToTheBrushThatIsArmedAndMissesBecomeOrbits() {
        val viewer = source("viewer/ModelSurfaceView.kt")
        val probe = viewer.substringAfter("private fun applyBrushProbe")
        assertTrue(probe.contains("brushGate.probe("))
        assertTrue("the studio region brush is routed", probe.contains("onTextureRegionHit?.invoke"))
        assertTrue("the support brush is routed", probe.contains("onPaintHit?.invoke"))
        assertTrue(
            "a miss replays the held movement as the orbit it would have been",
            probe.contains("modelRenderer.rotate(dx * 0.35f, dy * 0.35f)"),
        )
    }

    @Test
    fun twoFingersCancelOnlyTheUndecidedQuestion() {
        val viewer = source("viewer/ModelSurfaceView.kt")
        assertTrue(viewer.contains("if (brushGate.cancelDecision()) brushProbePending.set(false)"))
        val pointerDown = viewer.substringAfter("MotionEvent.ACTION_POINTER_DOWN ->")
        assertTrue(pointerDown.contains("brushGate.cancelDecision()"))
        // The two-finger pan/zoom branch stays ahead of the single-finger brush branch.
        val move = viewer.substringAfter("MotionEvent.ACTION_MOVE ->")
        val twoFinger = move.indexOf("if (event.pointerCount >= 2) {")
        val oneFinger = move.indexOf("} else if (!scaleDetector.isInProgress) {")
        assertTrue(twoFinger in 0 until oneFinger)
        assertTrue(
            "a second finger pans even mid-stroke",
            move.substring(twoFinger, oneFinger).contains("modelRenderer.panPixels("),
        )
        assertFalse(
            "a begun stroke is not cancelled by the second finger",
            pointerDown.substringBefore("previousFocusX").contains("brushGate.end()"),
        )
    }

    @Test
    fun theBarsTellTheTruthAboutOrbitingBesideTheModel() {
        val app = source("ui/EnderSlicerApp.kt")
        assertTrue(
            app.contains("Drag on the model to paint the region; drag beside it to rotate the view."),
        )
        assertTrue(
            app.contains("drag beside it rotates the view; two fingers pan and zoom"),
        )
        val tools = source("ui/ModelToolsSheet.kt")
        assertTrue(
            tools.contains("drag beside it rotates the view; two fingers pan and zoom"),
        )
        assertFalse(
            "the old two-fingers-only hint is gone",
            app.contains("Use two fingers to rotate and zoom."),
        )
    }
}
