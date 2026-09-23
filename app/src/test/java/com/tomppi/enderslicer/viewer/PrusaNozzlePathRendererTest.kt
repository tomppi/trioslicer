package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.engine.PrusaNozzlePath
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freeze the ribbon geometry of the from-scratch Prusa renderer.
 *
 * Two regressions were shipped and caught only on device:
 *  1. addRibbonMove paired the end corners with swapped signs (c = end - q,
 *     d = end + q), twisting every ribbon box into a crossed prism.
 *  2. boundaryNormal blended the miter across non-contiguous neighbours
 *     (travel moves / the next bead), skewing bead end caps, plus fell back
 *     to an arbitrary axis at the path start, path end and travel-adjacent
 *     boundaries.
 *
 * These tests read the built vertex stream and assert the intended geometry:
 * straight beads are prismatic, curved beads are seam-free, and bead ends are
 * capped perpendicular to their own segment.
 */
class PrusaNozzlePathRendererTest {

    private val width = 0.44f
    private val height = 0.2f
    private val half = width * 0.5f

    private fun path(vararg moves: FloatArray): PrusaNozzlePath {
        val count = moves.size
        val source = FloatArray(count * PrusaNozzlePath.VALUES_PER_MOVE)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var extrusions = 0
        var travels = 0
        moves.forEachIndexed { i, m ->
            val o = i * PrusaNozzlePath.VALUES_PER_MOVE
            m.copyInto(source, o)
            source[o + PrusaNozzlePath.SPEED] = 150f
            val kind = source[o + PrusaNozzlePath.KIND]
            if (kind == PrusaNozzlePath.Kind.EXTRUSION.code) extrusions++ else travels++
            minX = minOf(minX, source[o + PrusaNozzlePath.X1], source[o + PrusaNozzlePath.X2])
            minY = minOf(minY, source[o + PrusaNozzlePath.Y1], source[o + PrusaNozzlePath.Y2])
            minZ = minOf(minZ, source[o + PrusaNozzlePath.Z1], source[o + PrusaNozzlePath.Z2])
            maxX = maxOf(maxX, source[o + PrusaNozzlePath.X1], source[o + PrusaNozzlePath.X2])
            maxY = maxOf(maxY, source[o + PrusaNozzlePath.Y1], source[o + PrusaNozzlePath.Y2])
            maxZ = maxOf(maxZ, source[o + PrusaNozzlePath.Z1], source[o + PrusaNozzlePath.Z2])
        }
        return PrusaNozzlePath(
            moves = source,
            sourceMoveIndices = IntArray(count) { it },
            minX = minX, minY = minY, minZ = minZ,
            maxX = maxX, maxY = maxY, maxZ = maxZ,
            extrusionMoveCount = extrusions,
            travelMoveCount = travels,
            sourceMoveCount = count,
            layerCount = 1,
            truncated = false,
        )
    }

    private fun extrusionMove(sx: Float, sy: Float, ex: Float, ey: Float): FloatArray = floatArrayOf(
        sx, sy, 0f, ex, ey, 0f, 150f, PrusaNozzlePath.Kind.EXTRUSION.code, width, height,
    )

    private fun travelMove(sx: Float, sy: Float, ex: Float, ey: Float): FloatArray = floatArrayOf(
        sx, sy, 0f, ex, ey, 0f, 150f, PrusaNozzlePath.Kind.TRAVEL.code, 0f, 0f,
    )

    private fun positions(renderer: PrusaNozzlePathRenderer): FloatArray {
        val buffer = renderer.ribbonPositions ?: throw AssertionError("ribbonPositions not built")
        val result = FloatArray(buffer.limit())
        buffer.position(0)
        buffer.get(result)
        return result
    }

    private fun vert(v: FloatArray, window: Int, vertex: Int): FloatArray =
        v.copyOfRange(window * 18 * 3 + vertex * 3, window * 18 * 3 + vertex * 3 + 3)

    private fun assertNear(expected: FloatArray, actual: FloatArray, eps: Float = 1e-3f, message: String) {
        for (i in expected.indices) {
            assertTrue(
                message + " coord " + i + ": expected " + expected[i] + " got " + actual[i],
                kotlin.math.abs(expected[i] - actual[i]) <= eps,
            )
        }
    }

    @Test
    fun straightChainIsPrismaticNotTwisted() {
        val renderer = PrusaNozzlePathRenderer()
        renderer.buildPathBuffers(path(
            extrusionMove(0f, 0f, 1f, 0f),
            extrusionMove(1f, 0f, 2f, 0f),
            extrusionMove(2f, 0f, 3f, 0f),
            extrusionMove(3f, 0f, 4f, 0f),
        ))
        val v = positions(renderer)
        // Window 0 covers move 0: from (0,0) to (1,0), width 0.44, height 0.2.
        val aTop = vert(v, 0, 0)
        val bTop = vert(v, 0, 1)
        val cTop = vert(v, 0, 2)
        val dBot = vert(v, 0, 7)
        val aBot = vert(v, 0, 6)
        // Start cap: a/b are the two sides, exactly half-width apart.
        assertNear(floatArrayOf(0f, -half, height), aTop, 1e-4f, "aTop")
        assertNear(floatArrayOf(0f, half, height), bTop, 1e-4f, "bTop")
        // Top face quad must be a rectangle: b-a is the width axis, c-b the
        // segment axis, and they must be orthogonal (twisted pairing makes
        // c-b diagonal and the dot product non-zero).
        val ab = floatArrayOf(bTop[0] - aTop[0], bTop[1] - aTop[1])
        val cb = floatArrayOf(cTop[0] - bTop[0], cTop[1] - bTop[1])
        val dot = ab[0] * cb[0] + ab[1] * cb[1]
        assertTrue("top face twisted: b-a . c-b = " + dot, kotlin.math.abs(dot) < 1e-4f)
        // Same side: the left face runs a -> d exactly parallel to the segment
        // (no lateral drift). A twisted prism pairs a with the +q corner and
        // would drift by a full width.
        val ad = floatArrayOf(dBot[0] - aBot[0], dBot[1] - aBot[1], dBot[2] - aBot[2])
        assertTrue("left face crosses the bead: d-a lateral component " + ad[1], kotlin.math.abs(ad[1]) < 1e-4f)
        assertNear(floatArrayOf(1f, 0f, 0f), ad, 1e-4f, "left face spans one segment")
    }

