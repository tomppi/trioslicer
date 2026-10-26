package com.tomppi.enderslicer.viewer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The face the user picked, as a per-triangle tint the plate's own renderer draws.
 *
 * This is the paint buffer's shape, for a different fact: **one byte per scene vertex** - the
 * three vertices of a triangle all carry the same value - where 0 is the part's own shaded colour
 * and 255 is the selected face's amber tint. The mesh shader mixes the tint in by that value, so
 * the face is tinted in the picture the user is looking at rather than described beside it, and
 * the same picture is what a plate render carries to the agent.
 *
 * It is deliberately **not** the paint buffer, and it never touches it: selecting fills this
 * buffer only, and the support paint's per-triangle colours live in [PaintColorBuffer] and stay
 * exactly as they were. A painted triangle under the selected face keeps its paint and shades
 * with the tint mixed over it; a selection never writes a paint state, and painting never tints.
 *
 * Not thread-safe: the viewer owns one instance and fills it on the GL thread.
 */
class SelectionTintBuffer(val triangleCount: Int) {
    init {
        require(triangleCount >= 0) { "Triangle count must not be negative" }
    }

    /** One value per scene vertex: 0 for the part, [TINT] for a triangle of the selected face. */
    private val strength = ByteArray(triangleCount * VERTICES_PER_TRIANGLE)

    /** The same bytes, laid out the way a GL attribute read wants them. */
    private val data: ByteBuffer = ByteBuffer
        .allocateDirect(strength.size)
        .order(ByteOrder.nativeOrder())

    /** How many triangles the current selection tints. */
    var tintedTriangles: Int = 0
        private set

    val hasSelection: Boolean get() = tintedTriangles > 0

    val byteCount: Int get() = strength.size

    /** The buffer the renderer uploads; its position is always 0 for the caller. */
    val buffer: ByteBuffer get() = data

    /** How much of the tint one triangle of the scene carries: 0 or [TINT]. */
    fun tintAt(triangle: Int): Int =
        if (triangle in 0 until triangleCount) strength[triangle * VERTICES_PER_TRIANGLE].toInt() and 0xFF else 0

    fun isTinted(triangle: Int): Boolean = tintAt(triangle) != 0

    /**
     * Makes the selection exactly [triangles] - scene triangle indices.
     *
     * Anything outside the scene is ignored rather than wrapped around it: a caller that mixes
     * up a body's own index with a scene index tints a triangle of some other object otherwise,
     * which is exactly the tint-on-the-wrong-thing this must never do.
     */
    fun select(triangles: IntArray) {
        if (strength.isNotEmpty()) java.util.Arrays.fill(strength, 0)
        tintedTriangles = 0
        for (triangle in triangles) {
            if (triangle < 0 || triangle >= triangleCount) continue
            val at = triangle * VERTICES_PER_TRIANGLE
            if (strength[at] == TINT) continue
            strength[at] = TINT
            strength[at + 1] = TINT
            strength[at + 2] = TINT
            tintedTriangles++
        }
        publish()
    }

    /** Drops the tint without touching anything else on the plate. */
    fun clear() {
        if (tintedTriangles == 0 && strength.all { it.toInt() == 0 }) {
            publish()
            return
        }
        java.util.Arrays.fill(strength, 0)
        tintedTriangles = 0
        publish()
    }

    private fun publish() {
        data.clear()
        data.put(strength)
        data.position(0)
    }

    companion object {
        /** The value a selected triangle's vertices carry; the shader's mix factor scales it. */
        const val TINT: Byte = 0xFF.toByte()

        private const val VERTICES_PER_TRIANGLE = 3
    }
}
