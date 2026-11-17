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
        val json = JSONObject().apply {
            put("fx", k[0].toDouble())
            put("fy", k[1].toDouble())
            put("cx", k[2].toDouble())
            put("cy", k[3].toDouble())
            put("dist", JSONArray(listOf(0.0, 0.0, 0.0, 0.0, 0.0)))
            put("calibrated", true)
            put("width", active.width())
            put("height", active.height())
            put("camera_id", id)
            if (st.manualFocus) put("focus_diopters", st.focusDiopters.toDouble())
            put("rms_px", JSONObject.NULL)
        }
        try { out.writeText(json.toString(2)) } catch (_: Throwable) {}
    }
}
