package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plate's two halves of the same rule: an affordance is drawn over the model,
 * and a touch on it belongs to it.
 *
 * The viewer's gizmo is geometry drawn into the same frame as the parts, so the two
 * ways a part can "cover" it are draw order and touch order. Both are pinned here:
 * the gizmo pass runs after the model with depth testing off, and a finger-down is
 * offered to the gizmo's handles before the camera, the object pick or any tool. The
 * handle choice itself is [GizmoDragTest]'s rule (screen distance, nothing else).
 */
class GizmoOverlayOrderTest {

    private fun viewerSource(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("the app module was not found from " + File("").absolutePath)
        return File(root, "app/src/main/java/com/tomppi/enderslicer/viewer/ModelSurfaceView.kt")
            .readText()
    }

    @Test
    fun theGizmoPassRunsAfterTheModelWithDepthTestingOff() {
        val source = viewerSource()
        val scene = source.substringAfter("private fun drawScene(").substringBefore("private fun readPixels(")
        val mesh = scene.indexOf("drawMesh()")
        // The two pixel-sized overlays take the frame's own size (the offscreen plate capture
        // draws through them too), so their call sites carry arguments now.
        val gizmoCall = scene.indexOf("drawGizmo(")
        assertTrue("the frame draws the model", mesh >= 0)
        assertTrue("the frame draws the gizmo", gizmoCall >= 0)
        assertTrue("the gizmo is drawn after the model", gizmoCall > mesh)

        val gizmo = source.substringAfter("private fun drawGizmo(").substringBefore("private fun rebuildColorBuffer(")
        val disable = gizmo.indexOf("glDisable(GLES20.GL_DEPTH_TEST)")
        val draw = gizmo.indexOf("glDrawArrays")
        val enable = gizmo.indexOf("glEnable(GLES20.GL_DEPTH_TEST)")
        assertTrue("the gizmo draws with the depth test off", disable >= 0 && disable < draw)
        assertTrue("the depth test comes back for the next frame", enable > draw)
        // The document says the depth is not what puts it on top; the order and the
        // disabled test are.
        assertTrue(gizmo.contains("GL_DEPTH_TEST"))
        assertFalse("no depth-range trick is the mechanism here", scene.contains("glDepthRange"))
    }

    @Test
    fun aFingerDownOnAHandleIsOfferedToTheGizmoBeforeThePickers() {
        val source = viewerSource()
        val down = source.substringAfter("MotionEvent.ACTION_DOWN ->")
            .substringBefore("MotionEvent.ACTION_POINTER_DOWN ->")
        val grab = down.indexOf("grabGizmoHandleAt(")
        assertTrue("ACTION_DOWN offers the touch to the gizmo", grab >= 0)
        val jointPick = down.indexOf("jointPickActive)")
        // The surface picker and the plain paint brushes share the decide-first block now
        // (PaintStrokeGate), so the marker is the flag itself rather than "surfacePickActive)".
        // The ordering claim is unchanged: the gizmo is offered the touch first.
        val surfacePick = down.indexOf("surfacePickActive")
        val dragMove = down.indexOf("dragMoveActive)")
        assertTrue("the gizmo is tried before the joint picker", grab < jointPick)
        assertTrue("the gizmo is tried before the surface/face picker", grab < surfacePick)
        assertTrue("the gizmo is tried before finger-move", grab < dragMove)

        // And the hit test itself no longer asks the geometry what is in front of it:
        // that was the bug (the lid hid the arrow's plane, so the touch became a camera
        // orbit). Screen distance is the whole rule.
        val hit = source.substringAfter("fun gizmoHitAt(").substringBefore("fun gizmoArrowSamples(")
        assertFalse("the gizmo hit test does not consult the model hit ray",
            hit.contains("MeshPicker.pick("))
        assertFalse("no surface depth is judged against", hit.contains("surfaceDepth"))
        assertTrue("the handle choice is screen distance", hit.contains("chooseHandle(candidates, tolerancePx)"))
    }

    @Test
    fun theOverlayPassesAreDeclaredAfterTheModel() {
        val source = viewerSource()
        val scene = source.substringAfter("private fun drawScene(").substringBefore("private fun readPixels(")
        val mesh = scene.indexOf("drawMesh()")
        for (overlay in listOf("drawAxisTriad(", "drawAnnotation(", "drawGizmo(")) {
            assertTrue(overlay + " draws after the model", scene.indexOf(overlay) > mesh)
        }
    }
}
