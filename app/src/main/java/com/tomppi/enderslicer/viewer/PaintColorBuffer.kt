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
 * per-update cost is a byte pass over the resynced range plus a handful of float
 * stores, and the caller uploads only the resulting float range to the GPU.
 *
 * One instance covers the whole plate. Every object owns a contiguous slice of
 * it, resynced in the object's own triangle indices at its own first scene
 * triangle, and each slice names its own base slot: the selected object's
 * unpainted triangles can be the selection tint while a neighbour's stay in the
 * plain base colour. Enforcer and blocker are shared palette slots, so paint on
 * every object is drawn, not just the selected one's.
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

    /**
     * The slot each triangle is classified in: [ENFORCER], [BLOCKER], or the base
     * slot of the object that owns the triangle. Base slots are never the two
     * painted values, so a triangle counts as painted exactly when its byte is one
     * of those.
     */
    private val paintKind = ByteArray(triangleCount)

    /** Overlay classification per triangle: 0 = none, else a palette slot. */
    private val overlayKind = ByteArray(triangleCount)

    /** Scratch classifications for [resync]/[resyncOverlay], so the diff needs no allocation. */
    private val nextPaint = ByteArray(triangleCount)
    private val nextOverlay = ByteArray(triangleCount)

    /** What the colour buffer currently holds per triangle. */
    private val rendered = ByteArray(triangleCount)

    /** How many triangles are painted, so an edit needs no mesh-sized scan for [hasPaint]. */
    private var paintedTriangles = 0

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
            writeTriangle(i, palette[BASE_SLOT])
            i++
        }
        colors.position(0)
    }

    /**
     * Brings the object [paint] describes in line with its slice of the scene,
     * rewriting only the triangles whose colour actually changed.
     *
     * [offsetTriangles] is where that object's first triangle sits in the scene
     * and [countTriangles] how many triangles it owns, so its own indices can be
     * read against the shared buffer. [baseSlot] is the palette slot its
     * unpainted triangles draw in — the selection tint for the object being
     * worked on, the plain base colour for the rest.
     *
     * Classification walks the painted sets (O(painted)) rather than the mesh,
     * and the diff is a byte comparison per triangle - no boxing, no hashing,
     * no per-triangle allocation.
     */
    fun resync(
        paint: SupportPaintState,
        offsetTriangles: Int = 0,
        countTriangles: Int = triangleCount - offsetTriangles,
        baseSlot: Int = BASE_SLOT,
    ) {
        val from = offsetTriangles.coerceIn(0, triangleCount)
        val to = (offsetTriangles + countTriangles).coerceIn(from, triangleCount)
        if (from == to) return
        val base = baseSlot.toByte()
        Arrays.fill(nextPaint, from, to, base)
        for (triangle in paint.enforcerTriangles) {
            if (triangle < 0 || triangle >= countTriangles) continue
            val at = offsetTriangles + triangle
            if (at >= from && at < to) nextPaint[at] = ENFORCER
        }
        for (triangle in paint.blockerTriangles) {
            if (triangle < 0 || triangle >= countTriangles) continue
            val at = offsetTriangles + triangle
            if (at >= from && at < to) nextPaint[at] = BLOCKER
        }
        var i = from
        while (i < to) {
            classify(i, nextPaint[i])
            i++
        }
        hasPaint = paintedTriangles > 0
        rewriteRange(from, to)
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
     *
     * A plate carries one overlay and it belongs to the selected object, so the
     * whole scratch classification starts empty: an overlay that moved to another
     * object has to release the triangles it used to claim. [offsetTriangles] and
     * [countTriangles] place that object inside the scene buffer.
     */
    fun resyncOverlay(
        supports: IntArray,
        loads: IntArray,
        active: IntArray,
        regions: IntArray = IntArray(0),
        offsetTriangles: Int = 0,
        countTriangles: Int = triangleCount - offsetTriangles,
    ) {
        Arrays.fill(nextOverlay, NONE)
        val from = offsetTriangles.coerceIn(0, triangleCount)
        val to = (offsetTriangles + countTriangles).coerceIn(from, triangleCount)
        markOverlay(supports, SUPPORT, offsetTriangles, from, to)
        markOverlay(loads, LOAD, offsetTriangles, from, to)
        markOverlay(active, ACTIVE, offsetTriangles, from, to)
        for (triangle in regions.indices) {
            if (triangle >= countTriangles) break
            val at = offsetTriangles + triangle
            if (at < from || at >= to) break
            val bin = regions[triangle]
            if (bin < 0 || nextOverlay[at] != NONE) continue
            val slot = REGION_BASE + bin
            // A run with more bins than the palette has colours keeps them plain
            // rather than writing a slot that would read out of bounds.
            if (slot >= paletteSize) continue
            nextOverlay[at] = slot.toByte()
        }
        var overlaid = false
        var i = 0
        while (i < triangleCount) {
            overlayKind[i] = nextOverlay[i]
            if (nextOverlay[i] != NONE) overlaid = true
            i++
        }
        hasOverlay = overlaid
        rewriteRange(0, triangleCount)
    }

    /**
     * Applies one paint edit when the caller already knows which triangles it
     * touched.
     *
     * [changed] must be exactly the set of the object's own indices the edit may
     * have reclassified - the brush's expansion - and [offsetTriangles]/
     * [countTriangles] place that object inside the scene buffer. Triangles
     * outside it keep their current colour, so the cost is O(changed) with no
     * mesh-sized pass.
     */
    fun apply(
        paint: SupportPaintState,
        changed: Set<Int>,
        offsetTriangles: Int = 0,
        countTriangles: Int = triangleCount - offsetTriangles,
        baseSlot: Int = BASE_SLOT,
    ) {
        val from = offsetTriangles.coerceIn(0, triangleCount)
        val to = (offsetTriangles + countTriangles).coerceIn(from, triangleCount)
        val base = baseSlot.toByte()
        for (triangle in changed) {
            if (triangle < 0 || triangle >= countTriangles) continue
            val at = offsetTriangles + triangle
            if (at < from || at >= to) continue
            classify(
                at,
                when {
                    triangle in paint.enforcerTriangles -> ENFORCER
                    triangle in paint.blockerTriangles -> BLOCKER
                    else -> base
                },
            )
            refresh(at)
        }
        hasPaint = paintedTriangles > 0
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

    /** Records that [triangle] now carries [value]; keeps the painted count exact. */
    private fun classify(triangle: Int, value: Byte) {
        val previous = paintKind[triangle]
        if (previous == value) return
        val wasPainted = previous == ENFORCER || previous == BLOCKER
        val isPainted = value == ENFORCER || value == BLOCKER
        if (wasPainted != isPainted) paintedTriangles += if (isPainted) 1 else -1
        paintKind[triangle] = value
    }

    private fun markOverlay(
        indices: IntArray,
        value: Byte,
        offsetTriangles: Int,
        from: Int,
        to: Int,
    ) {
        for (index in indices) {
            if (index < 0) continue
            val at = offsetTriangles + index
            if (at < from || at >= to) continue
            nextOverlay[at] = value
        }
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

    private fun rewriteRange(from: Int, to: Int) {
        var i = from
        while (i < to) {
            refresh(i)
            i++
        }
        colors.position(0)
    }

    private fun colorFor(value: Byte): FloatArray {
        val slot = value.toInt()
        return if (slot in palette.indices) palette[slot] else palette[BASE_SLOT]
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

    companion object {
        /** Slot 0: the plain base colour of an object that is not selected. */
        const val BASE_SLOT = 0

        private const val ENFORCER: Byte = 1
        private const val BLOCKER: Byte = 2
        private const val SUPPORT: Byte = 3
        private const val LOAD: Byte = 4
        private const val ACTIVE: Byte = 5
        private const val NONE: Byte = 0

        /** Region bins start here: slot = REGION_BASE + bin index. */
        private const val REGION_BASE = 6

        /** base, enforcer, blocker, support, load, armed. */
        private const val FIXED_SLOTS = 6
        private const val FLOATS_PER_TRIANGLE = 9
    }
}
