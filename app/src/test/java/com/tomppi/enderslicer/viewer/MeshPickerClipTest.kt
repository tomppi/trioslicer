package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pick against a cut preview.
 *
 * The phase-one gap this closes: the preview is a shader clip plane, so the
 * clipped-away half is invisible but still in the buffer, and a picking ray ran
 * straight through the visible half and answered with a point on the half
 * standing behind it. The clip now belongs to the pick, and to the object being
 * cut alone - a neighbour on the plate is never hidden by somebody else's
 * plane.
 */
class MeshPickerClipTest {
    @Test
    fun theClipHidesTheTrianglesOnTheDiscardedSide() {
        // A cube from Z = 0 to 10 with its top face at Z = 10: besides that
        // face, only the corners exactly on the plane are on it, and a triangle
        // is hidden only when every corner is beyond - which is the shader's own
        // rule, one discard per fragment.
        val cube = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)
        val clip = MeshPicker.PickClip(ModelPlacement.Axis.Z, 5f)

        assertTrue("the top face is past the plane", clip.hides(cube.interleavedVertices, MeshFixtures.TOP * 2))
        assertFalse(
            "while the bottom face is not",
            clip.hides(cube.interleavedVertices, MeshFixtures.BOTTOM * 2),
        )
        assertFalse(
            "and a side wall that reaches down past the plane is the visible half's own wall",
            clip.hides(cube.interleavedVertices, MeshFixtures.FRONT * 2),
        )
        assertTrue(
            "and a face sitting exactly on the plane is on the discarded side of it",
            MeshPicker.PickClip(ModelPlacement.Axis.Z, 10f)
                .hides(cube.interleavedVertices, MeshFixtures.TOP * 2),
        )
    }

    @Test
    fun aPickSeesThroughAClippedHalfToWhateverStandsBehindIt() {
        // The phase-one gap in one scene: two boxes stacked along Z, the upper
        // one being cut at their seam. Without the clip the ray answers with the
        // upper box's top - geometry that is not on the screen - and with it the
        // answer is the lower box, which is what the user can actually see.
        val lower = MeshFixtures.box(0f, 0f, 0f, 20f, 20f, 10f)
        val upper = MeshFixtures.box(0f, 0f, 10f, 20f, 20f, 20f)
        val scene = sceneOf(listOf(lower, upper))
        val clip = MeshPicker.PickClip(ModelPlacement.Axis.Z, 10f)

        val openHit = scene.hierarchy.raycast(10f, 10f, 50f, 0f, 0f, -1f)
        assertNotNull("with no cut, the nearest surface is the upper box's top", openHit)
        assertEquals(20f, openHit!!.z, 1e-4f)

        val unclipped = scene.hierarchy.raycast(10f, 10f, 50f, 0f, 0f, -1f, clip)
        assertEquals("a clip with no object named applies to none of them", 20f, unclipped!!.z, 1e-4f)

        val clipped = scene.hierarchy.raycast(10f, 10f, 50f, 0f, 0f, -1f, clip, clipObject = 1, owners = scene.owners)
        assertNotNull("the object being cut is hidden where the plane discards it", clipped)
        assertEquals("so the ray reaches the half that is still there", 10f, clipped!!.z, 1e-4f)
    }

    @Test
    fun theClipAppliesToTheObjectBeingCutAndNoOther() {
        val cube = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)
        val hierarchy = MeshBvh.build(cube)
        val clip = MeshPicker.PickClip(ModelPlacement.Axis.Z, 5f)
        val owners = IntArray(cube.triangleCount)

        val hit = hierarchy.raycast(5f, 5f, 50f, 0f, 0f, -1f, clip, clipObject = 1, owners = owners)

        assertNotNull("the plane is the object being cut's, not the plate's", hit)
        assertEquals("so an object that is not being cut still answers", 10f, hit!!.z, 1e-4f)
    }

    /** A plate concatenated the way the renderer does, with its triangle owners. */
    private class Scene(val mesh: StlMesh, val hierarchy: MeshBvh, val owners: IntArray)

    private fun sceneOf(meshes: List<StlMesh>): Scene {
        val builder = MeshSolidBuilder("Build plate")
        val owners = ArrayList<Int>(meshes.sumOf { it.triangleCount })
        meshes.forEachIndexed { index, mesh ->
            for (triangle in 0 until mesh.triangleCount) {
                builder.addInterleaved(mesh.interleavedVertices, triangle)
                owners.add(index)
            }
        }
        val scene = builder.build()
        return Scene(scene, MeshBvh.build(scene), owners.toIntArray())
    }

    @Test
    fun aPlaneTheModelNeverReachesHidesNothing() {
        val cube = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)
        val clip = MeshPicker.PickClip(ModelPlacement.Axis.Z, 100f)

        val hidden = (0 until cube.triangleCount).count { clip.hides(cube.interleavedVertices, it) }
        assertEquals("every triangle is on the visible side", 0, hidden)
    }
}
