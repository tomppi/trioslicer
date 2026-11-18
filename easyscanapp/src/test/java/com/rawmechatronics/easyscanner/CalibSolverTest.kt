package com.rawmechatronics.easyscanner

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Synthetic check of the on-phone camera-IMU fit (no device needed). */
class CalibSolverTest {

    private fun axisAngle(ax: DoubleArray, ang: Double): Array<DoubleArray> {
        val c = cos(ang)
        val s = sin(ang)
        val t = 1.0 - c
        val x = ax[0]
        val y = ax[1]
        val z = ax[2]
        return arrayOf(
            doubleArrayOf(t * x * x + c, t * x * y - s * z, t * x * z + s * y),
            doubleArrayOf(t * x * y + s * z, t * y * y + c, t * y * z - s * x),
            doubleArrayOf(t * x * z - s * y, t * y * z + s * x, t * z * z + c),
        )
    }

    private fun rotvecToMat(deg: DoubleArray): Array<DoubleArray> {
        val rv = doubleArrayOf(Math.toRadians(deg[0]), Math.toRadians(deg[1]), Math.toRadians(deg[2]))
        val ang = sqrt(rv[0] * rv[0] + rv[1] * rv[1] + rv[2] * rv[2])
        if (ang < 1e-12) return arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        return axisAngle(doubleArrayOf(rv[0] / ang, rv[1] / ang, rv[2] / ang), ang)
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val c = arrayOf(DoubleArray(3), DoubleArray(3), DoubleArray(3))
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[i][k] * b[k][j]
            c[i][j] = s
        }
        return c
    }

    private fun transpose(a: Array<DoubleArray>) = arrayOf(
        doubleArrayOf(a[0][0], a[1][0], a[2][0]),
        doubleArrayOf(a[0][1], a[1][1], a[2][1]),
        doubleArrayOf(a[0][2], a[1][2], a[2][2]),
    )

    private fun matToRotvec(r: Array<DoubleArray>): DoubleArray {
        val tr = r[0][0] + r[1][1] + r[2][2]
        val ang = Math.acos(((tr - 1.0) / 2.0).coerceIn(-1.0, 1.0))
        if (ang < 1e-9) return DoubleArray(3)
        val k = ang / (2.0 * sin(ang))
        return doubleArrayOf(k * (r[2][1] - r[1][2]), k * (r[0][2] - r[2][0]), k * (r[1][0] - r[0][1]))
    }

    @Test
    fun recoversCameraImuRotationAndTimeOffset() {
        val aTrue = rotvecToMat(doubleArrayOf(25.0, -40.0, 65.0)) // camera->IMU rotation (test A)
        val dtTrue = 0.012
        val biasTrue = doubleArrayOf(0.002, -0.001, 0.0015)

        val n = 120
        val dt = 1.0 / 30.0
        val frames = ArrayList<CalibSolver.FrameRow>()
        val imu = ArrayList<CalibSolver.Imu>()

        // smooth random-ish orientation walk for the camera in the board frame
        var r = rotvecToMat(doubleArrayOf(5.0, -10.0, 20.0))
        val rng = java.util.Random(7)
        val t0 = 100.0
        for (i in 0 until n) {
            val angle = 0.25 + rng.nextDouble() * 0.15 // rad/s? (rad per step)
            val ax = doubleArrayOf(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian())
            val norm = sqrt(ax[0] * ax[0] + ax[1] * ax[1] + ax[2] * ax[2])
            val dR = axisAngle(doubleArrayOf(ax[0] / norm, ax[1] / norm, ax[2] / norm), angle)
            r = mul(r, dR)
            // camera->board matrix m = [R | p]
            val m = DoubleArray(16)
            for (row in 0..2) for (col in 0..2) m[row * 4 + col] = r[row][col]
            m[15] = 1.0
            frames.add(CalibSolver.FrameRow(t0 + i * dt, m, null))
            if (i > 0) {
                val prev = frames[i - 1].m
                val r0 = arrayOf(
                    doubleArrayOf(prev[0], prev[1], prev[2]),
                    doubleArrayOf(prev[4], prev[5], prev[6]),
                    doubleArrayOf(prev[8], prev[9], prev[10]),
                )
                val dRc = mul(transpose(r0), r)
                // gyro integrates the body rotation: dRb = A^T dRc A
                val dRb = mul(mul(transpose(aTrue), dRc), aTrue)
                val rv = matToRotvec(dRb)
                // sample the gyro at 480 Hz over [t0+dt_i+dtTrue, t0+dt_(i+1)+dtTrue]
                val s0 = t0 + (i - 1) * dt + dtTrue
                val s1 = t0 + i * dt + dtTrue
                val steps = 8
                for (k in 0..steps) {
                    val tt = s0 + (s1 - s0) * k / steps
                    val w = doubleArrayOf(
                        rv[0] / (s1 - s0) + biasTrue[0],
                        rv[1] / (s1 - s0) + biasTrue[1],
                        rv[2] / (s1 - s0) + biasTrue[2],
                    )
                    imu.add(CalibSolver.Imu('g', tt, w[0], w[1], w[2]))
                }
            }
        }
        imu.sortBy { it.ts }

        val fit = CalibSolver.fitCameraImu(frames, imu, doubleArrayOf(0.0, 0.0, 0.0))
        println("fit: rot=" + fit.rotDeg.contentToString() + " dt=" + fit.dt + " rms=" + fit.rmsDeg + " pairs=" + fit.pairs)
        val truth = doubleArrayOf(25.0, -40.0, 65.0)
        // compare via rotation matrices (rotvec representation can wrap)
        val aFit = rotvecToMat(fit.rotDeg)
        val diff = mul(transpose(aFit), aTrue)
        val angErr = Math.toDegrees(Math.acos(((diff[0][0] + diff[1][1] + diff[2][2] - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
        assertTrue("pairs too few: " + fit.pairs, fit.pairs > 50)
        assertTrue("rotation error too large: " + angErr, angErr < 0.5)
        assertTrue("time offset error too large: " + fit.dt, abs(fit.dt - dtTrue) < 0.004)
        assertTrue("rms too large: " + fit.rmsDeg, fit.rmsDeg < 0.2)
    }
}
