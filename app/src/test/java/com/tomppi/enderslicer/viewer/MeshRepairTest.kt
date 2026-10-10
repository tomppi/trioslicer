package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hole-filling pre-flight.
 *
 * A slicer never has to decide inside from outside, but a mesh boolean does, so
 * the contract these tests pin down is the one the joint workflow leans on: a
 * cube missing a face comes back a closed cube of exactly its own volume, a
 * three-cornered hole is filled, an open *surface* is refused rather than
 * turned into a zero-volume shell, and the two caps - a loop that is too long
 * and a hole that is too wide - are reported instead of guessed past.
 */
class MeshRepairTest {
    @Test
    fun aCubeWithAFaceMissingIsClosedToItsExactVolume() {
        val cube = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)
        val open = MeshFixtures.without(cube) { it == MeshFixtures.TOP * 2 || it == MeshFixtures.TOP * 2 + 1 }
        assertTrue("the fixture really is open", !MeshFixtures.isClosed(open))

        val repaired = MeshRepair.repair(open)

        assertNotSame("a repaired mesh is a new mesh", open, repaired.mesh)
        assertEquals("one hole was found", 1, repaired.report.loopsFound)
        assertEquals("and filled", 1, repaired.report.loopsFilled)
        assertEquals("its four edges were closed", 4, repaired.report.edgesClosed)
        assertEquals("nothing was left open", 0, repaired.report.loopsLeftOpen)
        assertNull("so there is no refusal to report", repaired.report.reason)
        assertTrue("the patch closes the solid", MeshFixtures.isClosed(repaired.mesh))
        assertEquals("and the volume is the cube's own", 1000.0, MeshFixtures.signedVolume(repaired.mesh), 1e-3)
        assertEquals("closed 1 hole (4 edges)", "closed 1 hole (4 edges)", repaired.report.summary)
    }

    @Test
    fun aSingleTriangleHoleIsFilled() {
        val cube = MeshFixtures.fannedBox(0f, 0f, 0f, 10f, 10f, 10f)
        val open = MeshFixtures.without(cube) { it == 0 }

        val repaired = MeshRepair.repair(open)

        assertEquals("the missing triangle is a three-edged hole", 3, repaired.report.edgesClosed)
        assertEquals(1, repaired.report.loopsFound)
        assertEquals(1, repaired.report.loopsFilled)
        assertTrue("the surface is closed again", MeshFixtures.isClosed(repaired.mesh))
        assertEquals(
            "and bounds the cube's volume to within a triangle's worth",
            1000.0,
            MeshFixtures.signedVolume(repaired.mesh),
            1e-3,
        )
    }

    @Test
    fun anOpenSurfaceIsReportedNotFilled() {
        val sheet = MeshFixtures.fromSoup(
            "sheet",
            MeshFixtures.triangleOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f),
        )
        val folded = MeshFixtures.fromSoup(
            "folded",
            MeshFixtures.triangleOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f) +
                MeshFixtures.triangleOf(10f, 0f, 0f, 10f, 10f, 0f, 0f, 10f, 0f),
        )

        for (surface in listOf(sheet, folded)) {
            val repaired = MeshRepair.repair(surface)
            assertSame("an open surface is not a solid with a hole", surface, repaired.mesh)
            assertEquals("its boundary was found", 1, repaired.report.loopsFound)
            assertEquals("but nothing was filled", 0, repaired.report.loopsFilled)
            assertEquals(1, repaired.report.loopsLeftOpen)
            assertTrue(
                "and the refusal says why: " + repaired.report.reason,
                repaired.report.reason.orEmpty().contains("open surface"),
            )
        }
    }

    @Test
    fun aMeshWithNoBoundaryEdgesComesBackAsTheSameInstance() {
        val cube = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)

        val repaired = MeshRepair.repair(cube)

        assertSame("nothing to do means no copy", cube, repaired.mesh)
        assertSame("not even the vertex data", cube.interleavedVertices, repaired.mesh.interleavedVertices)
        assertEquals(0, repaired.report.loopsFound)
        assertEquals(0, repaired.report.loopsFilled)
        assertEquals("closed already", repaired.report.summary)
    }

    @Test
    fun aHoleLongerThanTheLoopCapIsReportedNotFilled() {
        val cube = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)
        val open = MeshFixtures.without(cube) { it == MeshFixtures.TOP * 2 || it == MeshFixtures.TOP * 2 + 1 }

        val repaired = MeshRepair.repair(open, MeshRepair.Limits(maxLoopEdges = 3))

        assertSame("a refused repair does not copy the mesh", open, repaired.mesh)
        assertEquals(1, repaired.report.loopsFound)
        assertEquals(0, repaired.report.loopsFilled)
        assertEquals(1, repaired.report.loopsLeftOpen)
        assertTrue(
            "the cap is named: " + repaired.report.reason,
            repaired.report.reason.orEmpty().contains("more than 3 edges"),
        )
    }

    @Test
    fun aHoleWiderThanTheEdgeCapIsReportedNotFilled() {
        val cube = MeshFixtures.box(0f, 0f, 0f, 100f, 100f, 100f)
        val open = MeshFixtures.without(cube) { it == MeshFixtures.TOP * 2 || it == MeshFixtures.TOP * 2 + 1 }

        val repaired = MeshRepair.repair(open, MeshRepair.Limits(maxFillEdgeMm = 10f))

        assertSame(open, repaired.mesh)
        assertEquals(1, repaired.report.loopsFound)
        assertEquals(0, repaired.report.loopsFilled)
        assertTrue(
            "the width is named: " + repaired.report.reason,
            repaired.report.reason.orEmpty().contains("spanning more than 10"),
        )
    }
}
