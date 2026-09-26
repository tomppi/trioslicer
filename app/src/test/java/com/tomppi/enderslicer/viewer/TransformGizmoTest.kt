package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.annotation.Point3
import com.tomppi.enderslicer.model.ModelPlacement
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformGizmoTest {
    private val pivot = Point3(20f, 30f, 0f)

    @Test
    fun eachRingLiesInThePlaneItsAxisTurnsAround() {
        val rings = TransformGizmo.rings(pivot, radiusMm = 40f)

        assertEquals(3, rings.size)
        assertEquals(setOf(ModelPlacement.Axis.X, ModelPlacement.Axis.Y, ModelPlacement.Axis.Z), rings.map { it.axis }.toSet())
        rings.forEach { ring ->
            val points = ring.points.size / 3
            assertTrue("a ring needs enough points to look round", points >= 32)
            for (index in 0 until points) {
                val x = ring.points[index * 3]
                val y = ring.points[index * 3 + 1]
                val z = ring.points[index * 3 + 2]
                when (ring.axis) {
                    // A rotation about X sweeps the YZ plane, so X is constant.
                    ModelPlacement.Axis.X -> {
                        assertEquals(pivot.x, x, 1e-4f)
                        assertEquals(40f, distance(y - pivot.y, z - pivot.z), 1e-3f)
                    }
                    ModelPlacement.Axis.Y -> {
                        assertEquals(pivot.y, y, 1e-4f)
                        assertEquals(40f, distance(x - pivot.x, z - pivot.z), 1e-3f)
                    }
                    ModelPlacement.Axis.Z -> {
                        assertEquals(pivot.z, z, 1e-4f)
                        assertEquals(40f, distance(x - pivot.x, y - pivot.y), 1e-3f)
                    }
                }
            }
        }
    }

    @Test
    fun eachRingRunsTheWayItsAxisTurns() {
        // The next point along a ring has to be where a small positive rotation
        // takes the current one. The renderer takes a ring's own direction as the
        // direction of a positive turn, so a ring wound the other way turns
        // against the finger - which is how the Y axis behaved.
        val rings = TransformGizmo.rings(pivot, radiusMm = 40f)
        rings.forEach { ring ->
            val current = floatArrayOf(ring.points[0], ring.points[1], ring.points[2])
            val next = floatArrayOf(ring.points[3], ring.points[4], ring.points[5])
            val turned = rotateAbout(ring.axis, current, 0.05)
            val step = dot(
                next[0] - current[0], next[1] - current[1], next[2] - current[2],
                turned[0] - current[0], turned[1] - current[1], turned[2] - current[2],
            )
            assertTrue("ring " + ring.axis + " runs against its own turn", step > 0f)
        }
    }

    /** The same convention ModelPlacement.rotated uses, stated independently. */
    private fun rotateAbout(axis: ModelPlacement.Axis, point: FloatArray, radians: Double): FloatArray {
        val c = kotlin.math.cos(radians).toFloat()
        val s = kotlin.math.sin(radians).toFloat()
        val x = point[0]
        val y = point[1]
        val z = point[2]
        return when (axis) {
            ModelPlacement.Axis.X -> floatArrayOf(x, y * c - z * s, y * s + z * c)
            ModelPlacement.Axis.Y -> floatArrayOf(x * c + z * s, y, -x * s + z * c)
            ModelPlacement.Axis.Z -> floatArrayOf(x * c - y * s, x * s + y * c, z)
        }
    }

    private fun dot(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float =
        ax * bx + ay * by + az * bz

    @Test
    fun ringsBecomePairedSegmentsInTheAxisColours() {
        val overlay = TransformGizmo.ringsOverlay(TransformGizmo.rings(pivot, radiusMm = 25f))

        assertEquals(3, overlay.groups.size)
        val x = overlay.groups.single { it.color.contentEquals(TransformGizmo.X_COLOR) }
        val segmentVertices = x.vertices.size / 3
        assertEquals("GL_LINES wants pairs", 0, segmentVertices % 2)
        assertTrue(segmentVertices >= 64)
        // The last segment ends where the first one starts: that closes the loop.
        val firstPairStart = floatArrayOf(x.vertices[0], x.vertices[1], x.vertices[2])
        val lastPairEnd = floatArrayOf(
            x.vertices[x.vertices.size - 3],
            x.vertices[x.vertices.size - 2],
            x.vertices[x.vertices.size - 1],
        )
        assertEquals(firstPairStart[0], lastPairEnd[0], 1e-3f)
        assertEquals(firstPairStart[1], lastPairEnd[1], 1e-3f)
        assertEquals(firstPairStart[2], lastPairEnd[2], 1e-3f)
    }

    @Test
    fun eachArrowReachesItsAxisByItsLength() {
        val arrows = TransformGizmo.arrows(pivot, lengthMm = 50f)

        assertEquals(3, arrows.size)
        arrows.forEach { arrow ->
            var reached = 0f
            val points = arrow.vertices.size / 3
            for (index in 0 until points) {
                val along = when (arrow.axis) {
                    ModelPlacement.Axis.X -> arrow.vertices[index * 3] - pivot.x
                    ModelPlacement.Axis.Y -> arrow.vertices[index * 3 + 1] - pivot.y
                    ModelPlacement.Axis.Z -> arrow.vertices[index * 3 + 2] - pivot.z
                }
                reached = maxOf(reached, along)
            }
            assertEquals(50f, reached, 1e-3f)
        }
    }

    @Test
    fun theGizmoIsSizedFromTheModel() {
        val small = MeshBounds(0f, 0f, 0f, 20f, 20f, 5f)
        val large = MeshBounds(0f, 0f, 0f, 200f, 200f, 100f)
        assertTrue(TransformGizmo.ringRadiusMm(large) > TransformGizmo.ringRadiusMm(small))
        assertTrue(TransformGizmo.arrowLengthMm(large) > TransformGizmo.arrowLengthMm(small))
        // A footprint of nothing still gets something touchable.
        assertTrue(TransformGizmo.ringRadiusMm(null) > 0f)
        assertTrue(TransformGizmo.arrowLengthMm(null) > 0f)
    }

    @Test
    fun nonsenseDimensionsFallBackToATouchableGizmo() {
        val flat = MeshBounds(0f, 0f, 0f, 0f, 0f, 0f)
        assertTrue(TransformGizmo.ringRadiusMm(flat) > 0f)
        assertTrue(TransformGizmo.arrowLengthMm(flat) > 0f)

        val failure = runCatching { TransformGizmo.rings(pivot, radiusMm = 0f) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IllegalArgumentException)
    }

    private fun distance(a: Float, b: Float): Float = sqrt(a * a + b * b)
}
