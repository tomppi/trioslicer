package com.tomppi.splatproto

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

/**
 * On-device object masking with U2Net on the Qualcomm HTP.
 *
 * The model is the U2Net segmentation network from Qualcomm AI Hub (w8a8), compiled to an HTP
 * v73 context binary (`models/u2net.bin`): 320x320 RGB uint8 in, 320x320 uint8 alpha out.
 * Inference runs through QAIRT's qnn-net-run, staged to /data/local/tmp with its runtime
 * libraries and started through root (an app sandbox cannot load the unsigned HTP skeleton the
 * prebuilt qnn-net-run needs).
 *
 * Pipeline, per keyframe: a full-frame pass and its mirror are averaged (flip TTA), the alpha
 * gives the object's bounding box, and a second TTA pass runs on a 1.3x-margin crop of that box
 * so the boundary is resolved at roughly 3-4x the first-pass detail. The resulting probability
 * map is guided-filtered against the frame, closed, and eroded so the mask sits on the object.
 */
object NpuMasker {
    private const val TAG = "NpuMasker"

    /** Payload version: bumping it restages the assets both in app storage and under /data/local/tmp. */
    private const val PKG_VERSION = "splatproto-masker-v1"
    private const val ASSET_ROOT = "masker"
    private const val DEVICE_PKG = "/data/local/tmp/splatproto-masker"

    data class Result(val ok: Boolean, val message: String, val masks: Int = 0)

