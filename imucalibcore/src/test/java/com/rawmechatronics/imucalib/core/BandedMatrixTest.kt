package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BandedMatrixTest {

    @Test
    fun solveMatchesDenseGaussianForRandomSpd() {
        val rnd = Random(3)
        val n = 60
        val band = 5
        // L is a random banded lower-triangular matrix; A = L L^T + n I is banded SPD with the same band.
        val l = Array(n) { i -> DoubleArray(band + 1) { k -> if (i - k >= 0) rnd.nextDouble(-0.5, 0.5) else 0.0 } }
        val a = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0 until n) {
            var s = 0.0
            for (ki in 0..band) for (kj in 0..band) {
                val ii = i - ki
                val jj = j - kj
                if (ii >= 0 && jj >= 0 && ii == jj) s += l[i][ki] * l[j][kj]
            }
            a[i][j] = s + if (i == j) 1.0 else 0.0
        }
        val rhs = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        val dense = rhs.copyOf()
        val m = Array(n) { a[it].copyOf() }
        solveDense(m, n, dense)

        val banded = BandedMatrix(n, band)
        for (i in 0 until n) for (j in (i - band).coerceAtLeast(0)..i) banded.add(i, j, a[i][j])
        assertTrue(banded.cholesky())
        val got = rhs.copyOf()
        banded.solve(got)
        for (i in 0 until n) assertEquals("i=" + i, dense[i], got[i], 1e-8)
    }

    private fun solveDense(a: Array<DoubleArray>, n: Int, b: DoubleArray) {
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            val tb = b[col]; b[col] = b[pivot]; b[pivot] = tb
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                for (k in col until n) a[r][k] -= f * a[col][k]
                b[r] -= f * b[col]
            }
        }
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r][k] * b[k]
            b[r] = s / a[r][r]
        }
    }
}
