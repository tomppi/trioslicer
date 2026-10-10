package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.BedClipper.Half
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weld's sliver guard, and the triangles it exists to split.
 *
 * A cut through an axis-aligned wall leaves the wall's own triangles standing
 * perpendicular to the cut plane, with their edges running along the seam. The
 * guard that decides whether such a triangle may be split asks whether it has
 * any area to split - and it used to ask that in the CUT PLANE's own two
 * coordinates, where a triangle standing perpendicular to the plane projects to
 * nothing. So exactly the triangles the cap has to be welded to were refused,
 * their cap vertices were never paired with the surface beside them, and each
 * half came out of a cut it should have taken with a T-junction: closed-looking,
 * wrong volume, and refused as non-manifold by every boolean engine.
 *
 * What is asserted here is what that failure looks like from outside: the half
 * is edge-manifold, and the two halves measure the whole part exactly.
 */
class BedClipperSliverWeldTest {
    @Test
    fun aCutThroughABoxWallLeavesAManifoldHalfAtEveryDepth() {
        val box = MeshFixtures.box(0f, 0f, 0f, 80f, 40f, 40f)
        val whole = MeshFixtures.signedVolume(box)

        for (cut in listOf(4f, 5f, 7f, 20f)) {
            val high = BedClipper.clipClosed(box, ModelPlacement.Axis.Z, cut, Half.HIGH)
            val low = BedClipper.clipClosed(box, ModelPlacement.Axis.Z, cut, Half.LOW)

            assertTrue("the half above a Z cut at " + cut + " mm is edge-manifold", MeshFixtures.isClosed(high))
            assertTrue("and the half below it too", MeshFixtures.isClosed(low))
            assertEquals(
                "the slab above the cut is exactly its own volume",
                80.0 * 40.0 * (40.0 - cut),
                MeshFixtures.signedVolume(high),
                1e-3,
            )
            assertEquals(
                "and the two halves are the whole box",
                whole,
                MeshFixtures.signedVolume(high) + MeshFixtures.signedVolume(low),
                1e-3,
            )
        }
    }

    @Test
    fun aCutAcrossABoxOnXLeavesBothHalvesManifold() {
        val box = MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 80f)

        for (cut in listOf(4f, 5f, 7f, 20f)) {
            val high = BedClipper.clipClosed(box, ModelPlacement.Axis.X, cut, Half.HIGH)
            val low = BedClipper.clipClosed(box, ModelPlacement.Axis.X, cut, Half.LOW)

            assertTrue("the +X half at " + cut + " mm is edge-manifold", MeshFixtures.isClosed(high))
            assertTrue("and the -X half too", MeshFixtures.isClosed(low))
            assertEquals(
                "the +X half is the slab past the cut",
                (40.0 - cut) * 40.0 * 80.0,
                MeshFixtures.signedVolume(high),
                1e-3,
            )
        }
    }

    @Test
    fun aHollowBoxIsManifoldOnBothAxesAtEveryCut() {
        // A hollow cross-section puts the cut through interior walls as well as
        // the outside ones, which is where the guard bit hardest.
        val hollow = MeshFixtures.hollowBox(size = 40f, height = 40f, cavity = 30f)
        val whole = MeshFixtures.signedVolume(hollow)

        for (cut in listOf(4f, 10f, 20f, 25f)) {
            for (axis in listOf(ModelPlacement.Axis.X, ModelPlacement.Axis.Z)) {
                val high = BedClipper.clipClosed(hollow, axis, cut, Half.HIGH)
                val low = BedClipper.clipClosed(hollow, axis, cut, Half.LOW)

                assertTrue("the high half on " + axis + " at " + cut + " is edge-manifold", MeshFixtures.isClosed(high))
                assertTrue("the low half on " + axis + " at " + cut + " is edge-manifold", MeshFixtures.isClosed(low))
                assertEquals(
                    "and the two halves are the whole hollow box on " + axis + " at " + cut,
                    whole,
                    MeshFixtures.signedVolume(high) + MeshFixtures.signedVolume(low),
                    1e-3,
                )
            }
        }
    }
}
