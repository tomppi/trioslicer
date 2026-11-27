package com.tomppi.splatproto

import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares the pinhole training image set (`cross3dgs/images`) for Cross3DGS:
 * 1. Undistorts each frame using the exact BA-refined intrinsics from `sparse/0/cameras.txt`
 *    (falling back to `scan.json`), matching the PINHOLE model written into `cameras.bin`.
 * 2. When [useMasks] is true and U2Net masks exist (`masks/<file>.png`), undistorts each mask
 *    and composites background pixels to pure black `(0, 0, 0)` (Cross3DGS's render background).
 * 3. Downscales the longest side to [maxResolution] when larger (Cross3DGS automatically scales
 *    camera `fx/fy/cx/cy` to the actual image dimensions).
 */
object Cross3dgsUndistort {
    private const val TAG = "Cross3dgsUndistort"

    private data class CamParams(
        val fx: Double, val fy: Double, val cx: Double, val cy: Double,
        val k1: Double, val k2: Double, val p1: Double, val p2: Double, val k3: Double,
    ) {
        val hasDistortion: Boolean
            get() = abs(k1) > 1e-7 || abs(k2) > 1e-7 || abs(p1) > 1e-7 || abs(p2) > 1e-7 || abs(k3) > 1e-7

        fun signature(): String =
            "%.3f,%.3f,%.3f,%.3f,%.5f,%.5f,%.5f,%.5f,%.5f".format(fx, fy, cx, cy, k1, k2, p1, p2, k3)
    }

    fun ensure(scan: Scan, maxResolution: Int = 0, useMasks: Boolean = true): Boolean {
        val loaded = scan.load() ?: return false
        if (loaded.images.isEmpty()) return false
        val cam = readCamerasTxt(File(scan.dir, "sparse/0/cameras.txt"))
            ?: loaded.cameras.firstOrNull()?.intr?.let {
                CamParams(it.fx, it.fy, it.cx, it.cy, it.k1, it.k2, it.p1, it.p2, it.k3)
            } ?: return false

        val masksDir = File(scan.dir, "masks")
        val maskCount = if (useMasks && masksDir.isDirectory) {
            masksDir.listFiles { f -> f.isFile && f.name.endsWith(".png", true) }?.size ?: 0
        } else 0
        val effectiveMasks = useMasks && maskCount > 0

        val outDir = File(scan.dir, "cross3dgs/images").apply { mkdirs() }
        val metaFile = File(scan.dir, "cross3dgs/.meta")
        val expectedStamp = "maxRes=$maxResolution\nuseMasks=$effectiveMasks\nmasks=$maskCount\n" +
            "images=${loaded.images.size}\ncam=${cam.signature()}\n"
        val existingCount = outDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg", true) }?.size ?: 0
        if (metaFile.isFile && runCatching { metaFile.readText() }.getOrNull() == expectedStamp &&
            existingCount == loaded.images.size
        ) {
            return true
        }

        if (!OpenCVLoader.initLocal()) return false
        outDir.listFiles()?.forEach { it.delete() }

        val k = Mat(3, 3, CvType.CV_64F).apply {
            put(0, 0, cam.fx); put(0, 1, 0.0); put(0, 2, cam.cx)
            put(1, 0, 0.0); put(1, 1, cam.fy); put(1, 2, cam.cy)
            put(2, 0, 0.0); put(2, 1, 0.0); put(2, 2, 1.0)
        }
        val dist = Mat(1, 5, CvType.CV_64F).apply {
            put(0, 0, cam.k1); put(0, 1, cam.k2); put(0, 2, cam.p1)
            put(0, 3, cam.p2); put(0, 4, cam.k3)
        }
        val jpegParams = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95)
        var written = 0
        var masked = 0
        try {
            for (im in loaded.images) {
                val srcFile = File(scan.imagesDir, im.file)
                if (!srcFile.isFile) continue
                val src = Imgcodecs.imread(srcFile.absolutePath, Imgcodecs.IMREAD_COLOR)
                if (src == null || src.empty()) continue
                val undist = Mat()
                val finalImg = Mat()
                try {
                    if (cam.hasDistortion) {
                        Calib3d.undistort(src, undist, k, dist)
                    } else {
                        src.copyTo(undist)
                    }
                    if (effectiveMasks) {
                        val mf = findMask(masksDir, im.file)
                        if (mf != null) {
                            val rawMask = Imgcodecs.imread(mf.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
                            if (rawMask != null && !rawMask.empty()) {
                                val alignedMask = Mat()
                                val binMask = Mat()
                                try {
                                    if (rawMask.cols() != undist.cols() || rawMask.rows() != undist.rows()) {
                                        Imgproc.resize(rawMask, alignedMask, undist.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                                    } else {
                                        rawMask.copyTo(alignedMask)
                                    }
                                    val undistMask = if (cam.hasDistortion) {
                                        Mat().also { Calib3d.undistort(alignedMask, it, k, dist) }
                                    } else {
                                        alignedMask
                                    }
                                    try {
                                        Imgproc.threshold(undistMask, binMask, 127.0, 255.0, Imgproc.THRESH_BINARY)
                                        val maskedOut = Mat.zeros(undist.size(), undist.type())
                                        try {
                                            undist.copyTo(maskedOut, binMask)
                                            maskedOut.copyTo(undist)
                                            masked++
                                        } finally {
                                            maskedOut.release()
                                        }
                                    } finally {
                                        if (undistMask !== alignedMask) undistMask.release()
                                    }
                                } finally {
                                    rawMask.release(); alignedMask.release(); binMask.release()
                                }
                            }
                        }
                    }
                    val maxSide = max(undist.cols(), undist.rows())
                    if (maxResolution > 0 && maxSide > maxResolution) {
                        val scale = maxResolution.toDouble() / maxSide.toDouble()
                        val w = (undist.cols() * scale).roundToInt().coerceAtLeast(16)
                        val h = (undist.rows() * scale).roundToInt().coerceAtLeast(16)
                        Imgproc.resize(undist, finalImg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                    } else {
                        undist.copyTo(finalImg)
                    }
                    if (Imgcodecs.imwrite(File(outDir, im.file).absolutePath, finalImg, jpegParams)) {
                        written++
                    }
                } finally {
                    src.release(); undist.release(); finalImg.release()
                }
            }
        } finally {
            k.release(); dist.release(); jpegParams.release()
        }
        Log.i(TAG, "prepared $written images (masked=$masked, maxRes=$maxResolution, dist=${cam.hasDistortion})")
        if (written > 0) {
            runCatching { metaFile.writeText(expectedStamp) }
        }
        return written > 0
    }

    private fun findMask(masksDir: File, imageName: String): File? {
        val exact = File(masksDir, "$imageName.png")
        if (exact.isFile) return exact
        val stem = imageName.substringBeforeLast('.')
        val byStem = File(masksDir, "$stem.png")
        return byStem.takeIf { it.isFile }
    }

    private fun readCamerasTxt(file: File): CamParams? {
        if (!file.isFile) return null
        return runCatching {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val s = line.trim()
                    if (s.isEmpty() || s.startsWith("#")) continue
                    val p = s.split(Regex("\\s+"))
                    if (p.size < 8) continue
                    val model = p[1]
                    val params = p.drop(4).mapNotNull { it.toDoubleOrNull() }
                    if (model == "OPENCV" && params.size >= 8) {
                        // cameras.txt stores COLMAP pixel-centre cx+0.5, cy+0.5; OpenCV undistort uses pixel-origin cx, cy.
                        return@useLines CamParams(
                            params[0], params[1], params[2] - 0.5, params[3] - 0.5,
                            params[4], params[5], params[6], params[7], 0.0,
                        )
                    } else if (model == "PINHOLE" && params.size >= 4) {
                        return@useLines CamParams(
                            params[0], params[1], params[2] - 0.5, params[3] - 0.5,
                            0.0, 0.0, 0.0, 0.0, 0.0,
                        )
                    }
                }
                null
            }
        }.getOrNull()
    }
}
