package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which tools the surface pick may report to, and which tools own the plate's touches.
 *
 * The gate this pins was the face-selection bug: every surface pick was limited to the selected
 * plate object, so on a two-part CAD document only the first part's faces could be selected - a tap
 * on the second part fell through to the camera with no message. Smart Infill's selected-object
 * rule is the one that must stay, and the support brush keeps painting the part under the finger.
 */
class SurfacePickGateTest {

    private fun hit(objectIndex: Int) = MeshPicker.Hit(
        triangleIndex = objectIndex * 10,
        x = 0f,
        y = 0f,
        z = 0f,
        objectIndex = objectIndex,
    )

    @Test
    fun aFacePickAcceptsAnyPartOfTheDocument() {
        for (objectIndex in 0..3) {
            assertTrue(
                "a face on plate object " + objectIndex + " is a face",
                SurfacePickGate.reports(
                    hit = hit(objectIndex),
                    faceSelectActive = true,
                    surfacePickActive = true,
                    selectedObjectIndex = 0,
                ),
            )
        }
    }

    @Test
    fun smartInfillStillOnlyReportsTheSelectedObject() {
        assertTrue(
            SurfacePickGate.reports(
                hit = hit(1),
                faceSelectActive = false,
                surfacePickActive = true,
                selectedObjectIndex = 1,
            ),
        )
        assertFalse(
            "Smart Infill's boundary condition is about the object the tools act on",
            SurfacePickGate.reports(
                hit = hit(2),
                faceSelectActive = false,
                surfacePickActive = true,
                selectedObjectIndex = 1,
            ),
        )
    }

    @Test
    fun theSupportBrushPaintsThePartUnderTheFinger() {
        for (objectIndex in 0..3) {
            assertTrue(
                SurfacePickGate.reports(
                    hit = hit(objectIndex),
                    faceSelectActive = false,
                    surfacePickActive = false,
                    selectedObjectIndex = 1,
                ),
            )
        }
    }

    @Test
    fun aMissReportsNothing() {
        for (faceSelect in listOf(false, true)) {
            for (surfacePick in listOf(false, true)) {
                assertFalse(
                    SurfacePickGate.reports(
                        hit = null,
                        faceSelectActive = faceSelect,
                        surfacePickActive = surfacePick,
                        selectedObjectIndex = 0,
                    ),
                )
            }
        }
    }

    // --- the touch handler goes through the gate ---------------------------------------------

    @Test
    fun bothPickPathsAskTheGate() {
        val source = viewerSource()
        val probe = source.substringAfter("private fun applyBrushProbe(")
            .substringBefore("private fun schedulePaintPick(")
        assertTrue("the touch-down probe asks the gate", probe.contains("SurfacePickGate.reports("))
        assertFalse(
            "the probe does not gate the face pick on the selected object",
            probe.contains("hit.objectIndex == currentSelectedIndex"),
        )
        val samples = source.substringAfter("private fun schedulePaintPick(")
            .substringBefore("private fun scheduleAnnotationGesture(")
        assertTrue("the later samples ask the gate", samples.contains("SurfacePickGate.reports("))
        assertFalse(
            "a later sample does not gate the face pick on the selected object",
            samples.contains("hit.objectIndex == currentSelectedIndex"),
        )
    }

    // --- one tool owns the plate's touches ----------------------------------------------------

    @Test
    fun anArmedSurfacePickerKeepsTheTransformGizmoOffTheFinger() {
        val source = viewerSource()
        // A long press while a picker is armed must not open the transform card over it...
        val longPress = source.substringAfter("override fun onLongPress(")
            .substringBefore("override fun onDoubleTap(")
        assertTrue(
            "a long press during a surface pick is not a transform request",
            longPress.contains("!surfacePickActive"),
        )
        // ...and a tap on a handle while one is armed is the pick, not a drag. Even if a card
        // were left up, the picker owns the touch.
        val grab = source.substringAfter("private fun grabGizmoHandleAt(")
            .substringBefore("override fun onTouchEvent(")
        assertTrue(
            "an armed surface picker takes the touch before the gizmo handles",
            grab.contains("if (surfacePickActive) return false"),
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
