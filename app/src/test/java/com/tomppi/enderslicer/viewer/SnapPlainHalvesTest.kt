package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Putting the plain halves back, at the geometry level.
 *
 * Deleting every joint and applying runs no boolean at all: the pair's own
 * halves are staged where they stand, which is what makes the source the one
 * stage of record - and it is why the same joints applied twice cannot stack a
 * second beam on the first. This pins the property that path depends on, with no
 * engine in the loop: staging a pair's own halves through the placements the
 * plate has them at returns exactly those halves, in the frame the joint builder
 * reads.
 */
class SnapPlainHalvesTest {
    @Test
    fun stagingThePairsOwnHalvesReturnsThemUnchanged() {
        val lowOwn = MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 20f, name = "part lower")
        val highOwn = MeshFixtures.box(0f, 0f, 20f, 40f, 40f, 40f, name = "part upper")
        // The plate as the packer leaves it: each half dropped onto the bed, the high one
        // slid along X, so the two mating planes no longer sit on one coordinate.
        val lowFit = SnapFitHalf.placed(lowOwn, shifted(lowOwn, 0f, 0f, -20f), 20f)
        val highFit = SnapFitHalf.placed(highOwn, shifted(highOwn, 60f, 0f, -20f), 20f)

        val staged = SnapApply.stage(
            lowHalf = lowFit,
            highHalf = highFit,
            lowMesh = lowFit.mesh,
            highMesh = highFit.mesh,
            lowName = "part lower",
            highName = "part upper",
        )

        assertEquals("the low half comes back at the source's own floor", 0f, staged.lowMesh.bounds.minZ, 1e-3f)
        assertEquals("up to its mating face", 20f, staged.lowMesh.bounds.maxZ, 1e-3f)
        assertEquals("the high half likewise", 20f, staged.highMesh.bounds.minZ, 1e-3f)
        assertEquals(40f, staged.highMesh.bounds.maxZ, 1e-3f)
        assertEquals(
            "the packer's sideways separation is not carried into the pair's frame",
            lowOwn.bounds.minX,
            staged.lowMesh.bounds.minX,
            1e-3f,
        )
        assertEquals(
            "and every triangle the source had is still there",
            lowOwn.triangleCount,
            staged.lowMesh.triangleCount,
        )
        assertEquals(highOwn.triangleCount, staged.highMesh.triangleCount)
        assertEquals("the face the pair carries is the one it was split at", 20f, staged.lowFaceMm, 0f)
        assertEquals(20f, staged.highFaceMm, 0f)
        assertTrue(
            "and the pair meets, so the tool can be opened on it again",
            SnapFit.halvesMeet(
                SnapFitHalf.inPlace(staged.lowMesh, staged.lowFaceMm),
                SnapFitHalf.inPlace(staged.highMesh, staged.highFaceMm),
            ),
        )
    }

    /** [mesh] shifted, keeping its winding and its triangle count. */
    private fun shifted(mesh: StlMesh, dx: Float, dy: Float, dz: Float): StlMesh {
        val builder = MeshSolidBuilder(mesh.displayName)
        val vertices = mesh.interleavedVertices
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            builder.addTriangle(
                vertices[base] + dx, vertices[base + 1] + dy, vertices[base + 2] + dz,
                vertices[base + 6] + dx, vertices[base + 7] + dy, vertices[base + 8] + dz,
                vertices[base + 12] + dx, vertices[base + 13] + dy, vertices[base + 14] + dz,
            )
        }
        return builder.build()
    }
}
