package com.tomppi.enderslicer.ui.klipper

import androidx.compose.ui.graphics.Color
import com.tomppi.enderslicer.printer.KlipperMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection behind the surface view: which way is up, which way is away, and which quad is
 * painted over which.
 *
 * The camera conventions are the point of this file. The first version reached for the right
 * expressions in the wrong order, which drew the bed upside down and painted it back to front at
 * the same time - the surface read as the underside of the printer.
 */
class MeshSurfaceGeometryTest {
    private fun grid(heights: (Int, Int) -> Double): KlipperMesh = KlipperMesh(
        profileName = "test",
        points = List(5) { row -> List(5) { column -> heights(row, column) } },
    )

    private fun flat() = grid { _, _ -> 0.05 }

    @Test
    fun theBackOfTheBedIsDrawnAboveTheFront() {
        val projected = MeshSurfaceGeometry.project(flat(), 0f, 55f)
        val front = projected.first().maxOf { it.y }
        val back = projected.last().minOf { it.y }
        assertTrue("back was $back, front $front", back > front)
    }

    @Test
    fun theBackOfTheBedIsFartherAwayThanTheFront() {
        val projected = MeshSurfaceGeometry.project(flat(), 0f, 55f)
        val front = projected.first().maxOf { it.depth }
        val back = projected.last().minOf { it.depth }
        assertTrue("back was $back, front $front", back > front)
    }

    @Test
    fun aHigherPointAtTheSamePlaceIsDrawnHigherAndNearer() {
        // The same grid point twice, raised once: comparing two different points would only
        // measure how far back they are, which is what the two tests above already do.
        val onTheBed = MeshSurfaceGeometry.project(grid { _, _ -> 0.0 }, 0f, 55f)[2][2]
        val raised = MeshSurfaceGeometry.project(
            grid { row, column -> if (row == 2 && column == 2) 0.1 else 0.0 },
            0f,
            55f,
        )[2][2]
        assertTrue("raised was " + raised.y + ", flat " + onTheBed.y, raised.y > onTheBed.y)
        assertTrue(
            "raised was " + raised.depth + ", flat " + onTheBed.depth,
            raised.depth < onTheBed.depth,
        )
    }

    @Test
    fun theFarQuadIsPaintedFirst() {
        val facets = MeshSurfaceGeometry.facets(flat(), 400f, 300f, 20f, 55f)
        assertEquals(facets.map { it.depth }.sortedDescending(), facets.map { it.depth })
    }

    @Test
    fun everyQuadOfTheGridIsDrawn() {
        assertEquals(16, MeshSurfaceGeometry.facets(flat(), 400f, 300f, -35f, 55f).size)
    }

    @Test
    fun theSurfaceFitsInsideTheCanvas() {
        MeshSurfaceGeometry.facets(flat(), 400f, 300f, -35f, 55f).forEach { facet ->
            facet.corners.forEach { corner ->
                assertTrue("x was " + corner.x, corner.x >= 0f && corner.x <= 400f)
                assertTrue("y was " + corner.y, corner.y >= 0f && corner.y <= 300f)
            }
        }
    }

    @Test
    fun aMeshThatIsNotASurfaceDrawsNothing() {
        val one = KlipperMesh(points = List(1) { List(1) { 0.0 } })
        assertTrue(MeshSurfaceGeometry.project(one, 0f, 45f).isEmpty())
    }

    @Test
    fun turningTheBedDoesNotResizeIt() {
        // At yaw 0 the width on screen is the scale times the unit square, whatever the tilt.
        // A fit recomputed per frame changed it with the tilt, which is the drag that read as
        // a zoom rather than a turn.
        fun width(pitch: Float): Float {
            val corners = MeshSurfaceGeometry.facets(flat(), 400f, 300f, 0f, pitch)
                .flatMap { it.corners }
            return corners.maxOf { it.x } - corners.minOf { it.x }
        }
        assertEquals(width(30f), width(60f), 0.5f)
        assertEquals(width(30f), width(-45f), 0.5f)
    }

    @Test
    fun fromAboveTheSurfaceIsDrawnInColour() {
        val facets = MeshSurfaceGeometry.facets(flat(), 400f, 300f, -35f, 55f)
        assertTrue(facets.none { it.underside })
        assertTrue(facets.any { it.color != Color.Black })
    }

    @Test
    fun aSteepMeshViewedFromAboveIsNotMistakenForItsUnderside() {
        // Heights exaggerated until the top of the bed is cliffs, which is what the real
        // exaggeration does. Judging each quad by its own facing blackened half of them, and
        // the screen showed more black than bed. The camera decides, and it is above this one.
        val steep = KlipperMesh(
            profileName = "test",
            points = List(5) { row -> List(5) { column -> if ((row + column) % 2 == 0) 0.0 else 0.2 } },
        )
        val facets = MeshSurfaceGeometry.facets(steep, 400f, 300f, -35f, 55f)
        assertTrue("a quad was called the underside", facets.none { it.underside })
        assertTrue("a quad was blanked", facets.none { it.color == Color.Black })
    }

    @Test
    fun fromBelowEveryQuadIsTheUnderside() {
        val facets = MeshSurfaceGeometry.facets(flat(), 400f, 300f, -35f, -55f)
        assertTrue("nothing was drawn", facets.isNotEmpty())
        assertTrue("a quad was still facing up", facets.all { it.underside })
        assertTrue("a quad was still coloured", facets.all { it.color == Color.Black })
    }

    @Test
    fun theTiltTurnsAllTheWayOver() {
        assertEquals(0f, MeshSurfaceGeometry.wrapPitch(360f), 0.001f)
        assertEquals(170f, MeshSurfaceGeometry.wrapPitch(-190f), 0.001f)
        assertTrue(MeshSurfaceGeometry.isFromBelow(-55f))
        assertFalse(MeshSurfaceGeometry.isFromBelow(55f))
        assertFalse(MeshSurfaceGeometry.isFromBelow(90f))
    }

    @Test
    fun theTurntableAngleStaysWhereItCanBeUsed() {
        assertEquals(0f, MeshSurfaceGeometry.wrapYaw(360f), 0.001f)
        assertEquals(-10f, MeshSurfaceGeometry.wrapYaw(350f), 0.001f)
        assertEquals(10f, MeshSurfaceGeometry.wrapYaw(-350f), 0.001f)
    }
}