    /** Masks every keyframe into masks/<image>.png (0 ignore, 255 keep), then the caller reconstructs. */
    fun maskScanU2Net(
        context: Context,
        scan: Scan,
        onProgress: (Float, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result = runCatching {
        require(OpenCVLoader.initLocal()) { "OpenCV failed to load" }
        val images = scanImages(scan)
        require(images.isNotEmpty()) { "No keyframes" }
        val pkg = stageAssets(context)
        ensureDevicePkg(pkg)
        val work = File(context.filesDir, "masker/u2net")
        runShell("rm -rf '" + work.absolutePath + "'")
        work.mkdirs()
        val masksDir = File(scan.dir, "masks")
        masksDir.mkdirs()
        masksDir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".png")) it.delete() }
        var written = 0
        val started = System.currentTimeMillis()
        images.forEachIndexed { i, file ->
            val frameStart = System.currentTimeMillis()
            val bgr = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
            if (bgr.empty()) return@forEachIndexed
            val mask = u2netTwoPassMask(bgr, pkg, work, "f" + i)
            if (mask == null) {
                onLog("u2net frame " + i + " failed")
                bgr.release()
                return@forEachIndexed
            }
            val out = File(masksDir, file.name + ".png")
            Imgcodecs.imwrite(out.absolutePath, mask)
            written++
            val area = Core.countNonZero(mask).toDouble() / (bgr.cols().toDouble() * bgr.rows())
            onLog("u2net frame " + i + " mask " + String.format("%.1f", area * 100) + "% " +
                (System.currentTimeMillis() - frameStart) + " ms")
            mask.release()
            bgr.release()
            onProgress(0.05f + 0.9f * (i + 1) / images.size, "Mask " + (i + 1) + "/" + images.size + " (NPU x2)")
        }
        work.listFiles()?.forEach { it.deleteRecursively() }
        val elapsed = System.currentTimeMillis() - started
        onLog("u2net masked " + written + "/" + images.size + " in " + elapsed + " ms (" +
            (if (images.isEmpty()) 0 else elapsed / images.size) + " ms/frame)")
        Result(true, "Masked " + written + "/" + images.size, written)
    }.getOrElse { error ->
        Log.e(TAG, "u2net masking failed", error)
        onLog("u2net masking failed: " + (error.message ?: error.toString()))
        Result(false, error.message ?: "u2net masking failed")
    }

    /** Runs one U2Net pass on an image and returns the 320x320 uint8 alpha. */
    private fun u2netAlpha(pkg: File, bgr: Mat, work: File, tag: String): Mat? = runCatching {
        val small = Mat()
        Imgproc.resize(bgr, small, Size(320.0, 320.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val rgb = Mat()
        Imgproc.cvtColor(small, rgb, Imgproc.COLOR_BGR2RGB)
        small.release()
        val bytes = ByteArray(320 * 320 * 3)
        rgb.get(0, 0, bytes)
        rgb.release()
        val dir = File(work, tag)
        runShell("rm -rf '" + dir.absolutePath + "'")
        dir.mkdirs()
        val inputFile = File(dir, "in.raw")
        inputFile.writeBytes(bytes)
        val list = File(dir, "list.txt")
        list.writeText(inputFile.absolutePath + "\n")
        val outDir = File(dir, "out")
        val res = qnnRun(pkg, "models/u2net.bin", list, outDir, nativeInputs = true, nativeOutputs = true)
        if (res.code != 0) return null
        val maskFile = File(outDir, "Result_0/mask_native.raw")
        if (!maskFile.isFile) return null
        val raw = maskFile.readBytes()
        if (raw.size < 320 * 320) return null
        val alpha = Mat(320, 320, CvType.CV_8UC1)
        alpha.put(0, 0, raw.copyOfRange(0, 320 * 320))
        alpha
    }.getOrNull()

    /**
     * Flip test-time augmentation for one U2Net pass: the image is segmented normally and once
     * mirrored, the two probability maps are averaged and mirrored back.
     */
    private fun u2netAlphaTTA(pkg: File, bgr: Mat, work: File, tag: String): Mat? = runCatching {
        val plain = u2netAlpha(pkg, bgr, work, tag + "_a")
        val flippedInput = Mat()
        Core.flip(bgr, flippedInput, 1)
        val mirrored = u2netAlpha(pkg, flippedInput, work, tag + "_b")
        flippedInput.release()
        if (plain == null || mirrored == null) {
            plain?.release()
            mirrored?.release()
            return null
        }
        val mirroredBack = Mat()
        Core.flip(mirrored, mirroredBack, 1)
        mirrored.release()
        val f1 = Mat()
        plain.convertTo(f1, CvType.CV_32FC1, 1.0 / 255.0)
        val f2 = Mat()
        mirroredBack.convertTo(f2, CvType.CV_32FC1, 1.0 / 255.0)
        val average = Mat()
        Core.addWeighted(f1, 0.5, f2, 0.5, 0.0, average)
        val out = Mat()
        average.convertTo(out, CvType.CV_8UC1, 255.0)
        plain.release()
        mirroredBack.release()
        f1.release()
        f2.release()
        average.release()
        out
    }.getOrNull()

    /**
     * Two-pass U2Net mask: first pass finds the object, the second pass re-runs the model on a
     * zoomed crop of the object so the boundary is resolved at roughly 3-4x the first-pass detail.
     */
    private fun u2netTwoPassMask(bgr: Mat, pkg: File, work: File, tag: String): Mat? = runCatching {
        val first = u2netAlphaTTA(pkg, bgr, work, tag + "_p1") ?: return null
        val width = bgr.cols()
        val height = bgr.rows()
        val thresholded = Mat()
        Imgproc.threshold(first, thresholded, 153.0, 255.0, Imgproc.THRESH_BINARY)
        var minX = 320
        var minY = 320
        var maxX = -1
        var maxY = -1
        for (y in 0 until 320) for (x in 0 until 320) if (thresholded.get(y, x)[0] > 0.0) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        thresholded.release()
        if (maxX < 0) {
            first.release()
            return null
        }
        val sx = width.toDouble() / 320.0
        val sy = height.toDouble() / 320.0
        val marginX = (((maxX - minX + 1) * sx) * 0.3).toInt().coerceAtLeast(12)
        val marginY = (((maxY - minY + 1) * sy) * 0.3).toInt().coerceAtLeast(12)
        var x0 = (minX * sx).toInt() - marginX
        var y0 = (minY * sy).toInt() - marginY
        var x1 = (maxX * sx).toInt() + marginX
        var y1 = (maxY * sy).toInt() + marginY
        x0 = x0.coerceIn(0, maxOf(0, width - 64))
        y0 = y0.coerceIn(0, maxOf(0, height - 64))
        x1 = x1.coerceIn(minOf(width, x0 + 64), width)
        y1 = y1.coerceIn(minOf(height, y0 + 64), height)
        val cropWidth = x1 - x0
        val cropHeight = y1 - y0
        val crop = Mat(bgr, Rect(x0, y0, cropWidth, cropHeight))
        val second = u2netAlphaTTA(pkg, crop, work, tag + "_p2")
        crop.release()
        val maskFull = Mat(height, width, CvType.CV_8UC1, Scalar(0.0))
        if (second != null) {
            val bin = Mat()
            Imgproc.threshold(second, bin, 153.0, 255.0, Imgproc.THRESH_BINARY)
            second.release()
            val big = Mat()
            Imgproc.resize(bin, big, Size(cropWidth.toDouble(), cropHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            bin.release()
            val roi = maskFull.submat(Rect(x0, y0, cropWidth, cropHeight))
            big.copyTo(roi)
            roi.release()
            big.release()
        } else {
            val bin = Mat()
            Imgproc.threshold(first, bin, 153.0, 255.0, Imgproc.THRESH_BINARY)
            val big = Mat()
            Imgproc.resize(bin, big, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            bin.release()
            big.copyTo(maskFull)
            big.release()
        }
        first.release()
        val prob = Mat()
        maskFull.convertTo(prob, CvType.CV_32FC1, 1.0 / 255.0)
        maskFull.release()
        val refined = guidedRefine(bgr, prob, 4)
        prob.release()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(refined, refined, Imgproc.MORPH_CLOSE, kernel)
        val erodeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        val eroded = Mat()
        Imgproc.erode(refined, eroded, erodeKernel)
        refined.release()
        kernel.release()
        erodeKernel.release()
        eroded
    }.getOrNull()

    /** Runs two-pass U2Net on the first keyframe and returns the seed mask and its area. */
    fun u2netSegmentFirstFrame(context: Context, scan: Scan, onLog: (String) -> Unit): Pair<File, Float>? = runCatching {
        require(OpenCVLoader.initLocal()) { "OpenCV failed to load" }
        val images = scanImages(scan)
        require(images.isNotEmpty()) { "No keyframes" }
        val pkg = stageAssets(context)
        ensureDevicePkg(pkg)
        val file = images[0]
        val bgr = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
        if (bgr.empty()) return null
        val width = bgr.cols()
        val height = bgr.rows()
        val work = File(context.filesDir, "masker/u2net_auto")
        runShell("rm -rf '" + work.absolutePath + "'")
        work.mkdirs()
        val mask = u2netTwoPassMask(bgr, pkg, work, "seed") ?: return null
        val seedDir = File(context.filesDir, "masker").apply { mkdirs() }
        val seed = File(seedDir, "u2net_seed.png")
        Imgcodecs.imwrite(seed.absolutePath, mask)
        val area = Core.countNonZero(mask).toDouble() / (width * height)
        onLog("u2net seed area " + String.format("%.1f", area * 100) + "%")
        mask.release()
        bgr.release()
        work.listFiles()?.forEach { it.deleteRecursively() }
        seed to area.toFloat()
    }.getOrElse { error ->
        Log.e(TAG, "u2net seed failed", error)
        onLog("u2net seed failed: " + (error.message ?: error.toString()))
        null
    }

    /** Guided filter of the low-resolution probability map against the frame's grey levels. */
    private fun guidedRefine(bgr: Mat, prob: Mat, radius: Int = 8): Mat {
        val eps = 1e-3
        val ksize = Size((2 * radius + 1).toDouble(), (2 * radius + 1).toDouble())
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val guide = Mat()
        gray.convertTo(guide, CvType.CV_32FC1, 1.0 / 255.0)
        val meanI = Mat(); val meanP = Mat(); val meanII = Mat(); val meanIP = Mat()
        Imgproc.boxFilter(guide, meanI, CvType.CV_32FC1, ksize)
        Imgproc.boxFilter(prob, meanP, CvType.CV_32FC1, ksize)
        val sq = Mat(); Core.multiply(guide, guide, sq)
        Imgproc.boxFilter(sq, meanII, CvType.CV_32FC1, ksize)
        val gp = Mat(); Core.multiply(guide, prob, gp)
        Imgproc.boxFilter(gp, meanIP, CvType.CV_32FC1, ksize)
        val varI = Mat(); val mi2 = Mat(); Core.multiply(meanI, meanI, mi2); Core.subtract(meanII, mi2, varI)
        val cov = Mat(); val mip = Mat(); Core.multiply(meanI, meanP, mip); Core.subtract(meanIP, mip, cov)
        val denom = Mat(); Core.add(varI, Scalar(eps), denom)
        val a = Mat(); Core.divide(cov, denom, a)
        val b = Mat(); val am = Mat(); Core.multiply(a, meanI, am); Core.subtract(meanP, am, b)
        val meanA = Mat(); val meanB = Mat()
        Imgproc.boxFilter(a, meanA, CvType.CV_32FC1, ksize)
        Imgproc.boxFilter(b, meanB, CvType.CV_32FC1, ksize)
        val q = Mat(); val ag = Mat(); Core.multiply(meanA, guide, ag); Core.add(ag, meanB, q)
        val bin = Mat()
        Imgproc.threshold(q, bin, 0.5, 1.0, Imgproc.THRESH_BINARY)
        val out = Mat()
        bin.convertTo(out, CvType.CV_8UC1, 255.0)
        gray.release(); guide.release(); meanI.release(); meanP.release(); meanII.release(); meanIP.release()
        sq.release(); gp.release(); varI.release(); mi2.release(); cov.release(); mip.release(); denom.release()
        a.release(); b.release(); am.release(); meanA.release(); meanB.release(); q.release(); ag.release()
        bin.release()
        return out
    }

    // ---------------------------------------------------------------- NPU plumbing

    private fun qnnRun(
        pkg: File,
        context: String,
        inputList: File,
        outputDir: File,
        nativeInputs: Boolean = false,
        nativeOutputs: Boolean = false,
    ): ShellResult {
        outputDir.mkdirs()
        val native = (if (nativeInputs) " --use_native_input_files" else "") +
            (if (nativeOutputs) " --use_native_output_files" else "")
        val cmd = "cd '" + DEVICE_PKG + "' && " +
            "LD_LIBRARY_PATH='" + DEVICE_PKG + "' ADSP_LIBRARY_PATH='" + DEVICE_PKG + "/skel' " +
            "./bin/qnn-net-run --backend libQnnHtp.so --retrieve_context " + context + " " +
            "--input_list '" + inputList.absolutePath + "' --output_dir '" + outputDir.absolutePath + "' " +
            "--config_file models/htp_ext.json --log_level error" + native
        return runShell(cmd)
    }

    private fun stageAssets(context: Context): File {
        val root = File(context.filesDir, "masker")
        val pkg = File(root, "pkg")
        val marker = File(root, "staged")
        if (!marker.isFile || marker.readText() != PKG_VERSION) {
            pkg.deleteRecursively()
            pkg.mkdirs()
            copyAsset(context, ASSET_ROOT, pkg)
            marker.writeText(PKG_VERSION)
        }
        return pkg
    }

    private fun copyAsset(context: Context, path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 1 shl 16) }
            }
            target.setExecutable(true, false)
        } else {
            target.mkdirs()
            for (child in children) {
                // The app ships the payload's licence set and manifest beside the object code;
                // the on-device staging copy needs only what qnn-net-run actually executes.
                if (child == "LICENSES" || child == "SOURCE.md" || child == "SHA256SUMS") continue
                copyAsset(context, path + "/" + child, File(target, child))
            }
        }
    }

    private fun ensureDevicePkg(pkg: File) {
        val marker = DEVICE_PKG + "/.version"
        val current = runShell("cat '" + marker + "' 2>/dev/null").out.trim()
        if (current == PKG_VERSION) return
        val staged = runShell(
            "rm -rf '" + DEVICE_PKG + "' && mkdir -p /data/local/tmp && " +
                "cp -r '" + pkg.absolutePath + "' '" + DEVICE_PKG + "' && " +
                "chmod -R 755 '" + DEVICE_PKG + "' && " +
                "echo -n '" + PKG_VERSION + "' > '" + DEVICE_PKG + "/.version'",
        )
        require(staged.code == 0) { "Could not stage NPU payload: " + staged.out.takeLast(300) }
    }

    private data class ShellResult(val code: Int, val out: String)

    private fun runShell(command: String): ShellResult = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        ShellResult(process.waitFor(), out)
    }.getOrElse { ShellResult(-1, "shell failed: " + it.message) }

    private fun scanImages(scan: Scan): List<File> {
        val files = ArrayList<File>()
        runCatching {
            val arr = JSONObject(scan.meta.readText()).optJSONArray("images") ?: return@runCatching
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("file").orEmpty()
                if (name.isNotEmpty()) {
                    val f = File(scan.imagesDir, name)
                    if (f.isFile) files.add(f)
                }
            }
        }
        if (files.isEmpty()) {
            scan.imagesDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg", true) }
                ?.sortedBy { it.name }?.let { files.addAll(it) }
        }
        return files
    }
}
