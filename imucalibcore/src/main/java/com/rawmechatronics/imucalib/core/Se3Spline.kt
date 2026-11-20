package com.rawmechatronics.imucalib.core

/**
 * Pose of the camera (world <- camera) as a cubic SE(3) B-spline, Kalibr's
 * BSplinePose with a rotation-vector curve: translation and the rotation
 * vector theta are two cubic B-splines, R(t) = exp(theta(t)).
 *
 * All quantities are expressed with our own consistent convention:
 *  - R_wc maps camera coordinates to world coordinates;
 *  - thetaDot is the time derivative of the spline's rotation-vector curve;
 *  - body angular velocity omega_b = J_r(theta) * thetaDot,
 *    world angular velocity omega_w = R_wc * omega_b.
 */
class Se3Spline(val spline: BSpline) {

    init {
        require(spline.dimension == 6) { "SE(3) spline needs 6 dimensions" }
    }

    val startTime: Double get() = spline.startTime
    val endTime: Double get() = spline.endTime

    data class Pose(val rWc: DoubleArray, val pWc: DoubleArray)

    fun pose(t: Double): Pose {
        val v = spline.eval(t)
        return Pose(So3.exp(doubleArrayOf(v[3], v[4], v[5])), doubleArrayOf(v[0], v[1], v[2]))
    }

    /** Spline rotation curve theta [rad]. */
    fun theta(t: Double): DoubleArray {
        val v = spline.eval(t)
        return doubleArrayOf(v[3], v[4], v[5])
    }

    /** Body (camera-frame) angular velocity [rad/s]. */
    fun bodyAngularVelocity(t: Double): DoubleArray {
        val (_, deriv) = spline.evalWithDerivatives(t)
        val theta = doubleArrayOf(
            spline.eval(t)[3], spline.eval(t)[4], spline.eval(t)[5],
        )
        val thetaDot = doubleArrayOf(deriv[0][3], deriv[0][4], deriv[0][5])
        return So3.mulVec(So3.rightJacobian(theta), thetaDot)
    }

    /** World-frame angular velocity [rad/s]. */
    fun worldAngularVelocity(t: Double): DoubleArray {
        val pose = pose(t)
        return So3.mulVec(pose.rWc, bodyAngularVelocity(t))
    }

    /**
     * Body-frame angular acceleration [rad/s^2], by a central difference of the
     * body angular velocity. Analytic derivative is easy for a B-spline but the
     * expression is long; the calibration only needs it at IMU sample times.
     */
    fun bodyAngularAcceleration(t: Double): DoubleArray {
        val h = 1e-4
        val a = bodyAngularVelocity((t + h).coerceAtMost(endTime - 1e-9))
        val b = bodyAngularVelocity((t - h).coerceAtLeast(startTime))
        val denom = ((t + h).coerceAtMost(endTime - 1e-9)) - ((t - h).coerceAtLeast(startTime))
        return doubleArrayOf((a[0] - b[0]) / denom, (a[1] - b[1]) / denom, (a[2] - b[2]) / denom)
    }

    /** World-frame linear acceleration of the camera origin [m/s^2]. */
    fun worldLinearAcceleration(t: Double): DoubleArray {
        val d = spline.derivative(t, 2)
        return doubleArrayOf(d[0], d[1], d[2])
    }
}
