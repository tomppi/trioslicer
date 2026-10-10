package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import kotlin.math.sqrt

/**
 * CPU ray-cast from a screen point against the displayed model triangles.
 *
 * The camera is not recomputed here. The renderer publishes the exact MVP it
 * drew the last frame with, and every ray and every projection goes through
 * that one matrix, so what the user sees and what the picker answers about
 * cannot disagree. Recomputing the fit in the picker did exactly that: its fit
 * came out at 485 mm where the frame was drawn at 493, and taps landed on
 * nothing - on the model, on the plate, on the joint being placed, and on
 * plain tap-to-select alike.
 *
 * The [MeshBvh] hierarchy is still built once per mesh, so a pick visits a
 * handful of nodes instead of running a triangle test per triangle in the
 * model. Picks run on the viewer's paint executor while the GL thread publishes
 * a new frame, so the frame is a volatile reference to an immutable holder, and
 * the mutable caches beside it are guarded by [lock].
 */
object MeshPicker {
    data class Hit(
        val triangleIndex: Int,
        val x: Float,
        val y: Float,
        val z: Float,
        /**
         * Which object of the plate the triangle belongs to.
         *
         * The picker is handed the whole plate as one mesh - every object's
         * triangles end to end - so the triangle index alone cannot say which
         * part was hit. Zero for a single-object plate, and for a hit built
         * anywhere the viewer's scene is not in hand.
         */
        val objectIndex: Int = 0,
    )

    /**
     * The half of a cut preview a pick must not see.
     *
     * The preview is a shader clip plane: fragments beyond [offsetMm] along
     * [axis] are discarded, so the model the user is looking at ends there.
     * The triangles are still in the buffer, and without this the picker ran a
     * ray through the visible half and answered with a point on the half that
     * was clipped away - a tap on the visible cut face landed behind the model.
     */
    data class PickClip(
        val axis: ModelPlacement.Axis,
        val offsetMm: Float,
    ) {
        /**
         * True when no corner of the triangle is left of the plane: everything
         * the triangle covers is at or beyond it, which is what the shader's
         * discard leaves nothing of.
         *
         * A triangle that straddles the plane is still half drawn, so it has to
         * stay pickable - the visible half's own walls are exactly those
         * triangles. The tolerance is what keeps a face sitting exactly on the
         * plane out of the hidden set, where float dust would otherwise put it.
         */
        fun hides(vertices: VertexData, triangle: Int): Boolean {
            // The scene's triangle soup: nine floats to a triangle, six to a
            // vertex, so a triangle's own corners start at triangle * 18.
            val base = triangle * FLOATS_PER_TRIANGLE
            val channel = when (axis) {
                ModelPlacement.Axis.X -> 0
                ModelPlacement.Axis.Y -> 1
                ModelPlacement.Axis.Z -> 2
            }
            var hidden = true
            for (corner in 0 until VERTICES_PER_TRIANGLE) {
                if (vertices[base + corner * FLOATS_PER_VERTEX + channel] < offsetMm - PLANE_EPSILON_MM) {
                    hidden = false
                    break
                }
            }
            return hidden
        }
    }

    /** A ray under a screen point, in model space. [dir] is unit length. */
    data class ScreenRay(
        val originX: Float,
        val originY: Float,
        val originZ: Float,
        val dirX: Float,
        val dirY: Float,
        val dirZ: Float,
    )

    /**
     * One drawn frame's camera: the MVP the plate's vertices were transformed
     * by, its inverse, and the viewport it was drawn into. Immutable, and
     * replaced whole, so a reader on another thread sees a consistent set.
     */
    private class Frame(
        val mvp: FloatArray,
        val inverse: FloatArray?,
        val viewportWidth: Float,
        val viewportHeight: Float,
    )

    @Volatile
    private var frame: Frame? = null

    private val lock = Any()

    private var cachedMesh: StlMesh? = null
    private var cachedBvh: MeshBvh? = null

    private val clipScratch = FloatArray(4)
    private val worldScratch = FloatArray(4)
    private val nearPoint = FloatArray(3)
    private val farPoint = FloatArray(3)
    private val direction = FloatArray(3)

    /**
     * Publishes the camera the frame was drawn with. Called by the renderer at
     * the end of its own matrix setup, on the GL thread.
     *
     * The arrays are copied: the renderer keeps using its scratch matrices for
     * the per-object draws, and a pick running at the same time must not read a
     * matrix half-way through being rewritten.
     */
    fun publish(mvp: FloatArray, viewportWidth: Int, viewportHeight: Int) {
        if (mvp.size != 16 || viewportWidth <= 0 || viewportHeight <= 0) return
        val copy = mvp.copyOf()
        frame = Frame(copy, invert(copy), viewportWidth.toFloat(), viewportHeight.toFloat())
    }

    /**
     * Forgets the drawn frame: the viewer is gone or is being rebuilt, so
     * there is no camera a pick could honestly use. The next draw publishes one.
     */
    fun clearFrame() {
        frame = null
    }

    /** Drops the per-mesh hierarchy; call when the model is replaced or released. */
    fun invalidate() {
        synchronized(lock) {
            cachedMesh = null
            cachedBvh = null
        }
    }

    /** True once a frame has been published; false before the first draw. */
    val hasFrame: Boolean get() = frame != null

    fun pick(
        mesh: StlMesh,
        screenX: Float,
        screenY: Float,
        clip: PickClip? = null,
        clipObject: Int = -1,
        owners: IntArray? = null,
    ): Hit? {
        synchronized(lock) {
            val drawn = frame ?: return null
            val hierarchy = hierarchyFor(mesh) ?: return null
            if (!fillRay(drawn, screenX, screenY)) return null
            val hit = hierarchy.raycast(
                nearPoint[0], nearPoint[1], nearPoint[2],
                direction[0], direction[1], direction[2],
                clip,
                clipObject,
                owners,
            ) ?: return null
            return Hit(hit.triangleIndex, hit.x, hit.y, hit.z)
        }
    }

