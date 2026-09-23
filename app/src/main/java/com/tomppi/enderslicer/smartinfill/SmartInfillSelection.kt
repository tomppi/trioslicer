package com.tomppi.enderslicer.smartinfill

/**
 * Selection geometry for the Smart Infill workflow.
 *
 * A remote point mass carries a centre of gravity, and its CG belongs on the
 * surface the user picked — the engine takes the point as given, so the workflow
 * snaps it to the selection's area-weighted centroid, which is the rule the
 * WebView's bridge applied.
 */
object SmartInfillSelection {

    /**
     * Area-weighted centroid of [triangles] in the engine's original-triangle
     * soup (`originalPositions`: 9 floats per triangle, xyz per corner). Null
     * when the selection is empty or has no area — a caller then keeps whatever
     * point it had rather than snapping to a meaningless origin.
     */
    fun centroid(positions: FloatArray, triangles: IntArray): List<Double>? {
        var cx = 0.0
        var cy = 0.0
        var cz = 0.0
        var weight = 0.0
        for (triangle in triangles) {
            val offset = triangle * 9
            if (offset < 0 || offset + 8 >= positions.size) continue
            val ax = positions[offset].toDouble()
            val ay = positions[offset + 1].toDouble()
            val az = positions[offset + 2].toDouble()
            val e1x = positions[offset + 3] - ax
            val e1y = positions[offset + 4] - ay
            val e1z = positions[offset + 5] - az
            val e2x = positions[offset + 6] - ax
            val e2y = positions[offset + 7] - ay
            val e2z = positions[offset + 8] - az
            // Twice the triangle area, the natural weight for a centroid.
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            val area = Math.sqrt(nx * nx + ny * ny + nz * nz)
            if (area <= 0.0) continue
            cx += area * (ax + positions[offset + 3] + positions[offset + 6]) / 3.0
            cy += area * (ay + positions[offset + 4] + positions[offset + 7]) / 3.0
            cz += area * (az + positions[offset + 5] + positions[offset + 8]) / 3.0
            weight += area
        }
        if (weight <= 0.0) return null
        return listOf(cx / weight, cy / weight, cz / weight)
    }
}
