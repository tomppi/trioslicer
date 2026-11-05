package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PartTopoResultPreparerTest {
    @Test
    fun restoresDisplayedCenterAndBaseWithoutReapplyingAffine() {
        val analyzedDisplayed = mesh(
            name = "rotated-and-placed.stl",
            vertices = floatArrayOf(
                72f, 41f, 3.5f,
                88f, 41f, 3.5f,
                80f, 59f, 13.5f,
            ),
            bounds = MeshBounds(72f, 41f, 3.5f, 88f, 59f, 13.5f),
        )
        val filaSimLocalResult = mesh(
            name = "optimized.stl",
            vertices = floatArrayOf(
                -6f, -8f, 0f,
                6f, -8f, 0f,
                0f, 8f, 8f,
            ),
            bounds = MeshBounds(-6f, -8f, 0f, 6f, 8f, 8f),
        )

        val placement = PartTopoResultPreparer.placementFor(analyzedDisplayed)
        val transformed = placement.transformed(filaSimLocalResult)

        assertEquals(ModelPlacement.IDENTITY, placement.linear)
        assertEquals(80.0, placement.centerXmm, 0.0)
        assertEquals(50.0, placement.centerYmm, 0.0)
        assertEquals(3.5, placement.baseZmm, 0.0)
        assertEquals("filaSim Part Topo result", placement.source)
        assertEquals(80f, transformed.bounds.centerX, 1e-5f)
        assertEquals(50f, transformed.bounds.centerY, 1e-5f)
        assertEquals(3.5f, transformed.bounds.minZ, 1e-5f)
        assertEquals(ModelPlacement.IDENTITY, transformed.slicingTransform?.linear)
    }

    @Test
    fun aBodyAlreadyAtThePlacementPointIsAnIdentityPlacementNotARefusal() {
        // The engine's recentered result can arrive exactly at the placement point (a
        // centered-bed profile at default placement, or a symmetric result): the
        // near-identity fast path hands the parser's mesh back unchanged, with no
        // slicingTransform - and that placement IS the identity, not a missing one.
        val analyzedDisplayed = mesh(
            name = "rotated-and-placed.stl",
            vertices = floatArrayOf(
                72f, 41f, 3.5f,
                88f, 41f, 3.5f,
                80f, 59f, 13.5f,
            ),
            bounds = MeshBounds(72f, 41f, 3.5f, 88f, 59f, 13.5f),
        )
        val alreadyPlaced = mesh(
            name = "optimized.stl",
            vertices = floatArrayOf(
                72f, 41f, 3.5f,
                88f, 41f, 3.5f,
                80f, 59f, 13.5f,
            ),
            bounds = MeshBounds(72f, 41f, 3.5f, 88f, 59f, 13.5f),
        )

        val placement = PartTopoResultPreparer.placementFor(analyzedDisplayed)
        val transformed = placement.transformed(alreadyPlaced)

        assertSame("the fast path hands the mesh back unchanged", alreadyPlaced, transformed)
        assertNull("the fast path carries no slicing transform", transformed.slicingTransform)
        val transform = PartTopoResultPreparer.slicingTransformOf(transformed)
        assertEquals(ModelPlacement.IDENTITY, transform.linear)
        assertEquals(0.0, transform.translationXmm, 0.0)
        assertEquals(0.0, transform.translationYmm, 0.0)
        assertEquals(0.0, transform.translationZmm, 0.0)
    }

    private fun mesh(name: String, vertices: FloatArray, bounds: MeshBounds): StlMesh {
        val interleaved = FloatArray(18)
        repeat(3) { vertex ->
            val source = vertex * 3
            val target = vertex * 6
            interleaved[target] = vertices[source]
            interleaved[target + 1] = vertices[source + 1]
            interleaved[target + 2] = vertices[source + 2]
            interleaved[target + 5] = 1f
        }
        return StlMesh(
            displayName = name,
            interleavedVertices = VertexData.fromArray(interleaved),
            triangleCount = 1,
            bounds = bounds,
        )
    }
}
