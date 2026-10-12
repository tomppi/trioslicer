package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam's own edge, and the projection a joint's drag is made of.
 *
 * The two things worth pinning are the ones the user sees: the projection is on
 * an EDGE - a finger a third of the way along gets a third of the way along, not
 * the nearest corner - and it never leaves the contour, however far off the part
 * the finger wanders.
 */
class SnapSeamTest {
    @Test
    fun theContourIsTheEdgeTheSplitLeftOnTheMatingPlane() {
        val contour = contour()

        assertEquals("the box's own square, all the way round", 160f, contour.perimeterMm, 1e-3f)
        assertEquals("a corner is on it", 0f, contour.distanceTo(Vec3(0f, 0f, 20f)), 1e-3f)
        assertEquals("and so is the other one", 0f, contour.distanceTo(Vec3(40f, 40f, 20f)), 1e-3f)
        assertEquals(
            "the middle of the face is half a face from it",
            20f,
            contour.distanceTo(Vec3(20f, 20f, 20f)),
            1e-3f,
        )
        val on = contour.nearest(Vec3(33f, 7f, 20f))
        assertEquals("every point of it is on the mating plane", 20f, on.z, 1e-3f)
        assertTrue("and inside the part's own outline", on.x in 0f..40f && on.y in 0f..40f)
    }

    @Test
    fun aFingerOnTheSeamIsFollowedToTheMillimetre() {
        val contour = contour()

        val start = contour.nearest(Vec3(6f, 0f, 20f), Vec3(20f, 20f, 20f))
        assertEquals("the finger is where the joint goes", 0f, (start - Vec3(6f, 0f, 20f)).length(), 1e-3f)

        val moved = contour.nearest(Vec3(16f, 0f, 20f), start)
        assertEquals(
            "a 10 mm drag along the edge moves it 10 mm",
            10f,
            (moved - start).length(),
            1e-3f,
        )
    }

    @Test
    fun aFingerBesideTheSeamStillMovesItAsFarAsItWent() {
        val contour = contour()

        // A finger on the face rather than on the edge - which is where a finger
        // is - tracks the edge to within a fraction of a millimetre.
        val start = contour.nearest(Vec3(6f, 3f, 20f), Vec3(20f, 20f, 20f))
        assertEquals("the edge under the finger", 0f, (start - Vec3(6f, 0f, 20f)).length(), 0.05f)

        val moved = contour.nearest(Vec3(26f, 3f, 20f), start)
        assertEquals(
            "a 20 mm drag along the seam moves the joint 20 mm along it",
            20f,
            (moved - start).length(),
            0.1f,
        )
    }

    @Test
    fun theSlideFollowsTheEdgesRatherThanJumpingCornerToCorner() {
        val contour = contour()

        // A finger a millimetre inside the front edge, walking along it: every
        // sample moves the joint by the finger's own step. A projection onto the
        // outline's four thinned corners would sit still and then move in one
        // jump the length of an edge, which is the stutter this replaced.
        var previous = contour.nearest(Vec3(5f, 1f, 20f), Vec3(20f, 20f, 20f))
        var travelled = 0f
        for (step in 1..10) {
            val on = contour.nearest(Vec3(5f + step, 1f, 20f), previous)
            val moved = (on - previous).length()
            assertTrue("a step the finger's own size, not an edge-long jump: " + moved, moved <= 1.1f)
            travelled += moved
            previous = on
        }
        assertEquals("ten one-millimetre samples, ten millimetres of seam", 10f, travelled, 0.2f)
        assertTrue("with the joint still on the front edge", previous.y < 0.05f)
        assertTrue("and beside the finger", kotlin.math.abs(previous.x - 15f) < 0.2f)

        // A finger that has gone on round the corner: the joint is on the next
        // edge with it, rather than stuck at a corner it cannot leave.
        val rounded = contour.nearest(Vec3(39.5f, 6f, 20f), previous)
        assertTrue("the joint came round the corner with the finger", rounded.y > 3f)
        assertEquals("and is still on the seam", 0f, contour.distanceTo(rounded), 1e-3f)
        assertEquals("and on the mating plane", 20f, rounded.z, 1e-3f)
    }

    @Test
    fun aDragAcrossTheSeamLeavesTheJointOnIt() {
        val contour = contour()

        for (finger in listOf(
            Vec3(20f, 20f, 20f),
            Vec3(20f, -12f, 20f),
            Vec3(-9f, 33f, 20f),
            Vec3(70f, 70f, 20f),
        )) {
            val on = contour.nearest(finger, Vec3(20f, 0f, 20f))
            assertEquals("still on the seam", 0f, contour.distanceTo(on), 1e-3f)
            assertEquals("still on the mating plane", 20f, on.z, 1e-3f)
        }
    }

    @Test
    fun theMiddleOfAFaceDoesNotThrowTheJointToTheFarEdge() {
        val contour = contour()

        // The middle of a square face is equally far from all four edges. The
        // joint is on the front one, and a finger that drifts across the middle
        // - where the nearest edge is a toss-up - keeps it there rather than
        // flicking it to the far side of the part.
        val onFront = Vec3(20f, 0f, 20f)
        assertEquals(
            "the joint stays on the edge it was on",
            0f,
            (contour.nearest(Vec3(20f, 20f, 20f), onFront) - onFront).length(),
            0.1f,
        )
        val drifted = contour.nearest(Vec3(20f, 24f, 20f), onFront)
        assertTrue("and a finger past the middle takes it across, not around", drifted.y > 20f)
    }

    @Test
    fun aHalfWithNoCapAtTheSeamHasNoEdgeToSlideAlong() {
        // A block that stops well below the plane the joint is built on: there
        // is no seam material to read a contour from, and saying so is the
        // answer rather than an edge somewhere in mid-air.
        val short = SnapFitHalf.inPlace(
            MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 10f, name = "too short"),
            20f,
        )
        assertNull(SnapSeam.contour(short, Vec3(0f, 0f, 1f), Vec3(20f, 20f, 20f)))
    }

    @Test
    fun theContourIsReadOffTheHalvesOwnFrameNotThePlates() {
        // The same half carried 10 mm across the plate by the packer: the
        // contour is read in the half's OWN coordinates, so where the plate has
        // it cannot bend the edge the joint slides along - and neither can the
        // anchor, which only has to be a point on the seam.
        val mesh = MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 20f, name = "part lower")
        val shifted = MeshFixtures.box(10f, 10f, 0f, 50f, 50f, 20f, name = "part lower")
        val placed = SnapFitHalf.placed(mesh, shifted, 20f)

        val onPlate = requireNotNull(SnapSeam.contour(placed, Vec3(0f, 0f, 1f), Vec3(20f, 20f, 20f)))
        assertEquals("the same square, wherever the plate has the half", 160f, onPlate.perimeterMm, 1e-3f)
        assertEquals(
            "and the drag reads the same places in it",
            0f,
            (onPlate.nearest(Vec3(6f, 0f, 20f), null) - Vec3(6f, 0f, 20f)).length(),
            1e-3f,
        )
    }

    /** The low half of a 40 mm split: its mating face is the square at Z = 20. */
    private fun contour(): SnapSeamContour = requireNotNull(
        SnapSeam.contour(
            SnapFitHalf.inPlace(MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 20f, name = "part lower"), 20f),
            Vec3(0f, 0f, 1f),
            Vec3(20f, 20f, 20f),
        ),
    )
}