    /**
     * The ray under a screen point, in model space.
     *
     * Exposed for callers that need a position *along* the ray rather than the
     * first surface hit: a depth-preserving annotation drag slides a point at a
     * fixed distance instead of intersecting the mesh.
     */
    fun ray(screenX: Float, screenY: Float): ScreenRay? {
        synchronized(lock) {
            val drawn = frame ?: return null
            if (!fillRay(drawn, screenX, screenY)) return null
            return ScreenRay(
                nearPoint[0], nearPoint[1], nearPoint[2],
                direction[0], direction[1], direction[2],
            )
        }
    }

    /**
     * Projects a model-space point to screen pixels, or null when it is behind
     * the camera.
     *
     * Uses the same matrix as [ray], so a handle projected to the screen and
     * the pick that placed it cannot disagree about where it is - which is what
     * makes grabbing a handle by touch land where the user sees it.
     */
    fun project(x: Float, y: Float, z: Float): FloatArray? {
        val drawn = frame ?: return null
        val mvp = drawn.mvp
        // Column-major: m[0..3] is column 0, so the translation is at 12..14.
        val w = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
        if (w <= 1e-6f) return null
        val cx = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
        val cy = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
        return floatArrayOf(
            (cx / w * 0.5f + 0.5f) * drawn.viewportWidth,
            (1f - (cy / w * 0.5f + 0.5f)) * drawn.viewportHeight,
        )
    }

    /**
     * How deep a plate point is: the w of its clip position, which for the
     * perspective the plate is drawn with is its distance along the view axis.
     *
     * Bigger is further from the eye. Taken from the same published matrix as
     * everything else here, so "is this handle in front of the model" is judged
     * against the frame on screen and not against a camera of the picker's own.
     */
    fun depthOf(x: Float, y: Float, z: Float): Float? {
        val mvp = frame?.mvp ?: return null
        return mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
    }

    /** Fills [nearPoint] and [direction] for a screen position on a drawn frame. */
    private fun fillRay(drawn: Frame, screenX: Float, screenY: Float): Boolean {
        val inverse = drawn.inverse ?: return false
        val ndcX = (2f * screenX) / drawn.viewportWidth - 1f
        val ndcY = 1f - (2f * screenY) / drawn.viewportHeight
        unprojectInto(inverse, ndcX, ndcY, -1f, nearPoint)
        unprojectInto(inverse, ndcX, ndcY, 1f, farPoint)

        val dx = farPoint[0] - nearPoint[0]
        val dy = farPoint[1] - nearPoint[1]
        val dz = farPoint[2] - nearPoint[2]
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length <= 1e-6f) return false
        direction[0] = dx / length
        direction[1] = dy / length
        direction[2] = dz / length
        return true
    }

    private fun hierarchyFor(mesh: StlMesh): MeshBvh? {
        val existing = cachedBvh
        if (existing != null && cachedMesh === mesh) return existing
        if (mesh.triangleCount <= 0) return null
        val built = MeshBvh.build(mesh)
        cachedMesh = mesh
        cachedBvh = built
        return built
    }

    /** A clip-space point through the inverse MVP, divided by w. */
    private fun unprojectInto(inverse: FloatArray, ndcX: Float, ndcY: Float, ndcZ: Float, out: FloatArray) {
        clipScratch[0] = ndcX
        clipScratch[1] = ndcY
        clipScratch[2] = ndcZ
        clipScratch[3] = 1f
        for (row in 0 until 4) {
            worldScratch[row] = inverse[row] * clipScratch[0] +
                inverse[4 + row] * clipScratch[1] +
                inverse[8 + row] * clipScratch[2] +
                inverse[12 + row] * clipScratch[3]
        }
        val w = worldScratch[3]
        if (w == 0f) {
            out[0] = worldScratch[0]
            out[1] = worldScratch[1]
            out[2] = worldScratch[2]
        } else {
            out[0] = worldScratch[0] / w
            out[1] = worldScratch[1] / w
            out[2] = worldScratch[2] / w
        }
    }

    /**
     * The inverse of a column-major 4x4, or null when it is singular.
     *
     * Written out rather than taken from android.opengl.Matrix so the whole
     * pick path is plain arithmetic: the handover test publishes a hand-built
     * MVP and asserts where the ray lands, and a JVM test cannot call the
     * framework's matrix code.
     */
    internal fun invert(source: FloatArray): FloatArray? {
        if (source.size != 16) return null
        // Read row-major for the cofactor expansion, then write back the other
        // way round: m[column * 4 + row] is the layout everything else uses.
        val m = FloatArray(16) { source[(it % 4) * 4 + it / 4] }
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] +
            m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] -
            m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] +
            m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] -
            m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] -
            m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] +
            m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] -
            m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] +
            m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] +
            m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] -
            m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] +
            m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] -
            m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] -
            m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] +
            m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] -
            m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] +
            m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]

        val determinant = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (determinant == 0f || !determinant.isFinite()) return null
        val scale = 1f / determinant
        val result = FloatArray(16)
        for (index in 0 until 16) {
            val value = inv[index] * scale
            if (!value.isFinite()) return null
            // Back to column-major.
            result[(index % 4) * 4 + index / 4] = value
        }
        return result
    }

    private const val VERTICES_PER_TRIANGLE = 3
    private const val FLOATS_PER_VERTEX = 6
    private const val FLOATS_PER_TRIANGLE = 18

    /** How far a corner may sit past the plane and still count as on the visible side. */
    private const val PLANE_EPSILON_MM = 1e-6f
}
