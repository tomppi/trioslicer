package com.rawmechatronics.imucalib.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small SO(3) kit: row-major 3x3 rotation matrices and rotation vectors. */
object So3 {

    fun identity(): DoubleArray = doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0,
    )

    fun skew(w: DoubleArray): DoubleArray = doubleArrayOf(
        0.0, -w[2], w[1],
        w[2], 0.0, -w[0],
        -w[1], w[0], 0.0,
    )

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[r * 3 + k] * b[k * 3 + c]
            out[r * 3 + c] = s
        }
        return out
    }

    fun transpose(a: DoubleArray): DoubleArray = doubleArrayOf(
        a[0], a[3], a[6],
        a[1], a[4], a[7],
        a[2], a[5], a[8],
    )

    fun mulVec(a: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        a[0] * v[0] + a[1] * v[1] + a[2] * v[2],
        a[3] * v[0] + a[4] * v[1] + a[5] * v[2],
        a[6] * v[0] + a[7] * v[1] + a[8] * v[2],
    )

    fun transposeMulVec(a: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        a[0] * v[0] + a[3] * v[1] + a[6] * v[2],
        a[1] * v[0] + a[4] * v[1] + a[7] * v[2],
        a[2] * v[0] + a[5] * v[1] + a[8] * v[2],
    )

    /** Rodrigues: rotation vector (rad, axis*angle) -> rotation matrix. */
    fun exp(w: DoubleArray): DoubleArray {
        val th = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
        if (th < 1e-12) return identity()
        val k = doubleArrayOf(w[0] / th, w[1] / th, w[2] / th)
        val kx = skew(k)
        val s = sin(th)
        val c = 1.0 - cos(th)
        val out = identity()
        for (i in 0 until 9) out[i] += s * kx[i]
        val kx2 = mul(kx, kx)
        for (i in 0 until 9) out[i] += c * kx2[i]
        return out
    }

    /** Rotation matrix -> rotation vector, angle in [0, pi]. */
    fun log(r: DoubleArray): DoubleArray {
        val tr = (r[0] + r[4] + r[8] - 1.0) / 2.0
        val c = tr.coerceIn(-1.0, 1.0)
        val th = kotlin.math.acos(c)
        if (th < 1e-9) {
            return doubleArrayOf((r[7] - r[5]) / 2, (r[2] - r[6]) / 2, (r[3] - r[1]) / 2)
        }
        if (abs(th - Math.PI) < 1e-6) {
            // near pi: use the symmetric part to pick the axis
            val x = sqrt(((r[0] + 1.0) / 2.0).coerceIn(0.0, 1.0))
            val y = sqrt(((r[4] + 1.0) / 2.0).coerceIn(0.0, 1.0))
            val z = sqrt(((r[8] + 1.0) / 2.0).coerceIn(0.0, 1.0))
            val sign = if (r[7] - r[5] >= 0) 1.0 else -1.0
            val axis = doubleArrayOf(x * sign, y, z)
            val n = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
            if (n < 1e-12) return doubleArrayOf(0.0, 0.0, 0.0)
            return doubleArrayOf(axis[0] / n * th, axis[1] / n * th, axis[2] / n * th)
        }
        val f = th / (2.0 * sin(th))
        return doubleArrayOf(
            (r[7] - r[5]) * f,
            (r[2] - r[6]) * f,
            (r[3] - r[1]) * f,
        )
    }

    fun angleDeg(r: DoubleArray): Double {
        val c = ((r[0] + r[4] + r[8] - 1.0) / 2.0).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(c))
    }

    fun isRotation(r: DoubleArray, tol: Double = 1e-9): Boolean {
        val rt = transpose(r)
        val rrt = mul(r, rt)
        val id = identity()
        for (i in 0 until 9) if (abs(rrt[i] - id[i]) > tol) return false
        return abs(det(r) - 1.0) < tol
    }

    /**
     * Right Jacobian of SO(3): J_r(theta), with exp(theta + d) = exp(theta) exp(J_r(theta) d).
     * Body angular velocity of R(t)=exp(theta(t)) is omega = J_r(theta) * thetaDot.
     */
    fun rightJacobian(theta: DoubleArray): DoubleArray {
        val th = sqrt(theta[0] * theta[0] + theta[1] * theta[1] + theta[2] * theta[2])
        if (th < 1e-10) return identity()
        val k = skew(theta)
        val k2 = mul(k, k)
        val a = (1.0 - cos(th)) / (th * th)
        val b = (th - sin(th)) / (th * th * th)
        val out = identity()
        for (i in 0 until 9) {
            out[i] += -a * k[i] + b * k2[i]
        }
        return out
    }

    fun leftJacobian(theta: DoubleArray): DoubleArray = transpose(rightJacobian(theta))

    fun det(r: DoubleArray): Double =
        r[0] * (r[4] * r[8] - r[5] * r[7]) -
        r[1] * (r[3] * r[8] - r[5] * r[6]) +
        r[2] * (r[3] * r[7] - r[4] * r[6])
}