
package com.rawmechatronics.imucalib.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Continuous-time camera-IMU batch calibration: Kalibr's formulation, ported
 * to plain Kotlin and adapted to board-pose measurements.
 *
 * Unknowns: a uniform cubic SE(3) spline for the camera trajectory (translation
 * and rotation-vector curves) plus T_cam_imu (rotation + lever arm), the
 * camera-IMU time offset, gyro/accel biases and the gravity direction in the
 * board frame.
 *
 * Residuals:
 *  - board pose measurement: rotation error in the camera frame and translation
 *    error rotated into the camera frame, weighted by the board solver sigmas;
 *  - gyro: measured vs predicted specific angular rate, with R_cam<-imu, bias and
 *    the IMU time offset applied (t_spline = t_imu - td);
 *  - accelerometer: measured specific force at the IMU origin vs the spline's
 *    second derivative plus lever-arm terms, gravity, R_cam<-imu and accel bias.
 *
 * The normal equations keep the spline block banded (a cubic B-spline residual
 * touches four knots) and eliminate it with a Schur complement for the small
 * calibration block, exactly the structure Kalibr delegates to Ceres/SuiteSparse.
 * Jacobians are numeric over the local parameter window (40 parameters at most
 * per IMU residual), which is small enough for an offline phone solve.
 */
