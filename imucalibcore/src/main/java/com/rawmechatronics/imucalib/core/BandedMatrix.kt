package com.rawmechatronics.imucalib.core

import kotlin.math.sqrt

/**
 * Symmetric positive-definite matrix stored as a lower banded matrix:
 * A(i,j) with i >= j and i - j <= band. Used for the spline block of the
 * normal equations; the band exists because a cubic B-spline residual touches
 * four consecutive knots (24 coefficients at most).
 */
class BandedMatrix(val n: Int, val band: Int) {
    val data = DoubleArray(n * (band + 1))

    init {
        require(n > 0 && band >= 0)
    }

    fun add(i: Int, j: Int, value: Double) {
        if (value == 0.0) return
        val r = if (i >= j) i else j
        val c = if (i >= j) j else i
        if (r - c > band) throw IllegalArgumentException("outside band: $r,$c")
        data[r * (band + 1) + (r - c)] += value
    }

    fun get(i: Int, j: Int): Double {
        val r = if (i >= j) i else j
        val c = if (i >= j) j else i
        if (r - c > band) return 0.0
        return data[r * (band + 1) + (r - c)]
    }

    fun set(i: Int, j: Int, value: Double) {
        val r = if (i >= j) i else j
        val c = if (i >= j) j else i
        require(r - c <= band)
        data[r * (band + 1) + (r - c)] = value
    }

    fun addDiagonal(value: Double) {
        for (i in 0 until n) add(i, i, value)
    }

    fun copy(): BandedMatrix {
        val m = BandedMatrix(n, band)
        System.arraycopy(data, 0, m.data, 0, data.size)
        return m
    }

    /** In-place Cholesky: data becomes the lower triangular factor L. */
    fun cholesky(): Boolean {
        for (i in 0 until n) {
            val j0 = (i - band).coerceAtLeast(0)
            for (j in j0..i) {
                var sum = get(i, j)
                val k0 = (i - band).coerceAtLeast(j - band).coerceAtLeast(0)
                for (k in k0 until j) sum -= get(i, k) * get(j, k)
                if (i == j) {
                    if (sum <= 0.0) return false
                    set(i, i, sqrt(sum))
                } else {
                    set(i, j, sum / get(j, j))
                }
            }
        }
        return true
    }

    /** Solve L L^T x = b in place, after cholesky(). */
    fun solve(b: DoubleArray) {
        require(b.size == n)
        for (i in 0 until n) {
            var sum = b[i]
            val j0 = (i - band).coerceAtLeast(0)
            for (j in j0 until i) sum -= get(i, j) * b[j]
            b[i] = sum / get(i, i)
        }
        for (i in n - 1 downTo 0) {
            var sum = b[i]
            val j1 = (i + band).coerceAtMost(n - 1)
            for (j in i + 1..j1) sum -= get(j, i) * b[j]
            b[i] = sum / get(i, i)
        }
    }
}
