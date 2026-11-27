package com.rawmechatronics.easyscanner

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Seeds the engine's camera model from the camera's own factory calibration
 * (Camera2 LENS_INTRINSIC_CALIBRATION) so scanning does not depend on the
 * board camera-calibration step. The file is only written when no calibration
 * exists; a later board calibration (or deleting the file) replaces it.
 */
object VendorIntrinsics {
    fun ensure(ctx: Context, st: AppSettings) {
        val out = File(ctx.filesDir, "live_intrinsics.json")
        if (out.exists()) return
        val mgr = ctx.getSystemService(CameraManager::class.java)
        val id = st.cameraId
        val c = try { mgr.getCameraCharacteristics(id) } catch (e: Throwable) { return }
        val active = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val k = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) ?: return
        if (k.size < 4 || k[0] <= 0f || k[1] <= 0f) return
        val rawDist = c.get(CameraCharacteristics.LENS_DISTORTION)
        val dist = androidDistortionToOpenCv(rawDist)
        val json = JSONObject().apply {
            put("fx", k[0].toDouble())
            put("fy", k[1].toDouble())
            put("cx", k[2].toDouble())
            put("cy", k[3].toDouble())
            put("dist", JSONArray(dist.toList()))
            put("calibrated", true)
            put("width", active.width())
            put("height", active.height())
            put("camera_id", id)
            if (st.manualFocus) put("focus_diopters", st.focusDiopters.toDouble())
            put("rms_px", JSONObject.NULL)
        }
        try { out.writeText(json.toString(2)) } catch (_: Throwable) {}
    }

    /**
     * Converts Android Camera2 `LENS_DISTORTION` `[k1, k2, k3, p1, p2]` to OpenCV/Kalibr
     * Brown-Conrady order `[k1, k2, p1, p2, k3]`, guarding non-finite values.
     */
    internal fun androidDistortionToOpenCv(raw: FloatArray?): DoubleArray {
        if (raw == null || raw.size < 5) return DoubleArray(5)
        val out = doubleArrayOf(
            raw[0].toDouble(),
            raw[1].toDouble(),
            raw[3].toDouble(),
            raw[4].toDouble(),
            raw[2].toDouble(),
        )
        return if (out.all { it.isFinite() }) out else DoubleArray(5)
    }
}
