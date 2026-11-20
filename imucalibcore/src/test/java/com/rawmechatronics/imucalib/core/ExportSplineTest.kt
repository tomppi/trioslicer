package com.rawmechatronics.imucalib.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExportSplineTest {
    @Test
    fun exportCalibratedSplineForGpu() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: ""
        assumeTrue(dir.isNotEmpty())
        val session = CalibrationSession.fromCsv(
            File(dir, "frames.csv").readText(), File(dir, "imu.csv").readText(),
            File(dir, "corners.csv").readText())!!
        val cam = PinholeModel(431.19137640949805, 430.718509351878, 324.17995250822435, 249.9409677695653,
            0.0534236944418012, -0.06088468904926652, 0.006674683680240228, 0.0001703966471319626)
        val opts = CalibOptions(splineDt = 0.02, imuSubsample = 4, maxIterations = 60,
            useBoardPosePrior = false, pixelSigmaPx = 0.30)
        val cal = KalibrCalibrator(session.boardPoses, session.imu, opts, session.corners, cam, session.board)
        val r = cal.calibrate()
        val sp = r.spline.spline
        val coeff = sp.coefficients()
        val nKnots = coeff.size
        val dt = 0.02
        val t0 = sp.startTime
        val obs = session.corners
        val out = File("/root/gpu_real.bin")
        val buf = ByteBuffer.allocate(12 + nKnots * 6 * 4 + obs.size * (4 + 6 * 4 + 3 * 4 + 2 * 4)).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(obs.size); buf.putInt(36); buf.putInt(nKnots)
        for (i in 0 until nKnots) for (d in 0 until 6) buf.putFloat(coeff[i][d].toFloat())
        for (o in obs) {
            val base = kotlin.math.floor((o.t - t0) / dt).toInt()
            val b = sp.basis(o.t).b
            val x = if (o.xyz != null) o.xyz!! else session.board.corner3d(o.id)!!
            buf.putInt(base)
            for (k in 0 until 6) buf.putFloat(b[k].toFloat())
            for (d in 0 until 3) buf.putFloat(x[d].toFloat())
            buf.putFloat(o.u.toFloat()); buf.putFloat(o.v.toFloat())
        }
        FileOutputStream(out).use { it.write(buf.array()) }
        println("exported " + out.length() + " bytes, knots=" + nKnots + " obs=" + obs.size +
            " pixel_rms=" + r.diagnostics.rmsPixelPx + " gyro=" + r.diagnostics.rmsGyro +
            " accel=" + r.diagnostics.rmsAccel)
    }
}
