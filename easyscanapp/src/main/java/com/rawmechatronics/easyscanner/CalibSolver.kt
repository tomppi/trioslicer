package com.rawmechatronics.easyscanner

import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-phone VIO calibration from a tracker-debug session (board poses + IMU).
 *
 * frames.csv carries the board pose as 16 floats (row major, camera->board).
 * imu.csv carries timestamped raw accel/gyro samples. The solver estimates
 *   - the camera-IMU rotation (from gyro vs board relative rotation),
 *   - the camera-IMU time offset (part of the same least squares),
 *   - IMU white-noise densities (from the quietest 5 s window),
 * and writes the kalibr YAML files the VIO loads.
 */
object CalibSolver {

    typealias M3 = Array<DoubleArray>

    data class Result(val ok: Boolean, val message: String, val lines: List<String> = emptyList())

    internal data class FrameRow(val ts: Double, val m: DoubleArray, val vio: DoubleArray?)
    internal data class Imu(val type: Char, val ts: Double, val x: Double, val y: Double, val z: Double)
    data class DataPair(val t0: Double, val t1: Double, val dRcam: M3)
    internal data class ImuNoise(val accelNd: Double, val gyroNd: Double, val rateHz: Double, val bias: DoubleArray)
    internal data class Fit(val rotDeg: DoubleArray, val dt: Double, val bias: DoubleArray, val rmsDeg: Double, val pairs: Int)

    fun solve(ctx: android.content.Context): Result {
        val root = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "tracker-debug")
        val sessions = root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
        if (sessions.isEmpty()) {
            return Result(false, "No calibration recording found. Use Scan step -> 'Calib board 30s' with the board visible, then try again.")
        }
        var session = sessions.first()
        var frames = parseFrames(File(session, "frames.csv")) ?: emptyList()
        for (s2 in sessions) {
            val f2 = parseFrames(File(s2, "frames.csv")) ?: continue
            if (f2.size >= 25) { session = s2; frames = f2; break }
        }
        if (frames.size < 25) {
            return Result(false, "No session with enough board frames yet. Use Scan step -> 'Calib board 30s' with the board visible while moving.")
        }
        val imu = parseImu(File(session, "imu.csv"))
            ?: return Result(false, "imu.csv missing in " + session.name)
        if (frames.size < 25) {
            return Result(false, "Only " + frames.size + " board frames in " + session.name + ".\nRun a board scan with the board visible for 20-30 s while moving the phone.")
        }
        if (imu.size < 200) return Result(false, "Too few IMU samples: " + imu.size)

        val noise = estimateNoise(imu)
        val fit = fitCameraImu(frames, imu, noise.bias)

        val dir = File(ctx.filesDir, "openvins-config").apply { mkdirs() }
        writeImuCam(dir, fit.rotDeg, fit.dt)
        writeImuNoise(dir, noise)

