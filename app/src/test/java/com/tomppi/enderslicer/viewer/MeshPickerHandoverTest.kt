package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pick goes through the matrix the frame was drawn with.
 *
 * This is the handover, and it is the bug it fixes: the picker used to compute
 * its own camera fit from a snapshot of yaw/pitch/zoom, and that fit did not
 * always agree with the one the plate was drawn with - measured on a device at
 * 485 mm against the frame's 493 mm. Taps then landed past the model: a tap on
 * a part selected nothing, a tap on the model placed no joint, and the cut
 * preview's picker answered with geometry that was not on screen.
 *
 * The matrices here are built by hand, because the point of the test is that
 * nothing in the pick path recomputes a camera: publish one and the pick obeys
 * it exactly, publish a different one and the same screen point answers about
 * different geometry. android.opengl.Matrix is deliberately not involved - it
 * is a JVM test, and the pick path is arithmetic.
 */
class MeshPickerHandoverTest {
    /**
     * Looking along +Y at a 10 mm cube on the origin: +X right, +Z up, screen
     * 1000x1000, near plane at Y = -1 and far plane at Y = 9, so the eye is
     * outside the cube and the first face the ray meets is the cube's own.
     */
    private fun orthographic(): FloatArray = floatArrayOf(
        0.2f, 0f, 0f, 0f,
        0f, 0f, 0.2f, 0f,
        0f, 0.2f, 0f, 0f,
        -1f, -1f, -0.8f, 1f,
    )

    @Test
    fun withoutAPublishedFrameThereIsNoPick() {
        MeshPicker.clearFrame()

        assertNull("no frame has been drawn, so nothing is picked", MeshPicker.pick(cube(), 500f, 500f))
        assertNull(MeshPicker.ray(500f, 500f))
        assertNull(MeshPicker.project(5f, 5f, 5f))
        assertNull(MeshPicker.depthOf(5f, 5f, 5f))
    }

    @Test
    fun thePickFollowsThePublishedMatrix() {
        MeshPicker.publish(orthographic(), 1000, 1000)

        val hit = MeshPicker.pick(cube(), 500f, 500f)

        assertNotNull("a tap at the middle hits the cube's near face", hit)
        assertEquals("at the world point the matrix puts there", 5f, hit!!.x, 1e-3f)
        assertEquals("which is the face the ray meets first", 0f, hit.y, 1e-3f)
        assertEquals(5f, hit.z, 1e-3f)
    }

    @Test
    fun aDifferentPublishedMatrixAnswersDifferentlyForTheSameScreenPoint() {
        MeshPicker.publish(orthographic(), 1000, 1000)
        val before = MeshPicker.pick(cube(), 500f, 500f)

        // The same projection with the world shifted two millimetres along X:
        // the pixel that used to sit over x = 5 now sits over x = 7, and the
        // point that used to be under it is 20 px to its left.
        val shifted = orthographic()
        shifted[12] = -1.4f
        MeshPicker.publish(shifted, 1000, 1000)

        val samePixel = MeshPicker.pick(cube(), 500f, 500f)
        assertNotNull(samePixel)
        assertEquals("the same pixel, a different point of the world", 7f, samePixel!!.x, 1e-3f)
        assertEquals(before!!.y, samePixel.y, 1e-3f)
        assertEquals(before.z, samePixel.z, 1e-3f)

        val moved = MeshPicker.pick(cube(), 300f, 500f)
        assertNotNull("and the old point is now where the new matrix puts it", moved)
        assertEquals(before.x, moved!!.x, 1e-3f)
        assertEquals(before.y, moved.y, 1e-3f)
        assertEquals(before.z, moved.z, 1e-3f)
    }

    @Test
    fun theProjectionAndTheDepthComeFromTheSameMatrix() {
        MeshPicker.publish(orthographic(), 1000, 1000)

        val screen = MeshPicker.project(5f, 5f, 5f)
        assertNotNull(screen)
        assertEquals("the cube's centre is the middle of the screen", 500f, screen!![0], 1e-2f)
        assertEquals(500f, screen[1], 1e-2f)
        assertEquals("and the top of the cube is above it", 0f, MeshPicker.project(5f, 5f, 10f)!![1], 1e-2f)

        // Depth is the clip w, which for this matrix is 1 everywhere; what
        // matters is that it is read, not that it varies here.
        assertEquals(1f, MeshPicker.depthOf(5f, 5f, 5f)!!, 1e-4f)
    }

    @Test
    fun theRayIsTheLineThroughTheTappedPixel() {
        MeshPicker.publish(orthographic(), 1000, 1000)

        val ray = MeshPicker.ray(500f, 500f)

        assertNotNull(ray)
        assertEquals("the ray starts on the near plane", -1f, ray!!.originY, 1e-3f)
        assertEquals("at the world point under the finger", 5f, ray.originX, 1e-3f)
        assertEquals(5f, ray.originZ, 1e-3f)
        assertEquals("and runs away from the camera", 1f, ray.dirY, 1e-3f)
        assertEquals(0f, ray.dirX, 1e-4f)
        assertEquals(0f, ray.dirZ, 1e-4f)
    }

    @Test
    fun aSingularMatrixIsNotACamera() {
        MeshPicker.publish(FloatArray(16), 1000, 1000)

        assertNull("an empty matrix projects nothing", MeshPicker.project(5f, 5f, 5f))
        assertNull("and picks nothing", MeshPicker.pick(cube(), 500f, 500f))
        assertTrue("rather than answering from a stale frame", true)
    }

    /** A closed 10 mm cube on the origin, one triangle per face half. */
    private fun cube(): StlMesh = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)
}
