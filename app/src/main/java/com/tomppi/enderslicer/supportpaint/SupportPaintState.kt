package com.tomppi.enderslicer.supportpaint

/** How a paint stroke is applied to the model surface. */
enum class SupportPaintMode {
    NONE,
    ENFORCER,
    BLOCKER,
    ERASE,
}

/**
 * Support painting state for a single displayed model.
 *
 * Painted regions are stored as triangle indices into the displayed mesh's
 * [com.tomppi.enderslicer.viewer.StlMesh.interleavedVertices]. Triangle order is
 * stable across placement transforms (they only recompute vertices, never
 * reorder triangles), so these indices remain valid until a different model is
 * imported.
 */
data class SupportPaintState(
    val enforcerTriangles: Set<Int> = emptySet(),
    val blockerTriangles: Set<Int> = emptySet(),
    val brushRadiusMm: Double = DEFAULT_BRUSH_RADIUS_MM,
) {
    init {
        require(brushRadiusMm.isFinite() && brushRadiusMm > 0.0) { "Paint brush radius must be positive" }
        // Walk the smaller set instead of allocating an intersection: the
        // mutators below maintain this invariant, so the check only has to catch
        // a malformed constructed or decoded state, and it runs on every copy.
        val smaller = if (enforcerTriangles.size <= blockerTriangles.size) enforcerTriangles else blockerTriangles
        val larger = if (enforcerTriangles.size <= blockerTriangles.size) blockerTriangles else enforcerTriangles
        if (smaller.isNotEmpty()) {
            require(smaller.none { it in larger }) {
                "A triangle cannot be both a support enforcer and a support blocker"
            }
        }
    }

    val isEmpty: Boolean get() = enforcerTriangles.isEmpty() && blockerTriangles.isEmpty()

    /** Drops painted indices that do not exist in the current mesh (stale or corrupt restored workspace). */
    fun clippedToMesh(triangleCount: Int): SupportPaintState {
        require(triangleCount >= 0) { "Triangle count must not be negative" }
        return copy(
            enforcerTriangles = enforcerTriangles.filterTo(mutableSetOf()) { it in 0 until triangleCount },
            blockerTriangles = blockerTriangles.filterTo(mutableSetOf()) { it in 0 until triangleCount },
        )
    }

    /**
     * The same paint read against a mesh a cut produced: [sourceTriangles] has
     * one entry per triangle of the cut mesh naming the input triangle it was
     * cut from, so a painted input triangle paints every piece of itself that
     * survived the cut and the pieces the cut dropped take their paint with
     * them.
     *
     * A clip that folded or split a triangle renames every index after it, and a
     * clipped mesh is shorter than the one it came from: without this the paint
     * lands on whichever triangle happens to hold the old number, which on a
     * partly submerged model is a different wall of the part entirely.
     */
    fun throughClip(sourceTriangles: IntArray): SupportPaintState {
        if (isEmpty) return this
        val enforcers = HashSet<Int>()
        val blockers = HashSet<Int>()
        for (triangle in sourceTriangles.indices) {
            val source = sourceTriangles[triangle]
            when {
                source in enforcerTriangles -> enforcers += triangle
                source in blockerTriangles -> blockers += triangle
            }
        }
        return copy(enforcerTriangles = enforcers, blockerTriangles = blockers)
    }

    fun withEnforcer(triangles: Set<Int>): SupportPaintState = copy(
        enforcerTriangles = enforcerTriangles + triangles,
        blockerTriangles = blockerTriangles - triangles,
    )

    fun withBlocker(triangles: Set<Int>): SupportPaintState = copy(
        blockerTriangles = blockerTriangles + triangles,
        enforcerTriangles = enforcerTriangles - triangles,
    )

    fun erased(triangles: Set<Int>): SupportPaintState = copy(
        enforcerTriangles = enforcerTriangles - triangles,
        blockerTriangles = blockerTriangles - triangles,
    )

    companion object {
        const val DEFAULT_BRUSH_RADIUS_MM = 2.0
        const val MIN_BRUSH_RADIUS_MM = 0.5
        const val MAX_BRUSH_RADIUS_MM = 20.0
    }
}