        val scale = scaleReport(frames)
        val rot = rotationReport(frames)
        val lines = ArrayList<String>()
        lines.add("session: " + session.name)
        lines.add("board frames: " + frames.size + ", IMU samples: " + imu.size + " (" + "%.0f Hz".format(noise.rateHz) + ")")
        lines.add("")
        lines.add("R_cam_imu rotvec (deg): %.2f %.2f %.2f".format(fit.rotDeg[0], fit.rotDeg[1], fit.rotDeg[2]))
        lines.add("time offset: %.1f ms".format(fit.dt * 1000.0))
        lines.add("rotation fit rms: %.3f deg over %d pairs".format(fit.rmsDeg, fit.pairs))
        lines.add("gyro bias (deg/s): %.3f %.3f %.3f".format(fit.bias[0], fit.bias[1], fit.bias[2]))
        lines.add("accel noise density: %.3e m/s2/rtHz".format(noise.accelNd))
        lines.add("gyro noise density: %.3e rad/s/rtHz".format(noise.gyroNd))
        if (scale != null) {
            lines.add("")
            lines.add("scale check (VIO vs board):")
            lines.add("  board path: %.1f mm, VIO path: %.1f mm".format(scale.first * 1000.0, scale.second * 1000.0))
            lines.add("  scale factor: %.4f (error %+.1f%%)".format(scale.third, (scale.third - 1.0) * 100.0))
        } else {
            lines.add("")
            lines.add("scale check: no VIO poses in this recording (enable Track VIO during the board scan to get it)")
        }
        if (rot != null) {
            lines.add("")
            lines.add("VIO rotation vs board: median %.2f deg, p90 %.2f deg (pairs %d)".format(rot[0], rot[1], rot[2].toInt()))
        }
        lines.add("")
        lines.add("Written to files/openvins-config; the VIO loads it on next app start.")
        return Result(true, "Calibration solved", lines)
    }

    // ---------------------------------------------------------------- files

    private fun parseFrames(f: File): List<FrameRow>? {
        if (!f.exists()) return null
        val out = ArrayList<FrameRow>()
        try {
            f.bufferedReader().useLines { seq ->
                val it = seq.iterator()
                if (!it.hasNext()) return null
                val header = it.next().split(',')
                val iTs = header.indexOf("ts_ns").let { if (it >= 0) it else 1 }
                while (it.hasNext()) {
                    val p = it.next().split(',')
                    if (p.size < 5) continue
                    val ts = p[iTs].toDoubleOrNull() ?: continue
                    // rows may have more fields than the header; find the 16-float pose fields
                    var board: DoubleArray? = null
                    var vio: DoubleArray? = null
                    for (c in 4 until p.size) {
                        val vals = p[c].trim().split(' ').mapNotNull { it.toDoubleOrNull() }
                        if (vals.size != 16) continue
                        if (board == null) board = vals.toDoubleArray() else if (vio == null) vio = vals.toDoubleArray()
                    }
                    if (board == null) continue
                    out.add(FrameRow(ts * 1e-9, board, vio))
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }

    private fun parseImu(f: File): List<Imu>? {
        if (!f.exists()) return null
        val out = ArrayList<Imu>()
        try {
            f.bufferedReader().useLines { seq ->
                val it = seq.iterator()
                if (!it.hasNext()) return null
                it.next()
                while (it.hasNext()) {
                    val p = it.next().split(',')
                    if (p.size < 5) continue
                    val type = p[0].firstOrNull() ?: continue
                    val ts = p[1].toDoubleOrNull() ?: continue
                    val x = p[2].toDoubleOrNull() ?: continue
                    val y = p[3].toDoubleOrNull() ?: continue
                    val z = p[4].toDoubleOrNull() ?: continue
                    out.add(Imu(type, ts * 1e-9, x, y, z))
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }

    private fun writeImuCam(dir: File, rotDeg: DoubleArray, dt: Double) {
        val old = File(dir, "kalibr_imucam_chain.yaml")
        var intrinsics = "[432.179802389, 431.795649509, 321.333984375, 242.097886030]"
        var distortion = "[0.100007, -0.121012, -0.005155, -0.001849]"
        var resolution = "[640, 480]"
        if (old.exists()) {
            for (line in old.readLines()) {
                val t = line.trim()
                if (t.startsWith("intrinsics:")) intrinsics = t.substringAfter("intrinsics:").substringBefore('#').trim()
                if (t.startsWith("distortion_coeffs:")) distortion = t.substringAfter("distortion_coeffs:").substringBefore('#').trim()
                if (t.startsWith("resolution:")) resolution = t.substringAfter("resolution:").substringBefore('#').trim()
            }
        }
        val r = rotvecToMat(rotDeg)
        val sb = StringBuilder()
        sb.append("%YAML:1.0\n\ncam0:\n  T_cam_imu:\n")
        for (i in 0..2) sb.append("    - [%.9f, %.9f, %.9f, 0.0]\n".format(r[i][0], r[i][1], r[i][2]))
        sb.append("    - [0.0, 0.0, 0.0, 1.0]\n")
        sb.append("  cam_overlaps: []\n  camera_model: pinhole\n")
        sb.append("  distortion_coeffs: ").append(distortion).append('\n')
        sb.append("  distortion_model: radtan\n")
        sb.append("  intrinsics: ").append(intrinsics).append('\n')
        sb.append("  resolution: ").append(resolution).append('\n')
        sb.append("  rostopic: /cam0/image_raw\n")
        sb.append("  timeshift_cam_imu: %.6f\n".format(dt))
        File(dir, "kalibr_imucam_chain.yaml").writeText(sb.toString())
    }

    private fun writeImuNoise(dir: File, n: ImuNoise) {
        val f = File(dir, "kalibr_imu_chain.yaml")
        if (!f.exists()) return
        var text = f.readText()
        text = text.replace(Regex("accelerometer_noise_density:\\s*[^\\s#]+"), "accelerometer_noise_density: %.4e".format(n.accelNd))
        text = text.replace(Regex("gyroscope_noise_density:\\s*[^\\s#]+"), "gyroscope_noise_density: %.4e".format(n.gyroNd))
        text = text.replace(Regex("update_rate:\\s*[^\\s#]+"), "update_rate: %.1f".format(n.rateHz))
        f.writeText(text)
    }

    /** Returns (medianDeg, p90Deg, pairs) of VIO vs board relative rotation error, or null. */
    private fun rotationReport(frames: List<FrameRow>): DoubleArray? {
        val rows = frames.filter { it.vio != null }
        if (rows.size < 10) return null
        val errs = ArrayList<Double>()
        for (i in 1 until rows.size) {
            val a = rows[i - 1]
            val b = rows[i]
            if (b.ts - a.ts <= 0.01 || b.ts - a.ts > 0.25) continue
            val rb0 = rotOf(a.m)
            val rb1 = rotOf(b.m)
            val dRb = mul3(transpose3(rb0), rb1)
            val v0 = a.vio!!
            val v1 = b.vio!!
            val rv0 = arrayOf(
                doubleArrayOf(v0[0], v0[4], v0[8]),
                doubleArrayOf(v0[1], v0[5], v0[9]),
                doubleArrayOf(v0[2], v0[6], v0[10]),
            )
            val rv1 = arrayOf(
                doubleArrayOf(v1[0], v1[4], v1[8]),
                doubleArrayOf(v1[1], v1[5], v1[9]),
                doubleArrayOf(v1[2], v1[6], v1[10]),
            )
            // relative rotation in the camera frame: R_wc(t0)^T R_wc(t1) = R_cw0 R_cw1^T
            val dRv = mul3(rv0, transpose3(rv1))
            if (angleDeg(dRb) < 0.2) continue
            errs.add(angleDeg(mul3(transpose3(dRb), dRv)))
        }
        if (errs.size < 5) return null
        errs.sort()
        return doubleArrayOf(errs[errs.size / 2], errs[(errs.size * 9) / 10], errs.size.toDouble())
    }

    /** Returns (boardPath, vioPath, scaleFactor) or null when VIO poses were not recorded. */
    private fun scaleReport(frames: List<FrameRow>): Triple<Double, Double, Double>? {
        val rows = frames.filter { it.vio != null }
        if (rows.size < 20) return null
        var boardPath = 0.0
        var vioPath = 0.0
        var num = 0.0
        var den = 0.0
        var pbx = 0.0
        var pby = 0.0
        var pbz = 0.0
        var vpx = 0.0
        var vpy = 0.0
        var vpz = 0.0
        var have = false
        for (f in rows) {
            // camera position in the board frame: translation of (camera->board)
            val bx = f.m[3]
            val by = f.m[7]
            val bz = f.m[11]
            // camera position in the VIO world: -R^T t for (world->camera)
            val v = f.vio!!
            val r00 = v[0]; val r01 = v[1]; val r02 = v[2]; val t0 = v[3]
            val r10 = v[4]; val r11 = v[5]; val r12 = v[6]; val t1 = v[7]
            val r20 = v[8]; val r21 = v[9]; val r22 = v[10]; val t2 = v[11]
            val vx = -(r00 * t0 + r10 * t1 + r20 * t2)
            val vy = -(r01 * t0 + r11 * t1 + r21 * t2)
            val vz = -(r02 * t0 + r12 * t1 + r22 * t2)
            if (have) {
                val db = sqrt((bx - pbx) * (bx - pbx) + (by - pby) * (by - pby) + (bz - pbz) * (bz - pbz))
                val dv = sqrt((vx - vpx) * (vx - vpx) + (vy - vpy) * (vy - vpy) + (vz - vpz) * (vz - vpz))
                if (db < 0.5 && dv < 0.5) {
                    boardPath += db
                    vioPath += dv
                    num += db * dv
                    den += dv * dv
                }
            }
            pbx = bx; pby = by; pbz = bz
            vpx = vx; vpy = vy; vpz = vz
            have = true
        }
        if (den <= 0.0 || boardPath <= 0.0) return null
        return Triple(boardPath, vioPath, num / den)
    }

    // ---------------------------------------------------------------- IMU noise

    internal fun estimateNoise(imu: List<Imu>): ImuNoise {
        val gyro = imu.filter { it.type == 'g' }
        val accel = imu.filter { it.type == 'a' }
        val dts = gyro.zipWithNext { a, b -> b.ts - a.ts }.filter { it > 0 }
        val dt = if (dts.isEmpty()) 1.0 / 480.0 else dts.sorted()[dts.size / 2]
        val win = (5.0 / dt).toInt().coerceAtLeast(50)
        var bestScore = Double.MAX_VALUE
        var best = 0
        var i = 0
        while (i + win <= accel.size) {
            var score = 0.0
            for (k in 0..2) {
                var mean = 0.0
                for (j in i until i + win) mean += comp(accel[j], k)
                mean /= win
                var v = 0.0
                for (j in i until i + win) { val d = comp(accel[j], k) - mean; v += d * d }
                score += sqrt(v / win)
            }
            if (score < bestScore) { bestScore = score; best = i }
            i += win / 2
        }
        val a0 = best
        val a1 = (best + win).coerceAtMost(accel.size)
        val g0 = best.coerceAtMost(gyro.size)
        val g1 = (best + win).coerceAtMost(gyro.size)
        fun nd(list: List<Double>): Double {
            if (list.size < 3) return 0.0
            var s = 0.0
            for (j in 1 until list.size) { val d = list[j] - list[j - 1]; s += d * d }
            return sqrt(s / (list.size - 1)) / sqrt(2.0) * sqrt(dt)
        }
        val accelNd = (0..2).map { k -> nd((a0 until a1).map { comp(accel[it], k) }) }.average()
        val gyroNd = (0..2).map { k -> nd((g0 until g1).map { comp(gyro[it], k) }) }.average()
        val bias = DoubleArray(3) { k ->
            if (g1 <= g0) 0.0 else (g0 until g1).map { comp(gyro[it], k) }.average()
        }
        return ImuNoise(accelNd, gyroNd, 1.0 / dt, bias)
    }

    private fun comp(s: Imu, k: Int) = when (k) { 0 -> s.x; 1 -> s.y; else -> s.z }

    // ---------------------------------------------------------------- rotation fit

    internal fun fitCameraImu(frames: List<FrameRow>, imu: List<Imu>, bias0: DoubleArray): Fit {
        val gyro = imu.filter { it.type == 'g' }.sortedBy { it.ts }
        val pairs = ArrayList<DataPair>()
        for (i in 1 until frames.size) {
            val a = frames[i - 1]
            val b = frames[i]
            val dta = b.ts - a.ts
            if (dta <= 0.01 || dta > 0.25) continue
            val dRc = mul3(transpose3(rotOf(a.m)), rotOf(b.m))
            if (angleDeg(dRc) < 0.3) continue
            pairs.add(DataPair(a.ts, b.ts, dRc))
        }
        if (pairs.size < 15) {
            return Fit(DoubleArray(3), 0.0, bias0, Double.MAX_VALUE, pairs.size)
        }
        var p = doubleArrayOf(0.0, 0.0, 0.0, bias0[0], bias0[1], bias0[2], 0.0)
        var lastRms = Double.MAX_VALUE
        repeat(50) {
            val res = residuals(p, pairs, gyro)
            if (res.first.isEmpty()) return@repeat
            val r = res.first
            lastRms = res.second
            val eps = 1e-6
            val cols = 7
            val jac = Array(r.size) { DoubleArray(cols) }
            for (c in 0 until cols) {
                val pp = p.copyOf()
                pp[c] += eps
                val r2 = residuals(pp, pairs, gyro).first
                if (r2.size != r.size) continue
                for (row in r.indices) jac[row][c] = (r2[row] - r[row]) / eps
            }
            val a = Array(cols) { DoubleArray(cols) }
            val g = DoubleArray(cols)
            for (row in r.indices) {
                for (c in 0 until cols) {
                    g[c] += jac[row][c] * r[row]
                    for (d in 0 until cols) a[c][d] += jac[row][c] * jac[row][d]
                }
            }
            for (c in 0 until cols) a[c][c] += 1e-3
            val delta = solve7(a, g) ?: return@repeat
            for (c in 0 until cols) p[c] -= delta[c]
        }
        return Fit(doubleArrayOf(p[0], p[1], p[2]), p[6], doubleArrayOf(p[3], p[4], p[5]), lastRms, pairs.size)
    }

    private fun residuals(p: DoubleArray, pairs: List<DataPair>, gyro: List<Imu>): Pair<DoubleArray, Double> {
        val a = rotvecToMat(doubleArrayOf(p[0], p[1], p[2]))
        val bias = doubleArrayOf(p[3], p[4], p[5])
        val dt = p[6]
        val out = ArrayList<Double>(pairs.size)
        var sum2 = 0.0
        for (pr in pairs) {
            val dRb = integrate(gyro, pr.t0 + dt, pr.t1 + dt, bias) ?: continue
            val pred = mul3(mul3(a, dRb), transpose3(a))
            val e = matToRotvec(mul3(transpose3(pred), pr.dRcam))
            val deg = sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2])
            out.add(deg)
            sum2 += deg * deg
        }
        return Pair(out.toDoubleArray(), if (out.isEmpty()) Double.MAX_VALUE else sqrt(sum2 / out.size))
    }

    /** Integral of the bias-corrected gyro over [t0, t1], interpolating the ends. */
    private fun integrate(gyro: List<Imu>, t0: Double, t1: Double, bias: DoubleArray): M3? {
        if (gyro.size < 2 || t1 <= t0) return null
        if (t0 < gyro.first().ts || t1 > gyro.last().ts) return null
        val th = DoubleArray(3)
        var prevT = t0
        val prevW = omegaAt(gyro, t0)
        var i = lowerBound(gyro, t0)
        while (i < gyro.size && gyro[i].ts < t1) {
            val dt = gyro[i].ts - prevT
            if (dt > 0.0) {
                for (k in 0..2) th[k] += 0.5 * ((prevW[k] - bias[k]) + (comp(gyro[i], k) - bias[k])) * dt
            }
            prevT = gyro[i].ts
            for (k in 0..2) prevW[k] = comp(gyro[i], k)
            i++
        }
        val endW = omegaAt(gyro, t1)
        val dt = t1 - prevT
        if (dt > 0.0) for (k in 0..2) th[k] += 0.5 * ((prevW[k] - bias[k]) + (endW[k] - bias[k])) * dt
        val ang = sqrt(th[0] * th[0] + th[1] * th[1] + th[2] * th[2])
        if (ang < 1e-12) return identity3()
        return axisAngle(doubleArrayOf(th[0] / ang, th[1] / ang, th[2] / ang), ang)
    }

    /** Linearly interpolated gyro [rad/s] at time t. */
    private fun omegaAt(gyro: List<Imu>, t: Double): DoubleArray {
        val i = lowerBound(gyro, t)
        val out = DoubleArray(3)
        if (i <= 0) {
            for (k in 0..2) out[k] = comp(gyro[0], k)
            return out
        }
        if (i >= gyro.size) {
            for (k in 0..2) out[k] = comp(gyro[gyro.size - 1], k)
            return out
        }
        val a = gyro[i - 1]
        val b = gyro[i]
        val span = b.ts - a.ts
        val f = if (span > 0) ((t - a.ts) / span).coerceIn(0.0, 1.0) else 1.0
        for (k in 0..2) out[k] = comp(a, k) + (comp(b, k) - comp(a, k)) * f
        return out
    }

    private fun lowerBound(list: List<Imu>, t: Double): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (list[mid].ts < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // ---------------------------------------------------------------- linear algebra

    private fun rotOf(m: DoubleArray): M3 = arrayOf(
        doubleArrayOf(m[0], m[1], m[2]),
        doubleArrayOf(m[4], m[5], m[6]),
        doubleArrayOf(m[8], m[9], m[10]),
    )

    private fun identity3(): M3 = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 1.0),
    )

    private fun mul3(a: M3, b: M3): M3 {
        val c = arrayOf(DoubleArray(3), DoubleArray(3), DoubleArray(3))
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[i][k] * b[k][j]
            c[i][j] = s
        }
        return c
    }

    private fun transpose3(a: M3): M3 = arrayOf(
        doubleArrayOf(a[0][0], a[1][0], a[2][0]),
        doubleArrayOf(a[0][1], a[1][1], a[2][1]),
        doubleArrayOf(a[0][2], a[1][2], a[2][2]),
    )

    private fun angleDeg(r: M3): Double {
        val tr = r[0][0] + r[1][1] + r[2][2]
        return Math.toDegrees(acos(((tr - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
    }

    private fun rotvecToMat(deg: DoubleArray): M3 {
        val rv = doubleArrayOf(Math.toRadians(deg[0]), Math.toRadians(deg[1]), Math.toRadians(deg[2]))
        val ang = sqrt(rv[0] * rv[0] + rv[1] * rv[1] + rv[2] * rv[2])
        if (ang < 1e-12) return identity3()
        return axisAngle(doubleArrayOf(rv[0] / ang, rv[1] / ang, rv[2] / ang), ang)
    }

    private fun axisAngle(ax: DoubleArray, ang: Double): M3 {
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

    private fun matToRotvec(r: M3): DoubleArray {
        val tr = r[0][0] + r[1][1] + r[2][2]
        val ang = acos(((tr - 1.0) / 2.0).coerceIn(-1.0, 1.0))
        if (ang < 1e-9) return DoubleArray(3)
        val k = ang / (2.0 * sin(ang))
        return doubleArrayOf(
            Math.toDegrees(k * (r[2][1] - r[1][2])),
            Math.toDegrees(k * (r[0][2] - r[2][0])),
            Math.toDegrees(k * (r[1][0] - r[0][1])),
        )
    }

    private fun solve7(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 7
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val tmp = m[col]
            m[col] = m[piv]
            m[piv] = tmp
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }
}
