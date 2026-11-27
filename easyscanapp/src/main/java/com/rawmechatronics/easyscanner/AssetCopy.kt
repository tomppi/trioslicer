package com.rawmechatronics.easyscanner

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Copies a packaged asset into app storage when it is missing or when the
 * packaged copy has changed. A size-only guard is not enough for a mutable
 * model asset: an older lightglue.tflite in filesDir must be replaced when the
 * APK ships a different keypoint count.
 */
object AssetCopy {
    fun ensure(ctx: Context, assetName: String, target: File): File {
        val assetLen = try {
            ctx.assets.openFd(assetName).use { it.length }
        } catch (_: Exception) {
            -1L
        }
        val needsCopy = !target.exists() || target.length() < 1000L ||
            (assetLen >= 0L && target.length() != assetLen)
        if (needsCopy) {
            ctx.assets.open(assetName).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }
        return target
    }
}
