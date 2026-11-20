package com.rawmechatronics.easyscanner

import kotlin.math.abs

/**
 * Validation for a loaded VIO camera-IMU calibration profile
 * (Kalibr camchain-imucam YAML: T_cam_imu + timeshift_cam_imu).
 *
 * Pure Kotlin so the loader in [CalibrationActivity] can be tested on the JVM.
 */
object VioCalibrationProfile {

    data class Result(
        val ok: Boolean,
        val message: String,
        val timeshiftMs: Double = 0.0,
        val translationMm: DoubleArray = DoubleArray(3),
    )

    fun validate(text: String): Result {
        if (text.isBlank()) return Result(false, "file is empty")
        if (text.length > 256 * 1024) return Result(false, "file is too large for a camera-IMU profile")
        val rows = ArrayList<DoubleArray>()
        var inPose = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("T_cam_imu:")) {
                inPose = true
                continue
            }
            if (!inPose) continue
            if (line.startsWith("- [")) {
                val body = line.removePrefix("- [").substringBefore(']')
                val values = body.split(',').map { it.trim().toDoubleOrNull() }
                if (values.size >= 4 && values.take(4).all { it != null }) {
                    rows.add(values.take(4).map { it!! }.toDoubleArray())
                }
                if (rows.size == 4) break
            } else if (line.isNotEmpty()) {
                inPose = false
            }
        }
        if (rows.size < 3) return Result(false, "no T_cam_imu matrix found (expected Kalibr camchain-imucam YAML)")
        val r = Array(3) { i -> DoubleArray(3) { j -> rows[i][j] } }
        if (r.any { row -> row.any { !it.isFinite() } }) return Result(false, "T_cam_imu contains non-finite numbers")
        // orthonormality: R R^T must be ~I and det ~ +1
        var maxErr = 0.0
        for (i in 0 until 3) for (j in 0 until 3) {
            val dot = r[i][0] * r[j][0] + r[i][1] * r[j][1] + r[i][2] * r[j][2]
            maxErr = maxOf(maxErr, abs(dot - if (i == j) 1.0 else 0.0))
        }
        val det = r[0][0] * (r[1][1] * r[2][2] - r[1][2] * r[2][1]) -
            r[0][1] * (r[1][0] * r[2][2] - r[1][2] * r[2][0]) +
            r[0][2] * (r[1][0] * r[2][1] - r[1][1] * r[2][0])
        if (maxErr > 0.02) return Result(false, "T_cam_imu rotation is not orthonormal (max |R R^T - I| = " +
            String.format(java.util.Locale.US, "%.4f", maxErr) + ")")
        if (abs(det - 1.0) > 0.02) return Result(false, "T_cam_imu rotation determinant is not +1 (det = " +
            String.format(java.util.Locale.US, "%.4f", det) + ")")
        val ts = Regex("(?m)^\\s*timeshift_cam_imu:\\s*([-+0-9.eE]+)").find(text)
            ?: return Result(false, "no timeshift_cam_imu found")
        val td = ts.groupValues[1].toDoubleOrNull()
            ?: return Result(false, "timeshift_cam_imu is not a number")
        if (!td.isFinite() || abs(td) > 0.2) return Result(false, "timeshift_cam_imu out of range (|td| > 200 ms)")
        val t = doubleArrayOf(rows[0][3], rows[1][3], rows[2][3])
        return Result(true,
            "valid camera-IMU profile: timeshift " + String.format(java.util.Locale.US, "%.3f ms", td * 1000) +
                ", lever arm " + String.format(java.util.Locale.US, "%.1f, %.1f, %.1f mm", t[0] * 1000, t[1] * 1000, t[2] * 1000),
            td * 1000.0, DoubleArray(3) { t[it] * 1000.0 })
    }
}
