package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The room a place on the seam gives the shape put in it.
 *
 * This is the number the library hook's own fit check measures its shape
 * against, so it is pinned in the meshes rather than on a drawing: the run the
 * edge gives through the anchor, corner to corner, and the face's own depth
 * across it, inboard of the rim until the part's edge or a feature ends it.
 */
class SnapSeamTest {
    @Test
    fun theRoomIsTheRunsOwnLengthAndTheFacesOwnDepth() {
        val half = SnapFitHalf.inPlace(MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 20f, "low"), 20f)
        val room = requireNotNull(roomOf(half, Vec3(20f, 20f, 20f)))

        assertEquals("the edge runs the whole way round the run", 40f, room.alongMm, 0.1f)
        assertEquals("and the face is the whole depth across it", 40f, room.acrossMm, 0.1f)
        assertTrue("which is a real place to stand a shape on", room.isUsable)
        assertEquals("the run lies in the seam's own plane", 0f, room.alongDirection.z, 1e-4f)
        assertEquals("and so does the way the material lies", 0f, room.inboardDirection.z, 1e-4f)
    }

    /**
     * The room is the PLACE's, not the part's average: an L in section gives the
     * long arm a 40 mm face and the short one a 20 mm face, on one seam.
     */
    @Test
    fun theRoomFollowsThePlaceAlongTheSeam() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val half = SnapFitHalf.inPlace(lShapedHalf(), 20f)

        val onTheLongArm = requireNotNull(roomOf(half, Vec3(10f, 0f, 20f)))
        assertEquals("the long arm's run is its own edge", 40f, onTheLongArm.alongMm, 0.1f)
        assertEquals("and its depth is the whole arm", 40f, onTheLongArm.acrossMm, 0.1f)

        val onTheShortArm = requireNotNull(roomOf(half, Vec3(3f, 30f, 20f)))
        assertEquals("the short arm's run is the outer edge too", 40f, onTheShortArm.alongMm, 0.1f)
        assertEquals("but its face is only the arm's own 20 mm", 20f, onTheShortArm.acrossMm, 0.1f)
    }

    @Test
    fun aHalfWithNoCapAtTheSeamHasNoRoom() {
        // A block that stops well below the plane the joint is built on: there
        // is no material at the seam to measure a room on.
        val short = SnapFitHalf.inPlace(MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 10f, name = "too short"), 20f)
        assertNull(SnapSeam.room(short, Vec3(0f, 0f, 1f), Vec3(20f, 20f, 20f)))
    }

    @Test
    fun theRoomIsReadInTheHalvesOwnFrameNotThePlates() {
        // The same half carried 10 mm across the plate by the packer: the room is
        // read in the half's OWN coordinates, so where the plate has it cannot
        // change what the place gives.
        val mesh = MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 20f, name = "part lower")
        val shifted = MeshFixtures.box(10f, 10f, 0f, 50f, 50f, 20f, name = "part lower")
        val placed = SnapFitHalf.placed(mesh, shifted, 20f)

        val room = requireNotNull(roomOf(placed, Vec3(20f, 20f, 20f)))
        assertEquals("the same run, wherever the plate has the half", 40f, room.alongMm, 0.1f)
        assertEquals("and the same depth", 40f, room.acrossMm, 0.1f)
    }

    /** The room at [anchorMm] on [half], up the split's own axis. */
    private fun roomOf(half: SnapFitHalf, anchorMm: Vec3): SeamRoom? =
        SnapSeam.room(half, Vec3(0f, 0f, 1f), anchorMm)

    /** An L in section: a 40 x 20 arm and a 20 x 20 one, meeting the seam at Z = 20. */
    private fun lShapedHalf(): StlMesh {
        val long = MeshFixtures.box(0f, 0f, 0f, 40f, 20f, 20f, "low long arm")
        val short = MeshFixtures.box(0f, 20f, 0f, 20f, 40f, 20f, "low short arm")
        val union = MeshBoolean.union(long, short)
        return (union as? MeshBoolean.Result.Success)?.mesh
            ?: error("the L could not be built: " + (union as MeshBoolean.Result.Failure).reason)
    }
}