    @Test
    fun curvedChainWindowsShareExactCorners() {
        val moves = ArrayList<FloatArray>()
        val radius = 10f
        val stepDeg = 5.0
        val count = 14
        for (i in 0 until count) {
            val a0 = Math.toRadians(i * stepDeg)
            val a1 = Math.toRadians((i + 1) * stepDeg)
            moves.add(extrusionMove(
                (radius * cos(a0)).toFloat(), (radius * sin(a0)).toFloat(),
                (radius * cos(a1)).toFloat(), (radius * sin(a1)).toFloat(),
            ))
        }
        val renderer = PrusaNozzlePathRenderer()
        renderer.buildPathBuffers(path(*moves.toTypedArray()))
        val v = positions(renderer)
        val windows = v.size / (18 * 3)
        assertEquals(count, windows)
        for (i in 0 until windows - 1) {
            // Window i end corners (c = v2, d = v4/v7) must be identical to
            // window i+1 start corners (b = v1, a = v0/v6).
            assertNear(vert(v, i + 1, 1), vert(v, i, 2), 1e-4f, "window " + i + " c -> next b")
            assertNear(vert(v, i + 1, 0), vert(v, i, 5), 1e-4f, "window " + i + " d -> next a")
            assertNear(vert(v, i + 1, 12), vert(v, i, 13), 1e-4f, "window " + i + " cBot -> next bBot")
            assertNear(vert(v, i + 1, 6), vert(v, i, 7), 1e-4f, "window " + i + " dBot -> next aBot")
        }
    }

    @Test
    fun sideNormalsInterpolateContinuouslyAcrossWindows() {
        val moves = ArrayList<FloatArray>()
        val radius = 10f
        val stepDeg = 5.0
        val count = 14
        for (i in 0 until count) {
            val a0 = Math.toRadians(i * stepDeg)
            val a1 = Math.toRadians((i + 1) * stepDeg)
            moves.add(extrusionMove(
                (radius * cos(a0)).toFloat(), (radius * sin(a0)).toFloat(),
                (radius * cos(a1)).toFloat(), (radius * sin(a1)).toFloat(),
            ))
        }
        val renderer = PrusaNozzlePathRenderer()
        renderer.buildPathBuffers(path(*moves.toTypedArray()))
        val normals = FloatArray(renderer.ribbonNormals!!.limit())
        renderer.ribbonNormals!!.position(0)
        renderer.ribbonNormals!!.get(normals)
        val windows = normals.size / (18 * 3)
        assertEquals(count, windows)
        for (i in 0 until windows - 1) {
            // Left face: end normal of window i (d vertex 7) must equal the
            // start normal of window i+1 (a vertex 6) - continuous shading.
            assertNear(range(normals, i, 7), range(normals, i + 1, 6), 1e-4f, "left n " + i)
            assertNear(range(normals, i, 13), range(normals, i + 1, 12), 1e-4f, "right n " + i)
        }
    }

    private fun range(data: FloatArray, window: Int, vertex: Int): FloatArray =
        data.copyOfRange(window * 18 * 3 + vertex * 3, window * 18 * 3 + vertex * 3 + 3)

    @Test
    fun beadEndsCappedByTheirOwnDirectionAcrossTravel() {
        val renderer = PrusaNozzlePathRenderer()
        // Bead A along +x, travel jump, bead B along +y.
        renderer.buildPathBuffers(path(
            extrusionMove(0f, 0f, 1f, 0f),
            extrusionMove(1f, 0f, 2f, 0f),
            extrusionMove(2f, 0f, 3f, 0f),
            travelMove(3f, 0f, 4f, 3f),
            extrusionMove(4f, 3f, 4f, 4f),
            extrusionMove(4f, 4f, 4f, 5f),
            extrusionMove(4f, 5f, 4f, 6f),
        ))
        val v = positions(renderer)
        val windowCount = v.size / (18 * 3)
        assertEquals(6, windowCount) // 3 A + 3 B, travel draws no ribbon
        // A's LAST window (index 2): move (2,0)->(3,0); end cap must be
        // perpendicular to its own segment: offset by perp(1,0)=(0,1).
        assertNear(floatArrayOf(3f, half, height), vert(v, 2, 2), 1e-4f, "A end c")
        assertNear(floatArrayOf(3f, -half, height), vert(v, 2, 5), 1e-4f, "A end d")
        assertNear(floatArrayOf(3f, -half, 0f), vert(v, 2, 7), 1e-4f, "A end dBot")
        // B's FIRST window (index 3): move (4,3)->(4,4); start cap must be
        // perpendicular to perp(0,1)=(-1,0): a = s - p = (4.22, 3), b = (3.78, 3).
        assertNear(floatArrayOf(4f + half, 3f, height), vert(v, 3, 0), 1e-4f, "B start a")
        assertNear(floatArrayOf(4f - half, 3f, height), vert(v, 3, 1), 1e-4f, "B start b")
        assertNear(floatArrayOf(4f + half, 3f, 0f), vert(v, 3, 6), 1e-4f, "B start aBot")
    }
}
