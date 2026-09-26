package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.annotation.Point3
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GizmoRibbonTest {
    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    @Test
    fun theBufferTheRendererAllocatesIsBigEnoughForEverySegment() {
        // The bug this pins: the scratch buffer was sized in the wrong units, so
        // the first frame with a gizmo up overflowed it and took the GL thread
        // down. Every group must expand completely into the capacity its own
        // vertex count asks for.
        val overlay = TransformGizmo.ringsOverlay(
            TransformGizmo.rings(Point3(0f, 0f, 0f), radiusMm = 40f),
        )

        overlay.groups.forEach { group ->
            val segments = group.vertices.size / 6
            val buffer = FloatBuffer.allocate(GizmoRibbon.capacityFloats(group.vertices.size))
            val written = GizmoRibbon.expand(
                vertices = group.vertices,
                mvp = identity,
                viewportWidth = 1080,
                viewportHeight = 2000,
                halfWidthPx = 4f,
                target = buffer,
            )
            assertEquals("every segment becomes a quad", segments * 6, written)
            assertTrue(written <= GizmoRibbon.capacityFloats(group.vertices.size))
        }
    }

    @Test
    fun aSegmentBecomesAQuadOfTheRequestedWidth() {
        // A horizontal segment at the origin, identity matrix, 1000 px viewport:
        // 10 px half width is 0.02 in NDC, so the quad reaches +/-0.02 in Y.
        val vertices = floatArrayOf(-0.5f, 0f, 0f, 0.5f, 0f, 0f)
        val buffer = FloatBuffer.allocate(GizmoRibbon.capacityFloats(vertices.size))

        val written = GizmoRibbon.expand(vertices, identity, 1000, 1000, 10f, buffer)

        assertEquals(6, written)
        buffer.position(0)
        var maxY = 0f
        repeat(written) {
            buffer.get()
            maxY = maxOf(maxY, kotlin.math.abs(buffer.get()))
            buffer.get()
        }
        assertEquals(0.02f, maxY, 1e-4f)
    }

    @Test
    fun nothingIsDrawnForDegenerateOrHiddenSegments() {
        // Zero length.
        val degenerate = FloatBuffer.allocate(GizmoRibbon.capacityFloats(6))
        assertEquals(0, GizmoRibbon.expand(floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f), identity, 1080, 2000, 4f, degenerate))

        // Behind the eye: w = -z, so a point at z = 1 has a negative w.
        val behind = identity.copyOf().also { it[11] = -1f; it[15] = 0f }
        val hidden = FloatBuffer.allocate(GizmoRibbon.capacityFloats(6))
        assertEquals(0, GizmoRibbon.expand(floatArrayOf(0f, 0f, 1f, 1f, 0f, 1f), behind, 1080, 2000, 4f, hidden))
    }

    @Test
    fun anUndersizedBufferDrawsLessInsteadOfCrashing() {
        val overlay = TransformGizmo.ringsOverlay(
            TransformGizmo.rings(Point3(0f, 0f, 0f), radiusMm = 40f),
        )
        val group = overlay.groups.first()
        // Room for exactly one quad.
        val tight = FloatBuffer.allocate(GizmoRibbon.SEGMENT_FLOATS)

        val written = GizmoRibbon.expand(group.vertices, identity, 1080, 2000, 4f, tight)

        assertEquals(6, written)
    }

    @Test
    fun capacityFollowsTheVertexCount() {
        assertEquals(0, GizmoRibbon.capacityFloats(0))
        assertEquals(GizmoRibbon.SEGMENT_FLOATS, GizmoRibbon.capacityFloats(6))
        assertEquals(GizmoRibbon.SEGMENT_FLOATS * 2, GizmoRibbon.capacityFloats(12))
        assertEquals(GizmoRibbon.capacityFloats(12) * 4, GizmoRibbon.capacityBytes(12))
    }
}
