package com.tomppi.splatproto

import org.json.JSONObject
import java.io.File

object Reconstruction {
    /**
     * Runs the native pipeline on [scan] (blocking). [markerSizeMm] is the edge of a printed ArUco
     * scale marker in the scene (0 = none). Returns the report, or an error message.
     */
    fun run(scan: Scan, processing: Processing, markerSizeMm: Int, progress: NativeCore.Progress): Result<JSONObject> {
        val loaded = scan.load() ?: return Result.failure(IllegalStateException("scan.json is missing or damaged"))
        val cams = loaded.cameras
        val imgs = loaded.images.filter { File(scan.imagesDir, it.file).exists() }
        if (imgs.size < 3) return Result.failure(IllegalStateException("Need at least three keyframes"))
        val camArr = DoubleArray(cams.size * 15)
        val flags = IntArray(cams.size)
        cams.forEachIndexed { k, c ->
            val i = c.intr
            val p = c.position
            doubleArrayOf(c.index.toDouble(), i.width.toDouble(), i.height.toDouble(), i.fx, i.fy, i.cx, i.cy,
                i.k1, i.k2, i.p1, i.p2, i.k3,
                p?.get(0)?.toDouble() ?: 0.0, p?.get(1)?.toDouble() ?: 0.0, p?.get(2)?.toDouble() ?: 0.0,
            ).copyInto(camArr, k * 15)
            flags[k] = (if (i.calibrated) 1 else 0) or (if (p != null) 2 else 0)
        }
        val up = FloatArray(imgs.size * 3)
        imgs.forEachIndexed { k, im -> im.up.copyInto(up, k * 3) }
        val motion = DoubleArray(imgs.size * 6)
        imgs.forEachIndexed { k, im ->
            val g = im.gyro
            doubleArrayOf(im.timestampNs / 1e9, im.rsSkewNs / 1e9, g?.get(0)?.toDouble() ?: 0.0,
                g?.get(1)?.toDouble() ?: 0.0, g?.get(2)?.toDouble() ?: 0.0, if (g != null) 1.0 else 0.0,
            ).copyInto(motion, k * 6)
        }
        val options = processing.asOptions(markerSizeMm)
        val json = NativeCore.reconstruct(
            camArr, flags,
            imgs.map { File(scan.imagesDir, it.file).absolutePath }.toTypedArray(),
            imgs.map { it.camera }.toIntArray(), imgs.map { it.rig }.toIntArray(), up, motion,
            scan.dir.absolutePath, scan.imagesDir.absolutePath, options, progress,
        )
        val report = runCatching { JSONObject(json) }.getOrElse { return Result.failure(it) }
        return if (report.optBoolean("ok")) Result.success(report)
        else Result.failure(IllegalStateException(report.optString("message", "Reconstruction failed")))
    }
}
