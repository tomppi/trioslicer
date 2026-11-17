package com.rawmechatronics.easyscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ExifInterface
import org.json.JSONObject
import java.io.File

/** Decoding a session's photographs for the viewer, turned upright. */
object Photos {
    /**
     * Degrees clockwise that show a session's photos the way the live preview
     * showed them (portrait): an EXIF turn when the camera wrote one, else the
     * sensor orientation of the camera the session names. The JPEGs keep the
     * raw sensor orientation, so their tag usually says "normal".
     */
    fun rotation(context: Context, dir: File, first: File?): Int {
        val exif = first?.let { runCatching { ExifInterface(it.absolutePath) }.getOrNull() }
        when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> return 90
            ExifInterface.ORIENTATION_ROTATE_180 -> return 180
            ExifInterface.ORIENTATION_ROTATE_270 -> return 270
        }
        return runCatching {
            val id = JSONObject(File(dir, "session.json").readText()).getJSONObject("eyes").getJSONObject("left").get("camera_id").toString()
            context.getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        }.getOrDefault(90)
    }

    /** The photo decoded to roughly [maxPx] on its long side and turned by [degrees]. */
    fun decode(file: File, maxPx: Int, degrees: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        var sample = 1
        while (long / (sample * 2) >= maxPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (degrees % 360 == 0) return bmp
        val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (turned !== bmp) bmp.recycle()
        return turned
    }
}
