package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class BSplineTest {

    private fun spline(dims: Int = 3, n: Int = 12): BSpline {
        val rnd = Random(11)
        val coeff = Array(n) { DoubleArray(dims) { rnd.nextDouble(-1.0, 1.0) } }
        return BSpline(dims, coeff, 2.5, 0.1)
    }

    private fun within(t: Double, s: BSpline, k: Int, steps: Int) =
        s.startTime + (k.toDouble() / steps) * (s.endTime - s.startTime) * 0.999

    @Test
    fun basisIsAPartitionOfUnity() {
        val s = spline()
        for (k in 0 until 50) {
            val t = within(k.toDouble(), s, k, 50)
            val b = s.basis(t)
            assertEquals(1.0, b.b.sum(), 1e-12)
            assertEquals(0.0, b.db.sum(), 1e-12)
            assertEquals(0.0, b.ddb.sum(), 1e-12)
        }
    }

    @Test
    fun derivativesMatchFiniteDifferences() {
        val s = spline()
        for (k in 2 until 38) {
            val t = s.startTime + (k.toDouble() / 40.0) * (s.endTime - s.startTime)
            val h1 = 1e-6
            val d1 = s.derivative(t, 1)
            for (d in 0 until 3) {
                assertEquals((s.eval(t + h1)[d] - s.eval(t - h1)[d]) / (2 * h1), d1[d], 1e-5)
            }
            // second derivative: central difference needs a larger step (1e-6 loses
            // precision because the difference itself is ~1e-12)
            val h2 = 1e-3
            val v = s.eval(t)
            val d2 = s.derivative(t, 2)
            for (d in 0 until 3) {
                assertEquals((s.eval(t + h2)[d] - 2 * v[d] + s.eval(t - h2)[d]) / (h2 * h2), d2[d], 5e-2)
            }
        }
    }

    @Test
    fun evalIsContinuousAcrossKnots() {
        val s = spline()
        val eps = 1e-9
        var t = s.startTime
        while (t < s.endTime - 1e-6) {
            val inside = t.coerceIn(s.startTime, s.endTime - 1e-6)
            val a = s.eval((inside - eps).coerceAtLeast(s.startTime))
            val b = s.eval((inside + eps).coerceAtMost(s.endTime - 1e-9))
            for (d in 0 until 3) assertEquals(a[d], b[d], 1e-5)
            t += 0.1
        }
    }

    @Test
    fun endTimeEqualsCoveredSegments() {
        val s = spline(n = 10)
        assertEquals(2.5 + (10 - 3) * 0.1, s.endTime, 1e-12)
    }
}