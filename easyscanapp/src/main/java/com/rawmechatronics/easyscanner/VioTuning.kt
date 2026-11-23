package com.rawmechatronics.easyscanner

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * The one number the manual VIO step adjusts: the camera-IMU time offset in
 * `kalibr_imucam_chain.yaml`. OpenVINS reads it at startup, so the app rewrites
 * the file and restarts the filter when the slider is applied.
 */
object VioTuning {
    private val line = Regex("(?m)^(\\s*timeshift_cam_imu:\\s*)([-+0-9.eE]+).*$")

    fun file(ctx: Context): File = File(ctx.filesDir, "openvins-config/kalibr_imucam_chain.yaml")

    fun readMs(ctx: Context): Double {
        val f = file(ctx)
        if (!f.isFile) return 0.0
        return try {
            val m = line.find(f.readText()) ?: return 0.0
            m.groupValues[2].toDoubleOrNull()?.times(1000.0) ?: 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    fun writeMs(ctx: Context, ms: Double): Boolean {
        val f = file(ctx)
        if (!f.isFile) return false
        return try {
            val text = f.readText()
            val match = line.find(text) ?: return false
            val sec = String.format(Locale.US, "%.9f", ms / 1000.0)
            f.writeText(text.replaceRange(match.range, match.groupValues[1] + sec))
            true
        } catch (e: Exception) {
            false
        }
    }
}
