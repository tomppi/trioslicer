package com.rawmechatronics.imucalib.core

import kotlin.math.floor

/**
 * Uniform B-spline over a multi-dimensional curve of arbitrary order.
 *
 * Order 4 is the cubic B-spline Kalibr's single-camera fits traditionally used;
 * Kalibr's IMU-camera calibrator uses order 6 (degree 5) at 70 knots/s, and the
 * higher order is what lets linear acceleration follow the accelerometer
 * without the trajectory bending. A pose curve uses 6 dimensions: translation
 * and a rotation vector that is mapped to a rotation matrix with exp().
 *
 * Coefficients are stored row-major: coeff[i][d].
 */
class BSpline(
    private val dims: Int,
    private val coeff: Array<DoubleArray>,
    private val t0: Double,
    private val dt: Double,
    val order: Int = 4,
) {
    init {
        require(order >= 2) { "spline order must be at least 2" }
        require(coeff.size >= order) { "a spline of order $order needs at least $order coefficients" }
        require(dt > 0.0)
        coeff.forEach { require(it.size == dims) }
    }

    val dimension: Int get() = dims
    val startTime: Double get() = t0

    /** Read-only view of the coefficient table (for offline/GPU prototypes). */
    fun coefficients(): Array<DoubleArray> = coeff
    val endTime: Double get() = t0 + (coeff.size - (order - 1)) * dt

    class Basis(val b: DoubleArray, val db: DoubleArray, val ddb: DoubleArray)

    /**
     * Table of cardinal B-spline values N_n(x + shift) for n = 1..[maxOrder]
     * and shifts in [-span, span]. Built bottom-up once per basis evaluation;
     * the naive recursion would be exponential in the spline order.
     */
    private fun cardinalTable(x: Double, maxOrder: Int, span: Int): Array<DoubleArray> {
        val off = span
        val width = 2 * span + 1
        val t = Array(maxOrder + 1) { DoubleArray(width) }
        for (s in -span..span) {
            val y = x + s
            t[1][s + off] = if (y >= 0.0 && y < 1.0) 1.0 else 0.0
        }
        for (n in 2..maxOrder) {
            for (s in -span..span) {
                val y = x + s
                val a = t[n - 1][s + off]
                val b = if (s - 1 >= -span) t[n - 1][s - 1 + off] else 0.0
                t[n][s + off] = (y * a + (n - y) * b) / (n - 1)
            }
        }
        return t
    }

    /** Basis weights for the segment containing [t], and their 1st/2nd time derivatives. */
    fun basis(t: Double): Basis {
        val u = (t - t0) / dt
        val i = floor(u).toInt()
        val s = u - i
        // Evaluate the table around the highest local cardinal coordinate x.
        val span = order + 2
        val xMax = s + (order - 1)
        val tab = cardinalTable(xMax, order, span)
        val b = DoubleArray(order)
        val db = DoubleArray(order)
        val ddb = DoubleArray(order)
        for (k in 0 until order) {
            // shift relative to xMax: x = xMax - k
            val sh = -k
            b[k] = tab[order][sh + span]
            val nPrev = tab[order - 1]
            val nPrev2 = tab[order - 2]
            db[k] = (nPrev[sh + span] - nPrev[sh - 1 + span]) / dt
            ddb[k] = (nPrev2[sh + span] - 2.0 * nPrev2[sh - 1 + span] + nPrev2[sh - 2 + span]) / (dt * dt)
        }
        return Basis(b, db, ddb)
    }

    /** Value and derivatives (d0, d1, d2 w.r.t. time) of the curve at [t]. */
    fun eval(t: Double): DoubleArray = evalWithDerivatives(t).first

    fun evalWithDerivatives(t: Double): Pair<DoubleArray, Array<DoubleArray>> {
        val u = (t - t0) / dt
        val base = floor(u).toInt()
        val basis = basis(t)
        val value = DoubleArray(dims)
        val d1 = DoubleArray(dims)
        val d2 = DoubleArray(dims)
        for (k in 0 until order) {
            val ci = base + k
            require(ci in coeff.indices) { "time outside the spline support" }
            val c = coeff[ci]
            for (d in 0 until dims) {
                value[d] += basis.b[k] * c[d]
                d1[d] += basis.db[k] * c[d]
                d2[d] += basis.ddb[k] * c[d]
            }
        }
        return Pair(value, arrayOf(d1, d2))
    }

    fun derivative(t: Double, order: Int): DoubleArray {
        val (_, d) = evalWithDerivatives(t)
        return when (order) {
            0 -> eval(t)
            1 -> d[0]
            2 -> d[1]
            else -> throw IllegalArgumentException("only 0..2 supported")
        }
    }
}
