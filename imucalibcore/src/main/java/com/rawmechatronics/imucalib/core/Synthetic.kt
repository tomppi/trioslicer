package com.rawmechatronics.imucalib.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic ground-truth generator for the camera-IMU calibrator.
 *
 * A known smooth SE(3) trajectory is represented exactly by a cubic spline;
 * board poses (camera pose measurements in the board frame) and IMU samples
 * are generated from it with known T_cam_imu, time offset, biases and gravity.
 * This is the fixture the unit tests recover the parameters from.
 */
object Synthetic {

    private fun kotlin.random.Random.gauss(): Double {
        var u1 = nextDouble().coerceAtLeast(1e-12)
        var u2 = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    data class Scenario(
        val boardPoses: List<BoardPose>,
        val imu: List<ImuSample>,
        val truth: CalibParams,
        val trueSpline: Se3Spline,
        val gravityMagnitude: Double,
        val durationS: Double,
    )

    /** Smooth trajectory used as ground truth: translation and rotation-vector curves. */
    fun trajectoryCoeffs(knots: Int, dt: Double, t0: Double): Array<DoubleArray> {
        return Array(knots) { i ->
            val t = t0 + (i - 3) * dt
            doubleArrayOf(
                0.22 * sin(0.55 * t + 0.3) + 0.05 * sin(1.7 * t + 0.2),
                0.18 * cos(0.45 * t + 0.8) + 0.04 * cos(1.3 * t + 0.9),
                0.10 * sin(0.35 * t + 1.7) + 0.03 * sin(1.1 * t + 0.4),
                0.45 * sin(0.70 * t + 0.2) + 0.08 * sin(1.9 * t + 1.0),
                0.40 * cos(0.55 * t + 0.9) + 0.07 * cos(1.6 * t + 0.3),
                0.35 * sin(0.45 * t + 2.0) + 0.06 * sin(1.2 * t + 1.4),
            )
        }
    }

    fun defaultTruth(): CalibParams {
        val r = So3.exp(doubleArrayOf(-1.45, 0.08, 0.05))   // roughly -83 deg about x
        return CalibParams(
            rCi = So3.log(r),
            pCi = doubleArrayOf(0.018, -0.012, 0.004),
            td = 0.013,
            bg = doubleArrayOf(0.0012, -0.0021, 0.0006),
            ba = doubleArrayOf(0.060, -0.030, 0.020),
            gravityDir = unit(doubleArrayOf(0.02, -0.03, -1.0)),
        )
    }

    fun unit(v: DoubleArray): DoubleArray {
        val n = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    fun generate(
        seed: Int = 1,
        durationS: Double = 10.0,
        cameraRate: Double = 30.0,
        imuRate: Double = 240.0,
        knotDt: Double = 0.05,
        truth: CalibParams = defaultTruth(),
        noise: Boolean = false,
        gravityMagnitude: Double = 9.80665,
    ): Scenario {
        val rnd = Random(seed)
        val t0 = 0.0
        val segments = kotlin.math.ceil(durationS / knotDt).toInt()
        val knots = segments + 3
        val coeff = trajectoryCoeffs(knots, knotDt, t0)
        val spline = Se3Spline(BSpline(6, coeff, t0, knotDt))

        val board = ArrayList<BoardPose>()
        var t = t0 + 0.2
        while (t < spline.endTime - 0.2) {
            val pose = spline.pose(t)
            // The engine's board pose is the camera pose in the board frame:
            // X_board = R_bc * X_cam + t_bc, so rWc = R_bc and pWc = t_bc.
            var rMeas = pose.rWc
            var pMeas = pose.pWc
            if (noise) {
                val dR = So3.exp(doubleArrayOf(rnd.gauss() * 1.5e-3, rnd.gauss() * 1.5e-3, rnd.gauss() * 1.5e-3))
                rMeas = So3.mul(pose.rWc, dR)
                pMeas = doubleArrayOf(
                    pose.pWc[0] + rnd.gauss() * 8e-4,
                    pose.pWc[1] + rnd.gauss() * 8e-4,
                    pose.pWc[2] + rnd.gauss() * 8e-4,
                )
            }
            board.add(BoardPose(t, rMeas, pMeas))
            t += 1.0 / cameraRate
        }

        val gWorld = doubleArrayOf(
            gravityMagnitude * truth.gravityDir[0],
            gravityMagnitude * truth.gravityDir[1],
            gravityMagnitude * truth.gravityDir[2],
        )
        val rCamImu = So3.exp(truth.rCi)                 // camera <- imu
        val rImuCam = So3.transpose(rCamImu)             // imu <- camera
        val imu = ArrayList<ImuSample>()
        var tm = spline.startTime + 0.05
        val imuDt = 1.0 / imuRate
        while (tm < spline.endTime - 0.05) {
            val tEval = tm - truth.td
            if (tEval < spline.startTime || tEval >= spline.endTime) { tm += imuDt; continue }
            val v = spline.spline.evalWithDerivatives(tEval)
            val theta = doubleArrayOf(v.first[3], v.first[4], v.first[5])
            val thetaDot = doubleArrayOf(v.second[0][3], v.second[0][4], v.second[0][5])
            val pose = spline.pose(tEval)
            val wBody = So3.mulVec(So3.rightJacobian(theta), thetaDot)
            val wWorld = So3.mulVec(pose.rWc, wBody)
            val aCamWorld = doubleArrayOf(v.second[1][0], v.second[1][1], v.second[1][2])
            val alphaBody = spline.bodyAngularAcceleration(tEval)
            val alphaWorld = So3.mulVec(pose.rWc, alphaBody)
            val rWorld = So3.mulVec(pose.rWc, truth.pCi)
            val wxr = cross(wWorld, rWorld)
            val aPoint = doubleArrayOf(
                aCamWorld[0] + cross(alphaWorld, rWorld)[0] + cross(wWorld, wxr)[0],
                aCamWorld[1] + cross(alphaWorld, rWorld)[1] + cross(wWorld, wxr)[1],
                aCamWorld[2] + cross(alphaWorld, rWorld)[2] + cross(wWorld, wxr)[2],
            )
            val gyroPred = So3.mulVec(rImuCam, wBody)
            val specificForce = So3.mulVec(rImuCam, So3.transposeMulVec(pose.rWc, doubleArrayOf(aPoint[0] - gWorld[0], aPoint[1] - gWorld[1], aPoint[2] - gWorld[2])))
            var gyro = doubleArrayOf(gyroPred[0] + truth.bg[0], gyroPred[1] + truth.bg[1], gyroPred[2] + truth.bg[2])
            var accel = doubleArrayOf(specificForce[0] + truth.ba[0], specificForce[1] + truth.ba[1], specificForce[2] + truth.ba[2])
            if (noise) {
                for (k in 0 until 3) {
                    gyro[k] += rnd.gauss() * 3.0e-3
                    accel[k] += rnd.gauss() * 2.0e-2
                }
            }
            // acceleration measured at the IMU origin: the expression above already
            // includes the rotation of the specific force into the IMU frame
            imu.add(ImuSample(tm, gyro, accel))
            tm += imuDt
        }
        return Scenario(board, imu, truth, spline, gravityMagnitude, durationS)
    }

    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
}