package com.tomppi.enderslicer.model

/** One model's footprint on the bed, from its transformed bounding box. */
data class PlateFootprint(val widthMm: Double, val depthMm: Double)

/** Where an object's centre should sit, in bed coordinates (0,0 = bed's front-left corner). */
data class PlateSlot(val centerXmm: Double, val centerYmm: Double)

/**
 * Lays footprints out on the bed in shelves: largest first, a row filled left to right, a new row
 * when the next footprint would leave the bed, rows stacked front to back.
 *
 * This is plain shelf packing, not a nesting solver - deterministic and cheap, which is all an
 * "arrange the plate" button needs. Everything is kept [spacingMm] from its neighbour and from the
 * bed edge, and [arrange] answers null rather than stacking two models on top of each other when
 * the plate is too small.
 */
object PlateArranger {
    /** Slack for floating point sums, in mm. Far below anything a printer can resolve. */
    private const val EPSILON = 1e-9

    /**
     * Lays the footprints out on the bed, largest first, in rows.
     * @return one slot per footprint, in the SAME ORDER as the input, or null when they do
     *         not fit at this spacing.
     */
    fun arrange(
        footprints: List<PlateFootprint>,
        bedWidthMm: Double,
        bedDepthMm: Double,
        spacingMm: Double,
    ): List<PlateSlot>? {
        if (footprints.isEmpty()) return emptyList()

        // A bed without a positive finite size can hold nothing.
        if (!bedWidthMm.isFinite() || !bedDepthMm.isFinite() ||
            bedWidthMm <= 0.0 || bedDepthMm <= 0.0
        ) {
            return null
        }
        // A negative or non-finite spacing simply means no gap.
        val spacing = if (spacingMm.isFinite() && spacingMm > 0.0) spacingMm else 0.0
        val usableWidth = bedWidthMm - 2.0 * spacing
        val usableDepth = bedDepthMm - 2.0 * spacing

        val sizes = footprints.map { PlateFootprint(sizeOrZero(it.widthMm), sizeOrZero(it.depthMm)) }

        // Nothing can be arranged around a footprint that does not fit on the bed on its own.
        for (size in sizes) {
            if (size.widthMm > usableWidth + EPSILON) return null
            if (size.depthMm > usableDepth + EPSILON) return null
        }

        // Largest first, ties broken on the longer side and then on input position, so a big part
        // is never stranded behind a small one and the same plate always packs the same way.
        val order = sizes.indices.sortedWith(
            compareByDescending<Int> { sizes[it].widthMm * sizes[it].depthMm }
                .thenByDescending { maxOf(sizes[it].widthMm, sizes[it].depthMm) }
                .thenBy { it },
        )

        // Fill rows in that order: a footprint starts a new row only when it would leave the bed.
        val rows = mutableListOf<MutableList<Int>>()
        var row = mutableListOf<Int>()
        var rowWidth = 0.0
        for (index in order) {
            val needed = if (row.isEmpty()) {
                sizes[index].widthMm
            } else {
                rowWidth + spacing + sizes[index].widthMm
            }
            if (row.isNotEmpty() && needed > usableWidth + EPSILON) {
                rows.add(row)
                row = mutableListOf(index)
                rowWidth = sizes[index].widthMm
            } else {
                row.add(index)
                rowWidth = needed
            }
        }
        rows.add(row)

        // Then centre each row across the bed and stack the rows from the front, leaving the
        // spacing as the margin to every edge.
        val slots = arrayOfNulls<PlateSlot>(sizes.size)
        var rowFront = spacing
        for (current in rows) {
            var rowDepth = 0.0
            var currentWidth = -spacing
            for (index in current) {
                rowDepth = maxOf(rowDepth, sizes[index].depthMm)
                currentWidth += sizes[index].widthMm + spacing
            }
            if (rowFront + rowDepth > usableDepth + spacing + EPSILON) return null

            var cursor = (bedWidthMm - currentWidth) / 2.0
            val centerY = rowFront + rowDepth / 2.0
            for (index in current) {
                slots[index] = PlateSlot(cursor + sizes[index].widthMm / 2.0, centerY)
                cursor += sizes[index].widthMm + spacing
            }
            rowFront += rowDepth + spacing
        }

        val placed = ArrayList<PlateSlot>(sizes.size)
        for (slot in slots) placed.add(slot ?: return null)
        return placed
    }

    /** NaN and negative sizes collapse to zero; an infinity stays infinite and so never fits. */
    private fun sizeOrZero(valueMm: Double): Double =
        if (valueMm.isNaN()) 0.0 else valueMm.coerceAtLeast(0.0)
}
