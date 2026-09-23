package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.supportpaint.SupportPaintState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Arrays

/**
 * Incremental per-triangle colour storage for the model viewer.
 *
 * The previous implementation rebuilt the entire colour buffer on every paint
 * update: a [FloatArray] of `triangleCount * 9` floats (10 MB on a
 * 280k-triangle model), a direct [FloatBuffer] copy of the same size, and two
 * boxed [Set]&lt;Int&gt; lookups per triangle to classify it. On a 280k model
 * that is roughly 20 MB of allocation and 560k hash lookups *per touch-move
 * sample*, which is what made support painting feel unresponsive.
 *
 * This type keeps one byte per triangle plus a single persistent colour buffer,
 * and rewrites only the triangles whose colour actually changed. The remaining
 * per-update cost is a byte pass over the mesh plus a handful of float stores,
 * and the caller uploads only the resulting float range to the GPU.
 *
 * Three classifications share it: the support-paint state, the Smart Infill
 * boundary conditions drawn on the part (supports, loads, the condition being
 * picked), and — after an optimization — a density bin per triangle. A condition
 * wins over the result tint, and the tint wins over paint.
 *
 * Not thread-safe: the viewer owns one instance and mutates it on the GL thread.
 */
class PaintColorBuffer(
    val triangleCount: Int,
    /**
     * Colour per slot: 0 base, 1 enforcer, 2 blocker, 3 support, 4 load, 5 the
     * armed condition, 6+ one per density bin. The caller rebuilds the buffer
     * when the palette grows, so a slot always resolves to a colour.
     */
    private val palette: Array<FloatArray>,
) {
    init {
        require(triangleCount >= 0) { "Triangle count must not be negative" }
        require(palette.size >= FIXED_SLOTS) { "The palette needs its fixed slots" }
        require(palette.all { it.size == 3 }) { "Colours must be RGB triples" }
    }

    /** How many slots the palette holds. */
    val paletteSize: Int get() = palette.size

    /**
     * True when this buffer already paints with exactly these colours — the same
     * slots *and* the same values in them. The renderer uses it to decide whether
     * to rebuild: a second optimization with the same number of bins but different
     * densities keeps the size and changes every region colour, and reusing the
     * old palette would tint the part with the previous run's ramp.
     */
    fun hasPalette(candidate: Array<FloatArray>): Boolean {
        if (palette.size != candidate.size) return false
        for (index in palette.indices) {
            val mine = palette[index]
            val theirs = candidate[index]
            if (mine[0] != theirs[0] || mine[1] != theirs[1] || mine[2] != theirs[2]) {
                return false
            }
        }
        return true
    }

    /** Support-paint classification per triangle: 0 = base, 1 = enforcer, 2 = blocker. */
    private val paintKind = ByteArray(triangleCount)

    /** Overlay classification per triangle: 0 = none, else a palette slot. */
    private val overlayKind = ByteArray(triangleCount)

    /** Scratch classifications for [resync]/[resyncOverlay], so the diff needs no allocation. */
    private val nextPaint = ByteArray(triangleCount)
    private val nextOverlay = ByteArray(triangleCount)

    /** What the colour buffer currently holds per triangle. */
    private val rendered = ByteArray(triangleCount)

    private val colors: FloatBuffer = ByteBuffer
        .allocateDirect(triangleCount * FLOATS_PER_TRIANGLE * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    /** Pending dirty range in floats; empty when nothing changed. */
    private var dirtyFrom = Int.MAX_VALUE
    private var dirtyTo = -1

    /**
     * True when anything is painted. The renderer decides its constant-vs-buffer
     * path from [ModelSurfaceView]'s buffer reference, not from this; the flag is
     * how the classification can be asserted without reading colour floats.
     */
    var hasPaint: Boolean = false
        private set

    /** True when a boundary condition or a result tint is drawn; see [hasPaint] for its role. */
    var hasOverlay: Boolean = false
        private set

    val buffer: FloatBuffer get() = colors

    init {
        var i = 0
        while (i < triangleCount) {
            writeTriangle(i, palette[BASE.toInt()])
            i++
        }
        colors.position(0)
    }

    /**
     * Brings the paint classification in line with [state], rewriting only the
     * triangles whose colour actually changed.
     *
     * Classification walks the painted sets (O(painted)) rather than the mesh,
     * and the diff is a byte comparison per triangle - no boxing, no hashing,
     * no per-triangle allocation.
     */
    fun resync(state: SupportPaintState) {
        Arrays.fill(nextPaint, BASE)
        for (triangle in state.enforcerTriangles) {
            if (triangle in 0 until triangleCount) nextPaint[triangle] = ENFORCER
        }
        for (triangle in state.blockerTriangles) {
            if (triangle in 0 until triangleCount) nextPaint[triangle] = BLOCKER
        }
        var painted = false
        var i = 0
        while (i < triangleCount) {
            paintKind[i] = nextPaint[i]
            if (nextPaint[i] != BASE) painted = true
            i++
        }
        hasPaint = painted
        rewriteAll()
    }

    /**
     * Brings the Smart Infill overlay in line with the picked boundary conditions
     * and the result tint. Called when a pick, a removal, an armed condition or a
     * finished optimization changes — a handful of times per session, never per
     * touch sample.
     *
     * [regions] is one entry per model triangle: the density bin under it, or -1
     * for none (see SmartInfillOverlay). Conditions win over the tint, so a picked
     * surface keeps the colour of the condition it carries.
     */
    fun resyncOverlay(
        supports: IntArray,
        loads: IntArray,
        active: IntArray,
        regions: IntArray = IntArray(0),
    ) {
        Arrays.fill(nextOverlay, NONE)
        for (triangle in supports) {
            if (triangle in 0 until triangleCount) nextOverlay[triangle] = SUPPORT
        }
        for (triangle in loads) {
            if (triangle in 0 until triangleCount) nextOverlay[triangle] = LOAD
        }
        for (triangle in active) {
            if (triangle in 0 until triangleCount) nextOverlay[triangle] = ACTIVE
        }
        for (triangle in regions.indices) {
            if (triangle >= triangleCount) break
            val bin = regions[triangle]
            if (bin < 0 || nextOverlay[triangle] != NONE) continue
            val slot = REGION_BASE + bin
            // A run with more bins than the palette has colours keeps them plain
            // rather than writing a slot that would read out of bounds.
            if (slot >= paletteSize) continue
            nextOverlay[triangle] = slot.toByte()
        }
        var overlaid = false
        var i = 0
        while (i < triangleCount) {
            overlayKind[i] = nextOverlay[i]
            if (nextOverlay[i] != NONE) overlaid = true
            i++
        }
        hasOverlay = overlaid
        rewriteAll()
    }

    /**
     * Applies one paint edit when the caller already knows which triangles it
     * touched.
     *
     * [changed] must be exactly the set of indices the edit may have
     * reclassified - the brush's expansion. Triangles outside it keep their
     * current colour, so the cost is O(changed) with no mesh-sized pass.
     */
    fun apply(state: SupportPaintState, changed: Set<Int>) {
        for (triangle in changed) {
            if (triangle < 0 || triangle >= triangleCount) continue
            paintKind[triangle] = when {
                triangle in state.enforcerTriangles -> ENFORCER
                triangle in state.blockerTriangles -> BLOCKER
                else -> BASE
            }
            refresh(triangle)
        }
        var painted = false
        for (value in paintKind) {
            if (value != BASE) {
                painted = true
                break
            }
        }
        hasPaint = painted
        colors.position(0)
    }

    /**
     * Returns the pending dirty float range and clears it, or `null` when
     * nothing changed since the last call.
     */
    fun takeDirtyRange(): IntRange? {
        if (dirtyTo < dirtyFrom) return null
        val range = dirtyFrom..dirtyTo
        dirtyFrom = Int.MAX_VALUE
        dirtyTo = -1
        return range
    }

    /** The colour one triangle should show: the overlay wins over paint. */
    private fun refresh(triangle: Int) {
        val slot = overlayKind[triangle]
        val want = if (slot != NONE) slot else paintKind[triangle]
        if (want == rendered[triangle]) return
        rendered[triangle] = want
        writeTriangle(triangle, colorFor(want))
        markDirty(triangle)
    }

    private fun rewriteAll() {
        var i = 0
        while (i < triangleCount) {
            refresh(i)
            i++
        }
        colors.position(0)
    }

    private fun colorFor(value: Byte): FloatArray {
        val slot = value.toInt()
        return if (slot in palette.indices) palette[slot] else palette[0]
    }

    private fun markDirty(triangle: Int) {
        val from = triangle * FLOATS_PER_TRIANGLE
        val to = from + FLOATS_PER_TRIANGLE - 1
        if (from < dirtyFrom) dirtyFrom = from
        if (to > dirtyTo) dirtyTo = to
    }

    private fun writeTriangle(triangle: Int, color: FloatArray) {
        val offset = triangle * FLOATS_PER_TRIANGLE
        var vertex = 0
        while (vertex < 3) {
            val at = offset + vertex * 3
            colors.put(at, color[0])
            colors.put(at + 1, color[1])
            colors.put(at + 2, color[2])
            vertex++
        }
    }

    private companion object {
        const val BASE: Byte = 0
        const val ENFORCER: Byte = 1
        const val BLOCKER: Byte = 2
        const val SUPPORT: Byte = 3
        const val LOAD: Byte = 4
        const val ACTIVE: Byte = 5
        const val NONE: Byte = 0

        /** Region bins start here: slot = REGION_BASE + bin index. */
        const val REGION_BASE = 6

        /** base, enforcer, blocker, support, load, armed. */
        const val FIXED_SLOTS = 6
        const val FLOATS_PER_TRIANGLE = 9
    }
}
