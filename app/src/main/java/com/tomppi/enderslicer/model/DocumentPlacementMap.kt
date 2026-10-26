package com.tomppi.enderslicer.model

/**
 * One placed object's document-to-plate map, computed once.
 *
 * [ModelPlacement.transformed] draws a source mesh at `L * p + t`, where the translation is
 * decided by the transformed mesh's own bounds. That makes the map easy to call but expensive to
 * rebuild per point (it measures the whole mesh), so anything that has many points to map - a
 * face highlight, a tap's document coordinate - takes it once and reuses it. The placement is a
 * plain linear part plus a translation, so the inverse is exact.
 */
class DocumentPlacementMap internal constructor(
    private val linear: List<Double>,
    private val translationXmm: Double,
    private val translationYmm: Double,
    private val translationZmm: Double,
) {
    private val inverse: List<Double>? = invertLinear(linear)

    /** True when the map is the identity: the document's coordinates are the plate's. */
    val isIdentity: Boolean
        get() = linear == ModelPlacement.IDENTITY &&
            translationXmm == 0.0 && translationYmm == 0.0 && translationZmm == 0.0

    /** The plate-space position a document point is drawn at. */
    fun toPlate(x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        linear[0] * x + linear[1] * y + linear[2] * z + translationXmm,
        linear[3] * x + linear[4] * y + linear[5] * z + translationYmm,
        linear[6] * x + linear[7] * y + linear[8] * z + translationZmm,
    )

    /** The document point a plate point came from, or null when the linear part is singular. */
    fun toDocument(x: Double, y: Double, z: Double): DoubleArray? {
        val matrix = inverse ?: return null
        val dx = x - translationXmm
        val dy = y - translationYmm
        val dz = z - translationZmm
        return doubleArrayOf(
            matrix[0] * dx + matrix[1] * dy + matrix[2] * dz,
            matrix[3] * dx + matrix[4] * dy + matrix[5] * dz,
            matrix[6] * dx + matrix[7] * dy + matrix[8] * dz,
        )
    }

    /** A document direction turned into a plate direction (unit length, or null if impossible). */
    fun directionToPlate(x: Double, y: Double, z: Double): DoubleArray? {
        val px = linear[0] * x + linear[1] * y + linear[2] * z
        val py = linear[3] * x + linear[4] * y + linear[5] * z
        val pz = linear[6] * x + linear[7] * y + linear[8] * z
        val length = kotlin.math.sqrt(px * px + py * py + pz * pz)
        if (!length.isFinite() || length <= 1e-12) return null
        return doubleArrayOf(px / length, py / length, pz / length)
    }
}

/**
 * The inverse of a row-major 3x3, or null when it cannot be inverted.
 *
 * Written out rather than pulled from a library so the document-to-plate map is plain arithmetic
 * the JVM tests can exercise without a device.
 */
internal fun invertLinear(matrix: List<Double>): List<Double>? {
    if (matrix.size != 9) return null
    val a = matrix[0]
    val b = matrix[1]
    val c = matrix[2]
    val d = matrix[3]
    val e = matrix[4]
    val f = matrix[5]
    val g = matrix[6]
    val h = matrix[7]
    val i = matrix[8]
    val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    if (!determinant.isFinite() || kotlin.math.abs(determinant) <= 1e-12) return null
    val scale = 1.0 / determinant
    return listOf(
        (e * i - f * h) * scale, (c * h - b * i) * scale, (b * f - c * e) * scale,
        (f * g - d * i) * scale, (a * i - c * g) * scale, (c * d - a * f) * scale,
        (d * h - e * g) * scale, (b * g - a * h) * scale, (a * e - b * d) * scale,
    )
}
