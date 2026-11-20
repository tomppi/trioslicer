package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BSplineOrder6Test {
    @Test
    fun order6BasisDerivativesMatchFiniteDifferences() {
        val coeff = Array(20) { i -> DoubleArray(1) { kotlin.math.sin(i * 1.7) + 0.3 * i * i } }
        val sp = BSpline(1, coeff, 0.0, 0.1, order = 6)
        for (t in doubleArrayOf(0.53, 0.71, 0.97, 1.23)) {
            val b = sp.basis(t)
            var sum = 0.0
            var sumD = 0.0
            for (k in 0 until 6) { sum += b.b[k]; sumD += b.db[k] }
            assertEquals("partition of unity", 1.0, sum, 1e-12)
            assertEquals("derivative sums to zero", 0.0, sumD, 1e-10)
            val eps = 1e-6
            val v0 = sp.eval(t)
            val vp = sp.eval(t + eps)
            val vm = sp.eval(t - eps)
            val numD = (vp[0] - vm[0]) / (2 * eps)
            val numD2 = (vp[0] - 2 * v0[0] + vm[0]) / (eps * eps)
            val anaD = sp.evalWithDerivatives(t).second[0][0]
            val anaD2 = sp.evalWithDerivatives(t).second[1][0]
            assertEquals("first derivative", numD, anaD, 1e-5)
            assertEquals("second derivative", numD2, anaD2, 0.05)
        }
    }
}
