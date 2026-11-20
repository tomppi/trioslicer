package com.rawmechatronics.imucalib.core

import java.util.Locale

/** Camera model of the VIO image stream (pinhole + radtan, 4 coefficients). */
data class CalibCameraModel(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val dist: DoubleArray,
    val width: Int = 640,
    val height: Int = 480,
)

/** Writes the Kalibr camchain-imucam format OpenVINS reads (T_cam_imu maps imu -> camera). */
object KalibrYaml {

    fun imuCamChain(p: CalibParams, cam: CalibCameraModel): String {
        val r = So3.exp(p.rCi)
        fun n(v: Double) = String.format(Locale.US, "%.12f", v)
        val sb = StringBuilder()
        sb.append("%YAML:1.0\n\ncam0:\n  T_cam_imu:\n")
        for (row in 0 until 3) {
            sb.append("    - [")
                .append(n(r[row * 3])).append(", ")
                .append(n(r[row * 3 + 1])).append(", ")
                .append(n(r[row * 3 + 2])).append(", ")
                .append(n(p.pCi[row])).append("]\n")
        }
        sb.append("    - [0.0, 0.0, 0.0, 1.0]\n")
        sb.append("  cam_overlaps: []\n  camera_model: pinhole\n")
        sb.append("  distortion_coeffs: [")
            .append(n(cam.dist.getOrElse(0) { 0.0 })).append(", ")
            .append(n(cam.dist.getOrElse(1) { 0.0 })).append(", ")
            .append(n(cam.dist.getOrElse(2) { 0.0 })).append(", ")
            .append(n(cam.dist.getOrElse(3) { 0.0 })).append("]\n")
        sb.append("  distortion_model: radtan\n")
        sb.append("  intrinsics: [")
            .append(n(cam.fx)).append(", ").append(n(cam.fy)).append(", ")
            .append(n(cam.cx)).append(", ").append(n(cam.cy)).append("]\n")
        sb.append("  resolution: [").append(cam.width).append(", ").append(cam.height).append("]\n")
        sb.append("  rostopic: /cam0/image_raw\n")
        sb.append("  timeshift_cam_imu: ").append(String.format(Locale.US, "%.6f", p.td)).append('\n')
        return sb.toString()
    }
}
