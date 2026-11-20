package com.rawmechatronics.imucalib.core

/** One measured camera pose in the calibration world (the board frame). */
data class BoardPose(
    /** Time [s] on the camera clock (Camera2 exposure timestamp). */
    val t: Double,
    /** Rotation world <- camera, row-major 3x3. */
    val rWc: DoubleArray,
    /** Camera position in world (board) coordinates [m]. */
    val pWc: DoubleArray,
    /** 1-sigma rotation measurement noise [rad]. */
    val sigmaRot: Double = 1.5e-3,
    /** 1-sigma translation measurement noise [m]. */
    val sigmaTrans: Double = 8e-4,
)

/** One IMU sample on the IMU clock; gyro [rad/s], accel [m/s^2], raw (bias included). */
data class ImuSample(
    val t: Double,
    val gyro: DoubleArray,
    val accel: DoubleArray,
)

/**
 * Camera-IMU calibration parameters that are not part of the pose spline.
 * The extrinsic is T_cam_imu: [rCi] maps an IMU-frame vector into the camera
 * frame (R_imu<-cam = exp(rCi)), so the IMU origin is p_ci in camera
 * coordinates; td makes the two clocks agree (imu_time = camera_time + td).
 */
data class CalibParams(
    val rCi: DoubleArray,
    val pCi: DoubleArray,
    val td: Double,
    val bg: DoubleArray,
    val ba: DoubleArray,
    /** Gravity direction in world coordinates; unit vector. */
    val gravityDir: DoubleArray,
) {
    fun copyOf(): CalibParams = copy(
        rCi = rCi.copyOf(), pCi = pCi.copyOf(), bg = bg.copyOf(), ba = ba.copyOf(),
        gravityDir = gravityDir.copyOf(),
    )
}

data class CalibOptions(
    /** Uniform spline knot spacing. Coarser than Kalibr's pixel-observation default: with
     *  board-pose priors (6 numbers/frame instead of many corner pixels) denser knots fit
     *  the measurement noise and destroy the IMU-derived acceleration. */
    val splineDt: Double = 0.2,
    val gravityMagnitude: Double = 9.80665,
    /** Continuous-time white-noise densities (Kalibr imu chain defaults). */
    val gyroNoiseDensity: Double = 1.4e-4,
    val accelNoiseDensity: Double = 6.0e-4,
    /** Max LM iterations and convergence threshold on the parameter step. */
    /** Estimate the lever arm as a free parameter; off keeps it at zero with a tight prior. */
    val estimateLeverArm: Boolean = false,
    /** Run the calibration-only pre-solve before the full LM. */
    val stageCalibOnly: Boolean = false,
    /** Weak prior sigma on the lever arm [m]; the term is weakly observable without enough rotation. */
    val leverArmSigma: Double = 0.05,
    /** Keep every Nth IMU sample in the batch (1 = all; the spline cannot follow 480 Hz anyway). */
    val imuSubsample: Int = 4,
    val maxIterations: Int = 30,
    val stepTolerance: Double = 1e-8,
    /** Huber-ish robust threshold in sigmas for the pose measurements, 0 disables. */
    val robustSigma: Double = 0.0,
    /** When ChArUco corner observations are available: their pixel sigma and whether the
     *  board-pose prior is still part of the cost (Kalibr uses pixels only; keep the prior
     *  off with pixel reprojection, it is a lossier summary of the same corners). */
    val pixelSigmaPx: Double = 0.35,
    val useBoardPosePrior: Boolean = true,
    /** Fix the IMU sigmas [rad/s, m/s^2] instead of estimating them from the quiet window;
     *  use the discrete noise of the IMU model the VIO runs with (Kalibr's value). */
    val gyroSigmaOverride: Double = 0.0,
    val accelSigmaOverride: Double = 0.0,
    /** Pixel sigma for ArUco marker corners (they carry a ~1 px board-model error);
     *  ChArUco corners use [pixelSigmaPx]. */
    val markerSigmaPx: Double = 1.0,
    /** Optional prior on the camera-IMU time offset [s] and its sigma; used to test
     *  whether a lever-arm component is traded against a wrong time offset. */
    val tdPriorTarget: Double = Double.NaN,
    val tdPriorSigma: Double = 0.0,
    /** Hard-fix the camera-IMU time offset [s] (NaN = estimate it). */
    val fixedTimeOffset: Double = Double.NaN,
    /** Bias splines: bias knots per second (0 = constant biases as before). Kalibr models
     *  gyro/accel bias as splines (~70 knots/s); a coarse piecewise-linear bias removes the
     *  drift that otherwise shows up as IMU residual and biases the lever arm. */
    val biasKnotsPerSecond: Double = 0.0,
    val biasSmoothSigmaG: Double = 0.01,
    val biasSmoothSigmaA: Double = 0.05,
)