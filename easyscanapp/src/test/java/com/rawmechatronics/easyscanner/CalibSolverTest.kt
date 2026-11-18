package com.rawmechatronics.easyscanner

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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

    private val prevFrames = ArrayList<DoubleArray>()

    @Test
    fun endToEndSolvesSyntheticTrackerSession() {
        val recordsRoot = java.nio.file.Files.createTempDirectory("records").toFile()
        val configDir = java.nio.file.Files.createTempDirectory("cfg").toFile()
        val srcAssets = File("src/main/assets/openvins")
        for (name in listOf("estimator_config.yaml", "kalibr_imu_chain.yaml", "kalibr_imucam_chain.yaml")) {
            val src = File(srcAssets, name)
            if (src.exists()) src.copyTo(File(configDir, name), overwrite = true)
        }

        val aTrue = rotvecToMat(doubleArrayOf(30.0, -20.0, 55.0))
        val dtTrue = 0.011
        val biasTrue = doubleArrayOf(0.0015, -0.0008, 0.0009)
        val n = 180
        val dtFrame = 1.0 / 30.0
        val t0 = 200.0
        val rng = java.util.Random(11)

        val framesDir = File(recordsRoot, "session-1").apply { mkdirs() }
        val frames = StringBuilder()
        frames.append("frame,ts_ns,scan_on,detected,corners,cam_x,cam_y,cam_z,roll,pitch,yaw,distance,reproj_rms,fx,fy,cx,cy,laser_state,laser_points,proc_ms,laser_cam_count,pose16,vio_pose16,jpeg\n")
        val imu = StringBuilder("type,ts_ns,x,y,z\n")

        var r = rotvecToMat(doubleArrayOf(10.0, -5.0, 25.0))
        var p = doubleArrayOf(0.0, 0.0, 0.45)
        for (i in 0 until n) {
            val ax = doubleArrayOf(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian())
            val norm = sqrt(ax[0] * ax[0] + ax[1] * ax[1] + ax[2] * ax[2])
            val dR = axisAngle(doubleArrayOf(ax[0] / norm, ax[1] / norm, ax[2] / norm), 0.20 + rng.nextDouble() * 0.10)
            r = mul(r, dR)
            p = doubleArrayOf(p[0] + 0.001 * rng.nextGaussian(), p[1] + 0.001 * rng.nextGaussian(), 0.45 + 0.001 * rng.nextGaussian())
            val ts = t0 + i * dtFrame
            val m = DoubleArray(16)
            for (row in 0..2) for (col in 0..2) m[row * 4 + col] = r[row][col]
            for (row in 0..2) m[row * 4 + 3] = p[row]
            m[15] = 1.0
            val rT = transpose(r)
            val tInv = DoubleArray(3) { k -> -(rT[k][0] * p[0] + rT[k][1] * p[1] + rT[k][2] * p[2]) }
            val inv = DoubleArray(16)
            for (row in 0..2) for (col in 0..2) inv[row * 4 + col] = rT[row][col]
            for (row in 0..2) inv[row * 4 + 3] = tInv[row]
            inv[15] = 1.0
            frames.append(i).append(',').append((ts * 1e9).toLong()).append(",1.0,1.0,20,0,0,0,0,0,0,0.45,0.2,432,432,321,242,0,0,10.0,0,")
                .append(m.joinToString(" ") { it.toString() }).append(',')
                .append(inv.joinToString(" ") { it.toString() }).append(",f").append("%05d".format(i)).append(".jpg\n")

            if (i > 0) {
                val prev = prevFrames[i - 1]
                val r0 = arrayOf(
                    doubleArrayOf(prev[0], prev[1], prev[2]),
                    doubleArrayOf(prev[4], prev[5], prev[6]),
                    doubleArrayOf(prev[8], prev[9], prev[10]),
                )
                val dRc = mul(transpose(r0), r)
                val dRb = mul(mul(transpose(aTrue), dRc), aTrue)
                val rv = matToRotvec(dRb)
                val s0 = t0 + (i - 1) * dtFrame + dtTrue
                val s1 = t0 + i * dtFrame + dtTrue
                val steps = 8
                for (k in 0..steps) {
                    val tt = s0 + (s1 - s0) * k / steps
                    val gyroNoise = 1e-3
                    val w = doubleArrayOf(
                        rv[0] / (s1 - s0) + biasTrue[0] + gyroNoise * rng.nextGaussian(),
                        rv[1] / (s1 - s0) + biasTrue[1] + gyroNoise * rng.nextGaussian(),
                        rv[2] / (s1 - s0) + biasTrue[2] + gyroNoise * rng.nextGaussian(),
                    )
                    imu.append("g,").append((tt * 1e9).toLong()).append(',').append(w[0]).append(',').append(w[1]).append(',').append(w[2]).append('\n')
                }
            }
            // Accelerometer samples covering the same session, so the solver's noise estimate and
            // its >=500 sample / >=5 s validation are exercised like a real still recording.
            val accelNoise = 5e-3
            for (k in 0 until 8) {
                val tt = ts + (k / 8.0) * dtFrame
                imu.append("a,").append((tt * 1e9).toLong()).append(',')
                    .append(accelNoise * rng.nextGaussian()).append(',')
                    .append(accelNoise * rng.nextGaussian()).append(',')
                    .append(9.81 + accelNoise * rng.nextGaussian()).append('\n')
            }
            prevFrames.add(m)
        }
        File(framesDir, "frames.csv").writeText(frames.toString())
        File(framesDir, "imu.csv").writeText(imu.toString())

        val res = CalibSolver.solveIn(recordsRoot, configDir)
        assertTrue("solve failed: " + res.message, res.ok)
        val all = res.lines.joinToString("\n")
        println("solve result:\n" + all)

        val yaml = File(configDir, "kalibr_imucam_chain.yaml").readText()
        val dtVal = yaml.lines().first { it.trim().startsWith("timeshift_cam_imu:") }.substringAfter(":").trim().toDouble()
        assertTrue("time offset wrong: " + dtVal, abs(dtVal - dtTrue) < 0.004)
        assertTrue("intrinsics not preserved", yaml.contains("432.179802389"))

        val rows = yaml.lines().filter { it.trim().startsWith("- [") }.map { it.trim().removePrefix("- [").removeSuffix("]") }
        val rFit = arrayOf(
            rows[0].split(',').take(3).map { it.trim().toDouble() }.toDoubleArray(),
            rows[1].split(',').take(3).map { it.trim().toDouble() }.toDoubleArray(),
            rows[2].split(',').take(3).map { it.trim().toDouble() }.toDoubleArray(),
        )
        val diff = mul(transpose(rFit), aTrue)
        val angErr = Math.toDegrees(Math.acos(((diff[0][0] + diff[1][1] + diff[2][2] - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
        assertTrue("rotation wrong: " + angErr, angErr < 1.0)
        assertTrue("scale factor not printed", all.contains("scale factor:"))
        val scale = all.substringAfter("scale factor:").trim().split(" ")[0].toDouble()
        assertTrue("scale factor wrong: " + scale, abs(scale - 1.0) < 0.02)
    }

}