class KalibrCalibrator(
    boardIn: List<BoardPose>,
    imuIn: List<ImuSample>,
    private val options: CalibOptions = CalibOptions(),
    private val corners: List<CornerObservation> = emptyList(),
    private val camera: PinholeModel? = null,
    private val boardLayout: BoardLayout = BoardLayout(),
    private val pixelBatch: PixelBatchEvaluator? = null,
) {
    private val board = boardIn.sortedBy { it.t }
    private val usePixels = corners.isNotEmpty() && camera != null
    private val imu = imuIn.sortedBy { it.t }
    private val imuSub: List<ImuSample>
    private val t0: Double
    private val nKnots: Int
    private val coeff: Array<DoubleArray>
    private val spline: Se3Spline
    // Bias splines add 6 dense calibration parameters per knot (gyro xyz, accel xyz).
    // They are time-local, so an IMU row only touches the two knots around it.
    private val duration: Double = run {
        val first = min(boardIn.minOf { it.t }, imuIn.minOf { it.t })
        val last = max(boardIn.maxOf { it.t }, imuIn.maxOf { it.t })
        last - first + 0.4
    }
    private val nBiasKnots: Int = if (options.biasKnotsPerSecond > 0.0)
        max(2, ceil(duration * options.biasKnotsPerSecond).toInt() + 1) else 0
    private val NC: Int = 16 + nBiasKnots * 6
    private val biasDt: Double = if (nBiasKnots > 1) duration / (nBiasKnots - 1) else 1.0
    private val xc = DoubleArray(NC)
    // Pixel observations packed for the optional batch evaluator (GPU path):
    // per-observation base knot, 6 basis values, board point (3) and pixel (2).
    private val obsBase: IntArray
    private val obsBasis: DoubleArray
    private val obsXyz: DoubleArray
    private val obsUv: DoubleArray
    private val obsValid: BooleanArray
    private val nS: Int
    private val splineOrder = 6
    private val band = splineOrder * 6 - 1
    private var sigmaG: Double
    private var sigmaA: Double
    private val sigmaGFloor: Double
    private val sigmaAFloor: Double
    private val sigmaRot: Double
    private val sigmaTrans: Double

    /** Optional progress callback (iteration, cost) called after every accepted LM step. */
    @Volatile var progress: ((Int, Double) -> Unit)? = null

    init {
        require(board.size >= 30) { "need at least 30 board poses, got " + board.size }
        require(imu.size >= 200) { "need at least 200 IMU samples, got " + imu.size }
        val first = min(board.first().t, imu.first().t) - 0.2
        val last = max(board.last().t, imu.last().t) + 0.2
        t0 = first
        val dt = options.splineDt
        nKnots = ceil((last - first) / dt).toInt() + splineOrder
        coeff = Array(nKnots) { DoubleArray(6) }
        spline = Se3Spline(BSpline(6, coeff, t0, dt, order = splineOrder))
        nS = nKnots * 6
        val step = options.imuSubsample.coerceAtLeast(1)
        imuSub = ArrayList<ImuSample>()
        var i = 0
        while (i < imu.size) { imuSub.add(imu[i]); i += step }
        val dts = imu.zipWithNext { a, b -> b.t - a.t }.filter { it > 0 }.sorted()
        val rawDt = if (dts.isEmpty()) 1.0 / 400.0 else dts[dts.size / 2]
        val rawRate = 1.0 / rawDt
        sigmaG = options.gyroNoiseDensity * sqrt(rawRate)
        sigmaA = options.accelNoiseDensity * sqrt(rawRate)
        sigmaGFloor = sigmaG
        sigmaAFloor = sigmaA
        sigmaRot = board.map { it.sigmaRot }.sorted()[board.size / 2]
        sigmaTrans = board.map { it.sigmaTrans }.sorted()[board.size / 2]
        xc.fill(0.0)
        xc[13] = 0.0
        xc[14] = 0.0
        xc[15] = -1.0

        // Pack the pixel observations for the optional batch evaluator.
        val nObs = corners.size
        obsBase = IntArray(nObs)
        obsBasis = DoubleArray(nObs * 6)
        obsXyz = DoubleArray(nObs * 3)
        obsUv = DoubleArray(nObs * 2)
        obsValid = BooleanArray(nObs)
        for (oi in 0 until nObs) {
            val o = corners[oi]
            val te = o.t
            val valid = camera != null && te >= spline.startTime + 1e-6 && te < spline.endTime - 1e-6
            obsValid[oi] = valid
            var base = 0
            var b = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            var xyz: DoubleArray? = null
            if (valid) {
                base = floor((te - t0) / options.splineDt).toInt().coerceIn(0, nKnots - splineOrder)
                b = spline.spline.basis(te).b
                xyz = o.xyz ?: boardLayout.corner3d(o.id)
            }
            obsBase[oi] = base
            for (k in 0 until 6) obsBasis[oi * 6 + k] = b[k]
            if (xyz != null) for (d in 0 until 3) obsXyz[oi * 3 + d] = xyz!![d]
            obsUv[oi * 2] = o.u
            obsUv[oi * 2 + 1] = o.v
        }
    }

    data class Diagnostics(
        val iterations: Int,
        val cost: Double,
        val rmsBoardRotDeg: Double,
        val rmsBoardTransMm: Double,
        val rmsGyro: Double,
        val rmsAccel: Double,
        val boardRows: Int,
        val imuRows: Int,
        val converged: Boolean,
        /** RMS ChArUco reprojection error [px]; NaN when the session has no corners. */
        val rmsPixelPx: Double = Double.NaN,
    )

    data class Result(val params: CalibParams, val diagnostics: Diagnostics, val spline: Se3Spline)

    // ---------------------------------------------------------------- public

    fun calibrate(initialTd: Double = 0.0): Result {
        initializeSpline()
        xc.fill(0.0)
        val rotAndOffset = initRotationAndOffset(initialTd)
        for (k in 0 until 3) xc[k] = rotAndOffset[k]
        xc[6] = rotAndOffset[3]
        xc[13] = 0.0
        xc[14] = 0.0
        xc[15] = -1.0

        initializeFromQuietWindow()
        if (!options.tdPriorTarget.isNaN()) xc[6] = options.tdPriorTarget
        if (!options.fixedTimeOffset.isNaN()) xc[6] = options.fixedTimeOffset
        // First fit only the calibration block with the spline held at the pose measurement fit;
        // this avoids LM landing in a bad basin from a poor long-baseline initialization.
        if (options.stageCalibOnly) calibrateCalibOnly(20)
        var cost = totalCost()
        var lambda = 1e-3
        var converged = false
        var iteration = 0
        var lastDx = Double.MAX_VALUE
        while (iteration < options.maxIterations) {
            iteration++
            val sys = buildNormalSystem()
            var accepted = false
            var attempts = 0
            while (attempts < 10 && !accepted) {
                val dx = solveNormalSystem(sys, lambda)
                if (dx == null) {
                    lambda *= 10.0
                    attempts++
                    continue
                }
                // clamp/normalize inside applyStep is not invertible (gravity direction is
                // re-normalized), so a rejected trial must restore the exact previous state.
                val xcSave = xc.copyOf()
                val coeffSave = Array(coeff.size) { coeff[it].copyOf() }
                applyStep(dx)
                val newCost = totalCost()
                if (newCost < cost) {
                    cost = newCost
                    lambda = max(lambda * 0.3, 1e-9)
                    accepted = true
                    lastDx = dx.maxOf { abs(it) }
                } else {
                    System.arraycopy(xcSave, 0, xc, 0, xc.size)
                    for (i in coeff.indices) System.arraycopy(coeffSave[i], 0, coeff[i], 0, coeff[i].size)
                    lambda *= 5.0
                    attempts++
                }
            }
            if (!accepted) break
            progress?.invoke(iteration, cost)
            if (lastDx < options.stepTolerance) { converged = true; break }
        }
        val params = paramsFromState()
        val diag = diagnostics(iteration, converged)
        return Result(params, diag, spline)
    }

    /**
     * Bias and gravity initialization from the quietest IMU window: there the specific
     * force is gravity only, which gives the gravity direction in the board frame once
     * the initial spline attitude and extrinsic rotation are known.
     */
    private fun initializeFromQuietWindow() {
        val dt = 0.05
        val win = 3.0
        val n = (win / dt).toInt().coerceAtLeast(10)
        if (imu.size < n + 10) return
        var best = 0
        var bestScore = Double.MAX_VALUE
        var i = 0
        while (i + n <= imu.size) {
            var gyroMean = 0.0
            val accMean = DoubleArray(3)
            var accVar = 0.0
            for (j in i until i + n) {
                val s = imu[j]
                gyroMean += kotlin.math.sqrt(s.gyro[0] * s.gyro[0] + s.gyro[1] * s.gyro[1] + s.gyro[2] * s.gyro[2])
                for (k in 0 until 3) accMean[k] += s.accel[k]
            }
            gyroMean /= n
            for (k in 0 until 3) accMean[k] /= n
            for (j in i until i + n) {
                val s = imu[j]
                for (k in 0 until 3) {
                    val d = s.accel[k] - accMean[k]
                    accVar += d * d
                }
            }
            val score = gyroMean + accVar / n
            if (score < bestScore) { bestScore = score; best = i }
            i += (n / 3).coerceAtLeast(1)
        }
        val gyroBias = DoubleArray(3)
        val accMean = DoubleArray(3)
        for (j in best until best + n) {
            for (k in 0 until 3) {
                gyroBias[k] += imu[j].gyro[k]
                accMean[k] += imu[j].accel[k]
            }
        }
        for (k in 0 until 3) {
            gyroBias[k] /= n
            accMean[k] /= n
        }
        for (k in 0 until 3) xc[7 + k] = gyroBias[k]
        for (k in 0 until 3) xc[10 + k] = 0.0
        if (nBiasKnots > 0) {
            for (i in 0 until nBiasKnots) {
                for (k in 0 until 3) {
                    xc[16 + i * 6 + k] = gyroBias[k]
                    xc[16 + i * 6 + 3 + k] = 0.0
                }
            }
        }
        // Per-sample noise from the quiet window: first-difference std / sqrt(2). Phone IMUs
        // are far noisier than the Kalibr defaults, and weighting them like lab sensors bends
        // the spline; this keeps the IMU terms at their measured noise level.
        var gVar = 0.0
        var aVar = 0.0
        var count = 0
        for (j in best + 1 until best + n) {
            for (k in 0 until 3) {
                val dg = imu[j].gyro[k] - imu[j - 1].gyro[k]
                val da = imu[j].accel[k] - imu[j - 1].accel[k]
                gVar += dg * dg
                aVar += da * da
                count++
            }
        }
        if (count > 0) {
            // never make the IMU terms tighter than the model/Jacobian can justify
            sigmaG = kotlin.math.sqrt(gVar / count / 2.0).coerceIn(sigmaGFloor, 1.0)
            sigmaA = kotlin.math.sqrt(aVar / count / 2.0).coerceIn(sigmaAFloor, 5.0)
        }
        if (options.gyroSigmaOverride > 0.0) sigmaG = options.gyroSigmaOverride
        if (options.accelSigmaOverride > 0.0) sigmaA = options.accelSigmaOverride
        val mid = (imu[best].t + imu[best + n - 1].t) / 2.0
        val pose = spline.pose(mid)
        val rCamImu = So3.exp(doubleArrayOf(xc[0], xc[1], xc[2]))
        val rWorldImu = So3.mul(pose.rWc, rCamImu)
        val gWorld = So3.mulVec(rWorldImu, doubleArrayOf(-accMean[0], -accMean[1], -accMean[2]))
        val u = unit(gWorld)
        xc[13] = u[0]; xc[14] = u[1]; xc[15] = u[2]
    }

    private fun addDifferencePrior(a: Int, b: Int, sigma: Double, hcc: DoubleArray, gc: DoubleArray): Double {
        if (!sigma.isFinite() || sigma <= 0.0) return 0.0
        val s2 = sigma * sigma
        val v = (xc[a] - xc[b]) / sigma
        gc[a] += v / sigma
        gc[b] -= v / sigma
        hcc[a * NC + a] += 1.0 / s2
        hcc[b * NC + b] += 1.0 / s2
        hcc[a * NC + b] -= 1.0 / s2
        hcc[b * NC + a] -= 1.0 / s2
        return v * v
    }

    private fun leverSigma(): Double = if (options.estimateLeverArm) options.leverArmSigma else 1e-4

    private fun leverPriorCost(): Double {
        val s = leverSigma()
        var c = 0.0
        for (k in 0 until 3) { val v = xc[3 + k] / s; c += v * v }
        return c
    }

    // ------------------------------------------------------------ residuals

    private fun paramsFromState(): CalibParams = CalibParams(
        rCi = doubleArrayOf(xc[0], xc[1], xc[2]),
        pCi = doubleArrayOf(xc[3], xc[4], xc[5]),
        td = xc[6],
        bg = doubleArrayOf(xc[7], xc[8], xc[9]),
        ba = doubleArrayOf(xc[10], xc[11], xc[12]),
        gravityDir = unit(doubleArrayOf(xc[13], xc[14], xc[15])),
    )

    private fun setCalib(p: CalibParams) {
        for (k in 0 until 3) {
            xc[k] = p.rCi[k]
            xc[3 + k] = p.pCi[k]
            xc[7 + k] = p.bg[k]
            xc[10 + k] = p.ba[k]
        }
        xc[6] = p.td
        val u = unit(p.gravityDir)
        xc[13] = u[0]; xc[14] = u[1]; xc[15] = u[2]
    }

    private fun unit(v: DoubleArray): DoubleArray {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (n < 1e-12) return doubleArrayOf(0.0, 0.0, -1.0)
        return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    /** Residual of one board pose: 3 rotation [rad] then 3 translation [m], weighted. */
    private fun boardResidual(bp: BoardPose): DoubleArray {
        val pose = spline.pose(bp.t)
        val dR = So3.mul(So3.transpose(bp.rWc), pose.rWc)
        val rot = So3.log(dR)
        val dp = doubleArrayOf(pose.pWc[0] - bp.pWc[0], pose.pWc[1] - bp.pWc[1], pose.pWc[2] - bp.pWc[2])
        val tp = So3.transposeMulVec(bp.rWc, dp)
        return doubleArrayOf(
            rot[0] / sigmaRot, rot[1] / sigmaRot, rot[2] / sigmaRot,
            tp[0] / sigmaTrans, tp[1] / sigmaTrans, tp[2] / sigmaTrans,
        )
    }

    private data class Prediction(val gyro: DoubleArray, val accel: DoubleArray)

    /** Specific force and angular rate an IMU at T_cam_imu would measure at spline time [tEval]. */
    /** Bias at a pose time: constants, or the piecewise-linear bias spline when enabled. */
    private fun biasAt(t: Double, bg: DoubleArray, ba: DoubleArray) {
        if (nBiasKnots == 0) {
            for (k in 0 until 3) { bg[k] = xc[7 + k]; ba[k] = xc[10 + k] }
            return
        }
        val u = ((t - t0) / biasDt).coerceIn(0.0, (nBiasKnots - 1).toDouble())
        val i = min(floor(u).toInt(), nBiasKnots - 2)
        val w = u - i
        val a = 16 + i * 6
        val b = a + 6
        for (k in 0 until 3) {
            bg[k] = (1.0 - w) * xc[a + k] + w * xc[b + k]
            ba[k] = (1.0 - w) * xc[a + 3 + k] + w * xc[b + 3 + k]
        }
    }

    private fun imuPrediction(tEval: Double): Prediction {
        val (v, d) = spline.spline.evalWithDerivatives(tEval)
        val theta = doubleArrayOf(v[3], v[4], v[5])
        val thetaDot = doubleArrayOf(d[0][3], d[0][4], d[0][5])
        val rWc = So3.exp(doubleArrayOf(v[3], v[4], v[5]))
        val pWc = doubleArrayOf(v[0], v[1], v[2])
        val wBody = So3.mulVec(So3.rightJacobian(theta), thetaDot)
        val wWorld = So3.mulVec(rWc, wBody)
        val aCamWorld = doubleArrayOf(d[1][0], d[1][1], d[1][2])
        val alphaBody = spline.bodyAngularAcceleration(tEval)
        val alphaWorld = So3.mulVec(rWc, alphaBody)
        val pCi = doubleArrayOf(xc[3], xc[4], xc[5])
        val rWorld = So3.mulVec(rWc, pCi)
        val wxr = Synthetic.cross(wWorld, rWorld)
        val aPoint = doubleArrayOf(
            aCamWorld[0] + Synthetic.cross(alphaWorld, rWorld)[0] + Synthetic.cross(wWorld, wxr)[0],
            aCamWorld[1] + Synthetic.cross(alphaWorld, rWorld)[1] + Synthetic.cross(wWorld, wxr)[1],
            aCamWorld[2] + Synthetic.cross(alphaWorld, rWorld)[2] + Synthetic.cross(wWorld, wxr)[2],
        )
        val rCamImu = So3.exp(doubleArrayOf(xc[0], xc[1], xc[2]))
        val rImuCam = So3.transpose(rCamImu)
        val gyro = So3.mulVec(rImuCam, wBody)
        val bg = DoubleArray(3)
        val ba = DoubleArray(3)
        biasAt(tEval, bg, ba)
        val gDir = unit(doubleArrayOf(xc[13], xc[14], xc[15]))
        val g = doubleArrayOf(
            options.gravityMagnitude * gDir[0],
            options.gravityMagnitude * gDir[1],
            options.gravityMagnitude * gDir[2],
        )
        val specific = So3.mulVec(
            rImuCam,
            So3.transposeMulVec(rWc, doubleArrayOf(aPoint[0] - g[0], aPoint[1] - g[1], aPoint[2] - g[2])),
        )
        return Prediction(
            gyro = doubleArrayOf(gyro[0] + bg[0], gyro[1] + bg[1], gyro[2] + bg[2]),
            accel = doubleArrayOf(specific[0] + ba[0], specific[1] + ba[1], specific[2] + ba[2]),
        )
    }

    /** Residual of one IMU sample: 3 gyro then 3 accel, weighted. */
    private fun imuResidual(s: ImuSample): DoubleArray? {
        val tEval = s.t - xc[6]
        if (tEval < spline.startTime + 1e-6 || tEval >= spline.endTime - 1e-6) return null
        val pred = imuPrediction(tEval)
        return doubleArrayOf(
            (s.gyro[0] - pred.gyro[0]) / sigmaG,
            (s.gyro[1] - pred.gyro[1]) / sigmaG,
            (s.gyro[2] - pred.gyro[2]) / sigmaG,
            (s.accel[0] - pred.accel[0]) / sigmaA,
            (s.accel[1] - pred.accel[1]) / sigmaA,
            (s.accel[2] - pred.accel[2]) / sigmaA,
        )
    }

    /** Pixel reprojection residual of one ChArUco corner (already normalized by sigma). */
    private fun pixelResidual(obs: CornerObservation): DoubleArray? {
        val cam = camera ?: return null
        val t = obs.t
        if (t < spline.startTime + 1e-6 || t >= spline.endTime - 1e-6) return null
        val x = obs.xyz ?: boardLayout.corner3d(obs.id) ?: return null
        val pose = spline.pose(t)
        val r = pose.rWc
        val p = pose.pWc
        val dx = x[0] - p[0]
        val dy = x[1] - p[1]
        val dz = x[2] - p[2]
        val xc = r[0] * dx + r[3] * dy + r[6] * dz
        val yc = r[1] * dx + r[4] * dy + r[7] * dz
        val zc = r[2] * dx + r[5] * dy + r[8] * dz
        val uv = cam.project(xc, yc, zc) ?: return null
        val sigma = if (obs.xyz != null) options.markerSigmaPx else options.pixelSigmaPx
        val s = 1.0 / sigma.coerceAtLeast(1e-6)
        return doubleArrayOf((obs.u - uv[0]) * s, (obs.v - uv[1]) * s)
    }

    // ------------------------------------------------------------- jacobians

    private fun splineLocalParams(t: Double): Pair<Int, IntArray> {
        val u = (t - t0) / options.splineDt
        val base = floor(u).toInt()
        val idx = IntArray(splineOrder * 6)
        for (k in 0 until splineOrder) for (d in 0 until 6) idx[k * 6 + d] = (base + k) * 6 + d
        return Pair(base, idx)
    }

    private inline fun withPerturb(global: Int, eps: Double, body: () -> Unit) {
        if (global < nS) {
            val i = global / 6
            val d = global % 6
            coeff[i][d] += eps
            try { body() } finally { coeff[i][d] -= eps }
        } else {
            val c = global - nS
            xc[c] += eps
            try { body() } finally { xc[c] -= eps }
        }
    }

    /** Row of the Jacobian: parameter list and derivative matrix (rows = residuals). */
    private class Row(val idx: IntArray, val jac: Array<DoubleArray>)

    private fun boardRow(bp: BoardPose): Row {
        val (_, idx) = splineLocalParams(bp.t)
        val r0 = boardResidual(bp)
        val jac = Array(6) { DoubleArray(idx.size) }
        val eps = 1e-6
        for (a in idx.indices) {
            withPerturb(idx[a], eps) {
                val r1 = boardResidual(bp)
                for (m in 0 until 6) jac[m][a] = (r1[m] - r0[m]) / eps
            }
        }
        return Row(idx, jac)
    }

    /** Row built from a batch evaluator result (already the analytic Jacobian). */
    private fun pixelRowFromBatch(
        obs: CornerObservation,
        oi: Int,
        batch: DoubleArray,
        width: Int,
        localCount: Int,
    ): Row {
        val (_, idx) = splineLocalParams(obs.t)
        val jac = Array(2) { DoubleArray(idx.size) }
        for (col in 0 until idx.size.coerceAtMost(localCount)) {
            jac[0][col] = batch[oi * width + 2 + col]
            jac[1][col] = batch[oi * width + 2 + localCount + col]
        }
        return Row(idx, jac)
    }

    private fun pixelRow(obs: CornerObservation): Row? {
        val t = obs.t
        if (t < spline.startTime + 1e-6 || t >= spline.endTime - 1e-6) return null
        val (_, idx) = splineLocalParams(t)
        val r0 = pixelResidual(obs) ?: return null
        val jac = Array(2) { DoubleArray(idx.size) }
        val eps = 1e-6
        for (a in idx.indices) {
            withPerturb(idx[a], eps) {
                val r1 = pixelResidual(obs)
                if (r1 != null) for (m in 0 until 2) jac[m][a] = (r1[m] - r0[m]) / eps
            }
        }
        return Row(idx, jac)
    }

    private fun imuRow(s: ImuSample): Row? {
        val tEval0 = s.t - xc[6]
        if (tEval0 < spline.startTime + 1e-6 || tEval0 >= spline.endTime - 1e-6) return null
        val (_, local) = splineLocalParams(tEval0)
        // Only the calibration parameters that an IMU residual actually depends on:
        // rCi (0..2), pCi (3..5), td (6), gravity direction (13..15), plus the two bias
        // knots around this sample (or the constant biases when no bias spline is used).
        val extras = ArrayList<Int>(24)
        for (c in 0..6) {
            if (c == 6 && !options.fixedTimeOffset.isNaN()) continue
            extras.add(nS + c)
        }
        if (nBiasKnots == 0) {
            for (c in 7..12) extras.add(nS + c)
        } else {
            val u = ((tEval0 - t0) / biasDt).coerceIn(0.0, (nBiasKnots - 1).toDouble())
            val i = min(floor(u).toInt(), nBiasKnots - 2)
            for (k in 0 until 6) {
                extras.add(nS + 16 + i * 6 + k)
                extras.add(nS + 16 + (i + 1) * 6 + k)
            }
        }
        for (c in 13..15) extras.add(nS + c)
        extras.sort()
        val idx = IntArray(local.size + extras.size)
        for (i in local.indices) idx[i] = local[i]
        for (i in extras.indices) idx[local.size + i] = extras[i]
        val r0 = imuResidual(s) ?: return null
        val jac = Array(6) { DoubleArray(idx.size) }
        val eps = 1e-6
        for (a in idx.indices) {
            withPerturb(idx[a], eps) {
                val r1 = imuResidual(s) ?: return@withPerturb
                for (m in 0 until 6) jac[m][a] = (r1[m] - r0[m]) / eps
            }
        }
        return Row(idx, jac)
    }

    // --------------------------------------------------------- normal matrix

    private class NormalSystem(
        val hss: BandedMatrix,
        val hsc: DoubleArray,
        val hcc: DoubleArray,
        val gs: DoubleArray,
        val gc: DoubleArray,
        val cost: Double,
    )

    private fun totalCost(): Double {
        var cost = 0.0
        if (options.useBoardPosePrior) for (bp in board) {
            val r = boardResidual(bp)
            for (m in 0 until 6) cost += r[m] * r[m]
        }
        for (s in imuSub) {
            val r = imuResidual(s) ?: continue
            for (m in 0 until 6) cost += r[m] * r[m]
        }
        if (usePixels) for (obs in corners) {
            val r = pixelResidual(obs) ?: continue
            cost += r[0] * r[0] + r[1] * r[1]
        }
        return cost + leverPriorCost()
    }

    private fun buildNormalSystem(): NormalSystem {
        val hss = BandedMatrix(nS, band)
        val hsc = DoubleArray(nS * NC)
        val hcc = DoubleArray(NC * NC)
        val gs = DoubleArray(nS)
        val gc = DoubleArray(NC)
        var cost = 0.0

        fun accumulate(r: DoubleArray, row: Row) {
            val idx = row.idx
            for (m in 0 until 6) {
                cost += r[m] * r[m]
                val rm = r[m]
                for (a in idx.indices) {
                    val ga = idx[a]
                    val va = row.jac[m][a]
                    if (va == 0.0) continue
                    if (ga < nS) gs[ga] += va * rm else gc[ga - nS] += va * rm
                    for (b in a until idx.size) {
                        val gb = idx[b]
                        val vb = row.jac[m][b]
                        if (vb == 0.0) continue
                        val v = va * vb
                        if (ga < nS && gb < nS) {
                            hss.add(ga, gb, v)
                        } else if (ga < nS) {
                            hsc[ga * NC + (gb - nS)] += v
                        } else if (gb >= nS) {
                            val i = ga - nS
                            val j = gb - nS
                            hcc[i * NC + j] += v
                            if (i != j) hcc[j * NC + i] += v
                        }
                    }
                }
            }
        }

        if (options.useBoardPosePrior) for (bp in board) {
            val row = boardRow(bp)
            val r = boardResidual(bp)
            accumulate(r, row)
        }
        for (s in imuSub) {
            val row = imuRow(s) ?: continue
            val r = imuResidual(s) ?: continue
            accumulate(r, row)
        }
        val localCount = splineOrder * 6
        val batch = if (usePixels && pixelBatch != null) {
            val flat = DoubleArray(nKnots * 6)
            for (i in 0 until nKnots) for (d in 0 until 6) flat[i * 6 + d] = coeff[i][d]
            try {
                pixelBatch!!.evaluate(
                    flat, obsBase, obsBasis, obsXyz, obsUv, corners.size, localCount,
                    camera!!, options.pixelSigmaPx,
                )
            } catch (e: Throwable) {
                null
            }
        } else null
        if (usePixels) for (oi in corners.indices) {
            val obs = corners[oi]
            val r: DoubleArray
            val row: Row
            if (batch != null && obsValid[oi]) {
                val width = 2 + 2 * localCount
                r = doubleArrayOf(batch[oi * width], batch[oi * width + 1])
                row = pixelRowFromBatch(obs, oi, batch, width, localCount)
            } else {
                row = pixelRow(obs) ?: continue
                r = pixelResidual(obs) ?: continue
            }
            val idx = row.idx
            for (m in 0 until 2) {
                cost += r[m] * r[m]
                for (a in idx.indices) {
                    val ga = idx[a]
                    val va = row.jac[m][a]
                    if (va == 0.0) continue
                    gs[ga] += va * r[m]
                    for (b in a until idx.size) {
                        val gb = idx[b]
                        val vb = row.jac[m][b]
                        if (vb == 0.0) continue
                        hss.add(ga, gb, va * vb)
                    }
                }
            }
        }
        val sp = leverSigma()
        for (k in 0 until 3) {
            val v = xc[3 + k] / sp
            cost += v * v
            gc[3 + k] += v / sp
            hcc[(3 + k) * NC + (3 + k)] += 1.0 / (sp * sp)
        }
        if (!options.tdPriorTarget.isNaN() && options.tdPriorSigma > 0.0) {
            val v = (xc[6] - options.tdPriorTarget) / options.tdPriorSigma
            cost += v * v
            gc[6] += v / options.tdPriorSigma
            hcc[6 * NC + 6] += 1.0 / (options.tdPriorSigma * options.tdPriorSigma)
        }
        // Bias-spline smoothness: adjacent knots may drift slowly, never jump.
        if (nBiasKnots > 1) {
            for (i in 0 until nBiasKnots - 1) {
                for (k in 0 until 3) {
                    cost += addDifferencePrior(16 + i * 6 + k, 16 + (i + 1) * 6 + k, options.biasSmoothSigmaG, hcc, gc)
                    cost += addDifferencePrior(16 + i * 6 + 3 + k, 16 + (i + 1) * 6 + 3 + k, options.biasSmoothSigmaA, hcc, gc)
                }
            }
        }
        return NormalSystem(hss, hsc, hcc, gs, gc, cost)
    }

    // ------------------------------------------------------------- LM solve

    private fun solveNormalSystem(sys: NormalSystem, lambda: Double): DoubleArray? {
        val hss = sys.hss.copy()
        for (i in 0 until nS) {
            val d = max(hss.get(i, i), 1e-9)
            hss.add(i, i, lambda * d)
        }
        val hcc = sys.hcc.copyOf()
        for (i in 0 until NC) {
            val d = max(hcc[i * NC + i], 1e-9)
            hcc[i * NC + i] += lambda * d
        }
        if (!hss.cholesky()) return null
        // z = Hss^-1 gs
        val z = sys.gs.copyOf()
        hss.solve(z)
        // Y = Hss^-1 Hsc
        val y = DoubleArray(nS * NC)
        for (c in 0 until NC) {
            val col = DoubleArray(nS) { r -> sys.hsc[r * NC + c] }
            hss.solve(col)
            for (r in 0 until nS) y[r * NC + c] = col[r]
        }
        // Schur complement C = Hcc - Hsc^T Y
        val c = DoubleArray(NC * NC)
        for (i in 0 until NC) {
            for (j in 0 until NC) {
                var v = hcc[i * NC + j]
                for (r in 0 until nS) v -= sys.hsc[r * NC + i] * y[r * NC + j]
                c[i * NC + j] = v
            }
        }
        for (i in 0 until NC) c[i * NC + i] += max(1e-12, abs(c[i * NC + i]) * 1e-12)
        val gc = DoubleArray(NC) { i -> sys.gc[i] }
        for (i in 0 until NC) {
            var v = gc[i]
            for (r in 0 until nS) v -= sys.hsc[r * NC + i] * z[r]
            gc[i] = v
        }
        val uc = gc.copyOf()
        for (i in 0 until NC) uc[i] = -uc[i]
        if (!solveDense(c, NC, uc)) return null
        val us = DoubleArray(nS)
        for (r in 0 until nS) {
            var v = -z[r]
            for (c2 in 0 until NC) v -= y[r * NC + c2] * uc[c2]
            us[r] = v
        }
        val dx = DoubleArray(nS + NC)
        System.arraycopy(us, 0, dx, 0, nS)
        System.arraycopy(uc, 0, dx, nS, NC)
        return dx
    }

    private fun solveDense(a: DoubleArray, n: Int, b: DoubleArray): Boolean {
        for (col in 0 until n) {
            var pivot = col
            var best = abs(a[col * n + col])
            for (r in col + 1 until n) {
                val v = abs(a[r * n + col])
                if (v > best) { best = v; pivot = r }
            }
            if (best < 1e-14) return false
            if (pivot != col) {
                for (k in 0 until n) {
                    val tmp = a[col * n + k]; a[col * n + k] = a[pivot * n + k]; a[pivot * n + k] = tmp
                }
                val t = b[col]; b[col] = b[pivot]; b[pivot] = t
            }
            val p = a[col * n + col]
            for (r in col + 1 until n) {
                val f = a[r * n + col] / p
                if (f == 0.0) continue
                for (k in col until n) a[r * n + k] -= f * a[col * n + k]
                b[r] -= f * b[col]
            }
        }
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r * n + k] * b[k]
            b[r] = s / a[r * n + r]
        }
        return true
    }

    private fun applyStep(dx: DoubleArray, sign: Double = 1.0) {
        for (p in 0 until nS) {
            val i = p / 6
            val d = p % 6
            coeff[i][d] += sign * dx[p]
        }
        for (c in 0 until NC) xc[c] += sign * dx[nS + c]
        if (!options.fixedTimeOffset.isNaN()) xc[6] = options.fixedTimeOffset
        xc[6] = xc[6].coerceIn(-0.1, 0.1)
        val u = unit(doubleArrayOf(xc[13], xc[14], xc[15]))
        xc[13] = u[0]; xc[14] = u[1]; xc[15] = u[2]
    }

    // ---------------------------------------------------------- initialization

    private fun initializeSpline() {
        val h = BandedMatrix(nS, band)
        val g = DoubleArray(nS)
        for (bp in board) {
            val (base, _) = splineLocalParams(bp.t)
            val basis = spline.spline.basis(bp.t)
            val theta = So3.log(bp.rWc)
            val target = doubleArrayOf(
                bp.pWc[0], bp.pWc[1], bp.pWc[2],
                theta[0], theta[1], theta[2],
            )
            for (m in 0 until 6) {
                val w = if (m < 3) 1.0 / sigmaTrans else 1.0 / sigmaRot
                for (k in 0 until splineOrder) {
                    val i = (base + k) * 6 + m
                    val a = w * basis.b[k]
                    g[i] += a * w * target[m]
                    for (l in 0 until splineOrder) {
                        if (l < k) continue
                        val j = (base + l) * 6 + m
                        h.add(i, j, a * w * basis.b[l])
                    }
                }
            }
        }
        for (i in 0 until nS) h.add(i, i, 1e-6)
        if (!h.cholesky()) error("spline initialization is singular")
        for (m in 0 until 6) {
            val rhs = DoubleArray(nS)
            for (r in 0 until nS) if (r % 6 == m) rhs[r] = g[r]
            h.solve(rhs)
            for (r in 0 until nS) if (r % 6 == m) coeff[r / 6][m] = rhs[r]
        }
    }

    /**
     * Rotation and time-offset initialization: for a grid of camera->IMU offsets, fit
     * r_cam = D * r_imu from consecutive board-pose rotations against integrated gyro and
     * keep the offset whose rotation fit is best (Kalibr's hand-eye relation, with the
     * temporal calibration searched rather than assumed).
     */
    private fun initRotationAndOffset(initialTd: Double): DoubleArray {
        var bestTd = initialTd
        var bestR = doubleArrayOf(0.0, 0.0, 0.0)
        var bestRms = Double.MAX_VALUE
        for (td in doubleArrayOf(-0.030, -0.020, -0.010, 0.0, 0.010, 0.020, 0.030, initialTd)) {
            val fit = fitRotationAt(td)
            if (fit != null && fit.third < bestRms) {
                bestRms = fit.third
                bestTd = td
                bestR = fit.first
            }
        }
        return doubleArrayOf(bestR[0], bestR[1], bestR[2], bestTd)
    }

    private data class RotFit(val first: DoubleArray, val second: Int, val third: Double)

    private fun fitRotationAt(td: Double): RotFit? {
        val pairs = ArrayList<Pair<DoubleArray, DoubleArray>>()
        for (i in 1 until board.size) {
            val a = board[i - 1]
            val b = board[i]
            if (b.t - a.t > 0.2) continue
            val dRc = So3.mul(So3.transpose(a.rWc), b.rWc)
            if (So3.angleDeg(dRc) < 0.3) continue
            val dRi = integrateGyro(imu, a.t + td, b.t + td) ?: continue
            pairs.add(Pair(So3.log(dRc), So3.log(dRi)))
        }
        if (pairs.size < 10) return null
        val d = RotationFit.fit(pairs.map { it.second }, pairs.map { it.first })
        var sum = 0.0
        for (p in pairs) {
            val pred = So3.mulVec(d, p.second)
            val e = doubleArrayOf(pred[0] - p.first[0], pred[1] - p.first[1], pred[2] - p.first[2])
            sum += e[0] * e[0] + e[1] * e[1] + e[2] * e[2]
        }
        return RotFit(So3.log(d), pairs.size, kotlin.math.sqrt(sum / pairs.size))
    }

    /** Levenberg-Marquardt on the 16 calibration parameters with the spline held fixed. */
    private fun calibrateCalibOnly(maxIterations: Int) {
        var cost = totalCost()
        var lambda = 1e-3
        repeat(maxIterations) {
            val h = DoubleArray(NC * NC)
            val g = DoubleArray(NC)
            var rowCost = 0.0
            for (s in imuSub) {
                val r0 = imuResidual(s) ?: continue
                val jac = Array(6) { DoubleArray(NC) }
                val eps = 1e-6
                for (c in 0 until NC) {
                    xc[c] += eps
                    val r1 = imuResidual(s)
                    xc[c] -= eps
                    if (r1 == null) continue
                    for (m in 0 until 6) jac[m][c] = (r1[m] - r0[m]) / eps
                }
                for (m in 0 until 6) {
                    rowCost += r0[m] * r0[m]
                    for (i in 0 until NC) {
                        val ji = jac[m][i]
                        if (ji == 0.0) continue
                        g[i] += ji * r0[m]
                        for (j in i until NC) {
                            val v = ji * jac[m][j]
                            h[i * NC + j] += v
                            if (i != j) h[j * NC + i] += v
                        }
                    }
                }
            }
            addLeverPrior(h, g)
            var accepted = false
            var attempt = 0
            while (!accepted && attempt < 8) {
                val a = h.copyOf()
                for (i in 0 until NC) a[i * NC + i] += lambda * maxOf(1e-9, h[i * NC + i])
                val dx = DoubleArray(NC) { -g[it] }
                if (!solveDense(a, NC, dx)) { lambda *= 10; attempt++; continue }
                val dxAbs = dx.maxOf { kotlin.math.abs(it) }
                val xcSave = xc.copyOf()
                for (i in 0 until NC) xc[i] += dx[i]
                xc[6] = xc[6].coerceIn(-0.1, 0.1)
                val u = unit(doubleArrayOf(xc[13], xc[14], xc[15]))
                xc[13] = u[0]; xc[14] = u[1]; xc[15] = u[2]
                val newCost = totalCost()
                if (newCost < cost) {
                    cost = newCost
                    lambda = maxOf(lambda * 0.3, 1e-9)
                    accepted = true
                    if (dxAbs < options.stepTolerance) return
                } else {
                    System.arraycopy(xcSave, 0, xc, 0, xc.size)
                    lambda *= 5.0
                    attempt++
                }
            }
            if (!accepted) return
        }
    }

    private fun addLeverPrior(h: DoubleArray, g: DoubleArray) {
        val sp = leverSigma()
        for (k in 0 until 3) {
            g[3 + k] += xc[3 + k] / (sp * sp)
            h[(3 + k) * NC + (3 + k)] += 1.0 / (sp * sp)
        }
    }

    private fun integrateGyro(gyro: List<ImuSample>, ta: Double, tb: Double): DoubleArray? {
        if (gyro.size < 2 || tb <= ta) return null
        if (ta < gyro.first().t || tb > gyro.last().t) return null
        val th = DoubleArray(3)
        var prevT = ta
        var prevW = gyroAt(gyro, ta)
        var i = lowerBound(gyro, ta)
        while (i < gyro.size && gyro[i].t < tb) {
            val dt = gyro[i].t - prevT
            if (dt > 0.0) for (k in 0 until 3) th[k] += 0.5 * (prevW[k] + gyro[i].gyro[k]) * dt
            prevT = gyro[i].t
            prevW = gyro[i].gyro
            i++
        }
        val endW = gyroAt(gyro, tb)
        val dt = tb - prevT
        if (dt > 0.0) for (k in 0 until 3) th[k] += 0.5 * (prevW[k] + endW[k]) * dt
        return So3.exp(th)
    }

    private fun gyroAt(gyro: List<ImuSample>, t: Double): DoubleArray {
        val i = lowerBound(gyro, t)
        if (i <= 0) return gyro[0].gyro
        if (i >= gyro.size) return gyro[gyro.size - 1].gyro
        val a = gyro[i - 1]
        val b = gyro[i]
        val f = if (b.t > a.t) (t - a.t) / (b.t - a.t) else 0.0
        return doubleArrayOf(
            a.gyro[0] + f * (b.gyro[0] - a.gyro[0]),
            a.gyro[1] + f * (b.gyro[1] - a.gyro[1]),
            a.gyro[2] + f * (b.gyro[2] - a.gyro[2]),
        )
    }

    private fun lowerBound(items: List<ImuSample>, t: Double): Int {
        var lo = 0
        var hi = items.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (items[mid].t < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun diagnostics(iterations: Int, converged: Boolean): Diagnostics {
        var br = 0.0
        var bp = 0.0
        var nB = 0
        for (bp2 in board) {
            val r = boardResidual(bp2)
            br += (r[0] * r[0] + r[1] * r[1] + r[2] * r[2]) * sigmaRot * sigmaRot
            bp += (r[3] * r[3] + r[4] * r[4] + r[5] * r[5]) * sigmaTrans * sigmaTrans
            nB++
        }
        var rg = 0.0
        var ra = 0.0
        var nI = 0
        for (s in imuSub) {
            val r = imuResidual(s) ?: continue
            rg += (r[0] * r[0] + r[1] * r[1] + r[2] * r[2]) * sigmaG * sigmaG
            ra += (r[3] * r[3] + r[4] * r[4] + r[5] * r[5]) * sigmaA * sigmaA
            nI++
        }
        var pix = 0.0
        var nP = 0
        if (usePixels) for (obs in corners) {
            val r = pixelResidual(obs) ?: continue
            pix += (r[0] * r[0] + r[1] * r[1]) * options.pixelSigmaPx * options.pixelSigmaPx
            nP++
        }
        val rmsRot = if (nB > 0) sqrt(br / nB) * 180.0 / Math.PI else 0.0
        val rmsTrans = if (nB > 0) sqrt(bp / nB) * 1000.0 else 0.0
        val rmsG = if (nI > 0) sqrt(rg / nI) else 0.0
        val rmsA = if (nI > 0) sqrt(ra / nI) else 0.0
        val rmsPix = if (nP > 0) sqrt(pix / (2.0 * nP)) else Double.NaN
        return Diagnostics(iterations, totalCost(), rmsRot, rmsTrans, rmsG, rmsA, nB, nI, converged, rmsPix)
    }

    /** Cost at an explicit parameter set with the spline held at the pose measurement fit. */
    internal fun debugCostAtTruth(
        rCi: DoubleArray, pCi: DoubleArray, td: Double, bg: DoubleArray, ba: DoubleArray, gDir: DoubleArray,
        initSplineOnly: Boolean = false,
    ): Double {
        if (initSplineOnly) initializeSpline()
        for (k in 0 until 3) {
            xc[k] = rCi[k]
            xc[3 + k] = if (options.estimateLeverArm) pCi[k] else 0.0
            xc[7 + k] = bg[k]
            xc[10 + k] = ba[k]
        }
        xc[6] = td
        val u = unit(gDir)
        xc[13] = u[0]; xc[14] = u[1]; xc[15] = u[2]
        return totalCost()
    }

    /** Run only the initialization (pose fit, extrinsic/offset init, quiet-window biases). */
    internal fun debugInitializeOnly(): Double {
        initializeSpline()
        xc.fill(0.0)
        val ro = initRotationAndOffset(0.0)
        for (k in 0 until 3) xc[k] = ro[k]
        xc[6] = ro[3]
        xc[13] = 0.0; xc[14] = 0.0; xc[15] = -1.0
        initializeFromQuietWindow()
        return totalCost()
    }

    /** Weighted gyroscope cost for a forced extrinsic rotation and time offset. */
    internal fun debugGyroCost(rCi: DoubleArray, td: Double): Double {
        for (k in 0 until 3) xc[k] = rCi[k]
        xc[6] = td
        var c = 0.0
        for (s in imuSub) {
            val r = imuResidual(s) ?: continue
            for (m in 0 until 3) c += r[m] * r[m]
        }
        return c
    }

    /** Cost with a forced time offset (spline and other params as initialized). */
    internal fun debugCostAtTd(td: Double): Double {
        xc[6] = td
        return totalCost()
    }

    // ------------------------------------------------------------ test hooks

    /** Initialize exactly as calibrate() does (used by the gradient test). */
    internal fun debugInitialize(initialTd: Double = 0.0) {
        initializeSpline()
        xc.fill(0.0)
        val rotAndOffset = initRotationAndOffset(initialTd)
        for (k in 0 until 3) xc[k] = rotAndOffset[k]
        xc[6] = rotAndOffset[3]
        xc[13] = 0.0; xc[14] = 0.0; xc[15] = -1.0
    }

    internal fun debugSplineFitRms(): Pair<Double, Double> {
        var rot = 0.0
        var trans = 0.0
        for (bp in board) {
            val pose = spline.pose(bp.t)
            val a = So3.angleDeg(So3.mul(So3.transpose(bp.rWc), pose.rWc)) * Math.PI / 180.0
            val t = kotlin.math.sqrt(
                (pose.pWc[0] - bp.pWc[0]) * (pose.pWc[0] - bp.pWc[0]) +
                (pose.pWc[1] - bp.pWc[1]) * (pose.pWc[1] - bp.pWc[1]) +
                (pose.pWc[2] - bp.pWc[2]) * (pose.pWc[2] - bp.pWc[2]))
            rot += a * a
            trans += t * t
        }
        return Pair(kotlin.math.sqrt(rot / board.size), kotlin.math.sqrt(trans / board.size))
    }

    /** Compare the initialization system built by initializeSpline's loop with a dense one. */
    internal fun debugInitSystemDiff(): Double {
        val dense = Array(nS) { DoubleArray(nS) }
        var worst = 0.0
        val h = BandedMatrix(nS, band)
        val g = DoubleArray(nS)
        for (bp in board) {
            val (base, _) = splineLocalParams(bp.t)
            val basis = spline.spline.basis(bp.t)
            val theta = So3.log(bp.rWc)
            val target = doubleArrayOf(bp.pWc[0], bp.pWc[1], bp.pWc[2], theta[0], theta[1], theta[2])
            for (m in 0 until 6) {
                val w = if (m < 3) 1.0 / sigmaTrans else 1.0 / sigmaRot
                for (k2 in 0 until splineOrder) {
                    val i2 = (base + k2) * 6 + m
                    g[i2] += w * basis.b[k2] * w * target[m]
                }
                for (k2 in 0 until splineOrder) {
                    val i2 = (base + k2) * 6 + m
                    for (l2 in 0 until splineOrder) {
                        val j2 = (base + l2) * 6 + m
                        dense[i2][j2] += w * basis.b[k2] * w * basis.b[l2]
                    }
                }
                for (k2 in 0 until splineOrder) {
                    val i2 = (base + k2) * 6 + m
                    val a2 = w * basis.b[k2]
                    for (l2 in 0 until splineOrder) {
                        if (l2 < k2) continue
                        val j2 = (base + l2) * 6 + m
                        h.add(i2, j2, a2 * w * basis.b[l2])
                    }
                }
            }
        }
        for (i in 0 until nS) dense[i][i] += 1e-6
        for (i in 0 until nS) h.add(i, i, 1e-6)
        for (i in 0 until nS) for (j in 0 until nS) {
            val d = kotlin.math.abs(h.get(i, j) - dense[i][j])
            if (d > worst) worst = d
        }
        return worst
    }

    /** Build the initialization normal equations densely (interleaved) and compare the banded solve. */
    internal fun debugInitSolveMismatch(): Double {
        val dense = Array(nS) { DoubleArray(nS) }
        val gDense = DoubleArray(nS)
        for (bp in board) {
            val (base, _) = splineLocalParams(bp.t)
            val basis = spline.spline.basis(bp.t)
            val theta = So3.log(bp.rWc)
            val target = doubleArrayOf(bp.pWc[0], bp.pWc[1], bp.pWc[2], theta[0], theta[1], theta[2])
            for (m in 0 until 6) {
                val w = if (m < 3) 1.0 / sigmaTrans else 1.0 / sigmaRot
                for (k in 0 until splineOrder) {
                    val i = (base + k) * 6 + m
                    gDense[i] += w * basis.b[k] * w * target[m]
                    for (l in 0 until splineOrder) {
                        val j = (base + l) * 6 + m
                        dense[i][j] += w * basis.b[k] * w * basis.b[l]
                    }
                }
            }
        }
        for (i in 0 until nS) dense[i][i] += 1e-6
        val solDense = gDense.copyOf()
        val flat = DoubleArray(nS * nS)
        for (i in 0 until nS) for (j in 0 until nS) flat[i * nS + j] = dense[i][j]
        if (!solveDense(flat, nS, solDense)) error("dense init singular")
        val h = BandedMatrix(nS, band)
        for (i in 0 until nS) for (j in (i - band).coerceAtLeast(0)..i) h.add(i, j, dense[i][j])
        if (!h.cholesky()) error("banded init singular")
        val solBanded = gDense.copyOf()
        h.solve(solBanded)
        var worst = 0.0
        for (i in 0 until nS) worst = maxOf(worst, kotlin.math.abs(solDense[i] - solBanded[i]))
        return worst
    }

    /** Compare the banded and dense spline-fit coefficients; returns the max difference. */
    internal fun debugBandedVsDenseCoeff(): Double {
        initializeSpline()
        val banded = Array(nKnots) { coeff[it].copyOf() }
        debugDenseFitRms()
        var worst = 0.0
        for (i in 0 until nKnots) for (d in 0 until 6) {
            val e = kotlin.math.abs(banded[i][d] - coeff[i][d])
            if (e > worst) worst = e
        }
        // restore banded solution for further inspection
        for (i in 0 until nKnots) coeff[i] = banded[i].copyOf()
        return worst
    }

    /** Dense per-dimension least-squares fit of the board poses, for debugging the banded solve. */
    internal fun debugDenseFitRms(): Pair<Double, Double> {
        for (m in 0 until 6) {
            val a = DoubleArray(nKnots * nKnots)
            val b = DoubleArray(nKnots)
            for (bp in board) {
                val (base, _) = splineLocalParams(bp.t)
                val basis = spline.spline.basis(bp.t)
                val theta = So3.log(bp.rWc)
                val target = if (m < 3) bp.pWc[m] else theta[m - 3]
                val w = if (m < 3) 1.0 / sigmaTrans else 1.0 / sigmaRot
                for (k in 0 until splineOrder) {
                    val i = base + k
                    b[i] += w * basis.b[k] * w * target
                    for (l in 0 until splineOrder) {
                        val j = base + l
                        a[i * nKnots + j] += w * basis.b[k] * w * basis.b[l]
                    }
                }
            }
            for (i in 0 until nKnots) a[i * nKnots + i] += 1e-6
            val rhs = b.copyOf()
            if (!solveDense(a, nKnots, rhs)) error("dense fit singular")
            for (i in 0 until nKnots) coeff[i][m] = rhs[i]
        }
        return debugSplineFitRms()
    }

    /**
     * Max |2*analytic - numeric| gradient mismatch over a sample of parameters,
     * normalized by 1+|numeric|. A broken Jacobian shows up immediately.
     */
    internal fun debugGradientMismatch(samples: Int = 30): Double {
        val sys = buildNormalSystem()
        val eps = 1e-6
        val rnd = java.util.Random(7)
        var worst = 0.0
        repeat(samples) {
            val idx = rnd.nextInt(nS + NC)
            val analytic: Double
            val plus: Double
            val minus: Double
            if (idx < nS) {
                val i = idx / 6
                val d = idx % 6
                analytic = sys.gs[idx]
                coeff[i][d] += eps
                plus = totalCost()
                coeff[i][d] -= 2 * eps
                minus = totalCost()
                coeff[i][d] += eps
            } else {
                val cc2 = idx - nS
                analytic = sys.gc[cc2]
                xc[cc2] += eps
                plus = totalCost()
                xc[cc2] -= 2 * eps
                minus = totalCost()
                xc[cc2] += eps
            }
            val numeric = (plus - minus) / (2 * eps)
            val err = kotlin.math.abs(2.0 * analytic - numeric) / (1.0 + kotlin.math.abs(numeric))
            if (err > worst) worst = err
        }
        return worst
    }

    private companion object
}