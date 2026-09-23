package com.tomppi.enderslicer.smartinfill

/**
 * What the model shows for the Smart Infill workflow: which ORIGINAL triangles
 * carry a support, which carry a load, which condition is armed for its next
 * pick, and — once a run has finished — the density bin under every triangle.
 *
 * Built from the workflow state, so the surfaces the user sees and the surfaces
 * the solver is given are the same selection.
 */
class SmartInfillOverlay(
    val supports: IntArray,
    val loads: IntArray,
    val active: IntArray,
    /** Per model triangle: the density bin it sits on, or -1 for none. */
    val regions: IntArray = IntArray(0),
    /** The densities those bins stand for, in bin order, as fractions (0.22 = 22 %). */
    val binDensities: DoubleArray = DoubleArray(0),
    /**
     * The optimized volumes as the engine reported them, drawn as coloured
     * wireframe shells: the geometry the modifier package will carry, at the
     * constants the solver found. In the model's own coordinates, which is why
     * the viewer can hand them straight to the same model matrix.
     */
    val volumes: List<FilaSimRegion> = emptyList(),
) {
    val isEmpty: Boolean
        get() = supports.isEmpty() && loads.isEmpty() && active.isEmpty() &&
            regions.isEmpty() && volumes.isEmpty()

    // Array content, not identity: the viewer skips a redraw when a
    // recomposition hands it the same selection again.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is SmartInfillOverlay &&
                supports.contentEquals(other.supports) &&
                loads.contentEquals(other.loads) &&
                active.contentEquals(other.active) &&
                regions.contentEquals(other.regions) &&
                binDensities.contentEquals(other.binDensities) &&
                volumes == other.volumes)

    override fun hashCode(): Int {
        var result = supports.contentHashCode()
        result = result * 31 + loads.contentHashCode()
        result = result * 31 + active.contentHashCode()
        result = result * 31 + regions.contentHashCode()
        result = result * 31 + binDensities.contentHashCode()
        result = result * 31 + volumes.hashCode()
        return result
    }

    companion object {
        /**
         * True for the conditions the part rests on. A bearing is a load (it
         * carries force through the surface); a prescribed displacement is a
         * support (it removes freedom).
         */
        fun isSupport(condition: FilaSimBoundaryCondition): Boolean = when (condition) {
            is FilaSimBoundaryCondition.Fixed,
            is FilaSimBoundaryCondition.Frictionless,
            is FilaSimBoundaryCondition.Elastic,
            is FilaSimBoundaryCondition.Cylindrical,
            is FilaSimBoundaryCondition.Displacement,
            -> true

            else -> false
        }

        /**
         * The overlay for [conditions] and the result tint, or null when there is
         * nothing to draw. [surfaceBins] is per model triangle (-1 = no bin) and
         * [binDensities] the densities those bin indices mean.
         */
        fun of(
            conditions: List<SmartInfillCondition>,
            pickingConditionId: Long?,
            surfaceBins: IntArray = IntArray(0),
            binDensities: DoubleArray = DoubleArray(0),
            volumes: List<FilaSimRegion> = emptyList(),
        ): SmartInfillOverlay? {
            val supports = ArrayList<Int>()
            val loads = ArrayList<Int>()
            val active = ArrayList<Int>()
            conditions.forEach { entry ->
                val target = if (isSupport(entry.condition)) supports else loads
                entry.condition.triangles.forEach(target::add)
                if (entry.id == pickingConditionId) {
                    entry.condition.triangles.forEach(active::add)
                }
            }
            // A tint with no bin anywhere is not a tint: keep the array out so the
            // viewer never builds a region palette for nothing.
            val tinted = binDensities.isNotEmpty() && surfaceBins.any { bin -> bin >= 0 }
            val overlay = SmartInfillOverlay(
                supports = supports.toIntArray(),
                loads = loads.toIntArray(),
                active = active.toIntArray(),
                regions = if (tinted) surfaceBins else IntArray(0),
                binDensities = if (tinted) binDensities else DoubleArray(0),
                volumes = volumes,
            )
            return if (overlay.isEmpty) null else overlay
        }
    }
}
