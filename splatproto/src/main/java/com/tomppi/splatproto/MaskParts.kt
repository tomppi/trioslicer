package com.tomppi.splatproto

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * The first keyframe's auto mask as pickable parts.
 *
 * U2Net returns one silhouette; when it also swallowed part of the table (or missed an arm), the
 * user corrects it by tapping the connected regions it got wrong. The kept set is written back to
 * the same seed PNG the pipeline reads. Nothing here runs a model - it is connected components
 * over the seed and a green overlay for the screen.
 */
object MaskParts {
    private const val TAG = "MaskParts"

    private var labels: Mat? = null
    private var sizes: IntArray = IntArray(0)
    private var fullWidth = 0
    private var fullHeight = 0
    private var kept: MutableSet<Int>? = null

    private fun seedFile(context: Context): File = File(context.filesDir, "masker/seed.png")

    /** Splits the auto mask into connected parts (256 grid) so the user can pick what to keep. */
    fun prepareParts(seed: File, image: File): Int {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(image.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return 0
        val mask = Imgcodecs.imread(seed.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        if (mask.empty()) return 0
        val small = Mat()
        Imgproc.resize(mask, small, Size(256.0, 256.0), 0.0, 0.0, Imgproc.INTER_NEAREST)
        mask.release()
        val labelMat = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(small, labelMat, stats, centroids)
        small.release()
        stats.release()
        centroids.release()
        labels = labelMat
        fullWidth = width
        fullHeight = height
        sizes = IntArray(count)
        val keep = HashSet<Int>()
        for (id in 1 until count) keep.add(id)
        kept = keep
        return count - 1
    }

    fun partCount(): Int = (sizes.size - 1).coerceAtLeast(0)

    fun keptCount(): Int = kept?.size ?: 0

    /** Toggles the part under a normalised (stored-orientation) point and rewrites the seed. */
    fun togglePartAt(nx: Float, ny: Float): Boolean {
        val labelMat = labels ?: return false
        val x = (nx * 255).toInt().coerceIn(0, 255)
        val y = (ny * 255).toInt().coerceIn(0, 255)
        val id = labelMat.get(y, x)[0].toInt()
        if (id <= 0) return false
        val set = kept ?: return false
        if (!set.remove(id)) set.add(id)
        writeSeedFromParts()
        return true
    }

    fun resetParts() {
        val labelMat = labels ?: return
        val set = HashSet<Int>()
        for (id in 1 until sizes.size) set.add(id)
        kept = set
        writeSeedFromParts()
    }

    private fun writeSeedFromParts() {
        val labelMat = labels ?: return
        val set = kept ?: return
        val small = Mat(256, 256, CvType.CV_8UC1, Scalar(0.0))
        val bytes = ByteArray(256 * 256)
        for (y in 0 until 256) for (x in 0 until 256) {
            val id = labelMat.get(y, x)[0].toInt()
            if (id > 0 && set.contains(id)) bytes[y * 256 + x] = 255.toByte()
        }
        small.put(0, 0, bytes)
        val full = Mat()
        Imgproc.resize(small, full, Size(fullWidth.toDouble(), fullHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        small.release()
        sessionlessSeedWrite(full)
        full.release()
    }

    private fun sessionlessSeedWrite(mask: Mat) {
        val file = seedFile(contextRef ?: return)
        file.parentFile?.mkdirs()
        Imgcodecs.imwrite(file.absolutePath, mask)
    }

    @Volatile private var contextRef: Context? = null

    fun rememberContext(context: Context) {
        contextRef = context
    }

    /** Frees the part-picking labels. */
    fun releaseParts() {
        runCatching { labels?.release() }
        labels = null
        kept = null
        sizes = IntArray(0)
    }

    /** Resident set size of this process in MB, for memory logging. */
    fun rssMb(): Long = runCatching {
        val status = File("/proc/self/status").readLines()
        val line = status.firstOrNull { it.startsWith("VmRSS:") } ?: return 0L
        line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()?.div(1024) ?: 0L
    }.getOrDefault(0L)

    fun keptArea(): Float {
        val labelMat = labels ?: return 0f
        val set = kept ?: return 0f
        var n = 0
        for (y in 0 until 256) for (x in 0 until 256) {
            val id = labelMat.get(y, x)[0].toInt()
            if (id > 0 && set.contains(id)) n++
        }
        return n.toFloat() / (256f * 256f)
    }

    /** Green overlay of the seed mask over the keyframe, in display orientation. */
    fun previewBitmap(seed: File, image: File): Bitmap? = runCatching {
        val bgr = Imgcodecs.imread(image.absolutePath, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
        if (bgr.empty()) return null
        val mask = Imgcodecs.imread(seed.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        if (mask.empty()) return null
        val sized = Mat()
        Imgproc.resize(mask, sized, Size(bgr.cols().toDouble(), bgr.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        val rgb = Mat()
        Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGBA)
        val tint = Mat(bgr.size(), CvType.CV_8UC4, Scalar(80.0, 220.0, 120.0, 255.0))
        val overlay = Mat()
        Core.addWeighted(rgb, 0.6, tint, 0.4, 0.0, overlay)
        overlay.copyTo(rgb, sized)
        val orientation = runCatching {
            android.media.ExifInterface(image.absolutePath).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        val code = when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> Core.ROTATE_90_CLOCKWISE
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> Core.ROTATE_180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> -1
        }
        val display = if (code >= 0) Mat().also { Core.rotate(rgb, it, code) } else rgb
        val bitmap = Bitmap.createBitmap(display.cols(), display.rows(), Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(display, bitmap)
        bgr.release(); mask.release(); sized.release(); rgb.release(); tint.release(); overlay.release()
        if (display !== rgb) display.release()
        bitmap
    }.getOrElse { error ->
        Log.e(TAG, "preview failed", error)
        null
    }
}
