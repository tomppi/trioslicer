package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class Se3SplineTest {

    private fun spline(n: Int = 60, dt: Double = 0.05): Se3Spline {
        val rnd = Random(23)
        val coeff = Array(n) { i ->
            val t = i * dt
            DoubleArray(6) { d ->
                when (d) {
                    0 -> 0.4 * kotlin.math.sin(0.7 * t) + rnd.nextDouble(-0.01, 0.01)
                    1 -> 0.3 * kotlin.math.cos(0.5 * t) + rnd.nextDouble(-0.01, 0.01)
                    2 -> 0.2 * kotlin.math.sin(0.3 * t) + rnd.nextDouble(-0.01, 0.01)
                    else -> 0.8 * kotlin.math.sin(0.9 * t + d) + rnd.nextDouble(-0.02, 0.02)
                }
            }
        }
        return Se3Spline(BSpline(6, coeff, 0.0, dt))
    }

    @Test
    fun bodyAngularVelocityMatchesRotationDerivative() {
        val s = spline()
        val h = 1e-6
        for (k in 3 until 40) {
            val t = s.startTime + (k.toDouble() / 44.0) * (s.endTime - s.startTime)
            val pose = s.pose(t)
            val rp = s.pose(t + h).rWc
            val rm = s.pose(t - h).rWc
            val rdot = DoubleArray(9) { i -> (rp[i] - rm[i]) / (2 * h) }
            val bodyRate = So3.mul(So3.transpose(pose.rWc), rdot)  // [omega_b]x
            val w = s.bodyAngularVelocity(t)
            val expected = So3.skew(w)
            for (i in 0 until 9) assertEquals(expected[i], bodyRate[i], 1e-5)
        }
    }

    @Test
    fun worldAngularVelocityMatchesWorldRotationDerivative() {
        val s = spline()
        val h = 1e-6
        for (k in 3 until 30) {
            val t = s.startTime + (k.toDouble() / 34.0) * (s.endTime - s.startTime)
            val r = s.pose(t).rWc
            val rp = s.pose(t + h).rWc
            val rm = s.pose(t - h).rWc
            val rdot = DoubleArray(9) { i -> (rp[i] - rm[i]) / (2 * h) }
            val worldRate = So3.mul(rdot, So3.transpose(r))  // [omega_w]x
            val w = s.worldAngularVelocity(t)
            val expected = So3.skew(w)
            for (i in 0 until 9) assertEquals(expected[i], worldRate[i], 1e-5)
        }
    }

    @Test
    fun accelerationMatchesFiniteDifferenceOfVelocity() {
        val s = spline()
        val h = 1e-4
        for (k in 3 until 30) {
            val t = s.startTime + (k.toDouble() / 34.0) * (s.endTime - s.startTime)
            val a = s.worldLinearAcceleration(t)
            val vp = s.spline.derivative(t + h, 1)
            val vm = s.spline.derivative(t - h, 1)
            for (d in 0 until 3) assertEquals((vp[d] - vm[d]) / (2 * h), a[d], 1e-3)
        }
    }

    @Test
    fun poseRotationIsAValidRotation() {
        val s = spline()
        for (k in 0 until 20) {
            val t = s.startTime + (k.toDouble() / 20.0) * (s.endTime - s.startTime) * 0.99
            assertEquals(1.0, So3.det(s.pose(t).rWc), 1e-9)
        }
    }
}
