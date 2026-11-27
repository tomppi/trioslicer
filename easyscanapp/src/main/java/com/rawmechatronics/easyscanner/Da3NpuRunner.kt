package com.rawmechatronics.easyscanner

import android.content.Context
import android.media.Image
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Single-frame Depth-Anything-3 inference on the Qualcomm Hexagon HTP.
 *
 * Shares the exact staging layout (`/data/local/tmp/splatproto-masker` +
 * `filesDir/masker/pkg`) with `NpuMasker` so the QNN runtime and models are
 * staged once and reused by both EasyScanner's live mesh and splatproto.
 */
object Da3NpuRunner {
    private const val TAG = "Da3NpuRunner"
    private const val SIZE = AdaptiveDepthMesh.SIZE
    private const val PKG_VERSION = "splatproto-masker-v3"
    private const val ASSET_ROOT = "masker"
    private const val DEVICE_PKG = "/data/local/tmp/splatproto-masker"
    private const val DA3_CONTEXT = "models/da3_depth_fp16.bin"

    private val stageLock = Any()

    /**
     * True persistent QNN HTP session backed by `bin/da3-worker` (compiled against QAIRT
     * 2.52.0 headers). Loads `models/da3_depth_fp16.bin` ONCE via `contextCreateFromBinary`
     * in HTP BURST mode and executes every subsequent patch directly via `graphExecute`
     * without reloading the 75 MB context binary. Falls back transparently to `qnn-net-run`
     * if `da3-worker` cannot start.
     */
    class PersistentSession(private val context: Context) {
        private val lock = Any()
        private var proc: Process? = null
        private var stdin: java.io.BufferedWriter? = null
        private var stdout: java.io.BufferedReader? = null
        private var workerReady = false
        private var workDir: File? = null
        private var inFile: File? = null
        private var outFile: File? = null

        fun warmUp() = synchronized(lock) {
            stageNpu(context)
            ensureWorkerLocked()
        }

        fun infer(rgb518: ByteArray): FloatArray = synchronized(lock) {
            require(rgb518.size >= SIZE * SIZE * 3) { "rgb518 too small" }
            stageNpu(context)
            if (!ensureWorkerLocked()) {
                Log.w(TAG, "da3-worker unavailable; falling back to qnn-net-run")
                return@synchronized runSingleFrame(context, rgb518)
            }
            val wIn = inFile!!
            val wOut = outFile!!
            val buffer = ByteBuffer.allocate(SIZE * SIZE * 3 * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (c in 0 until 3) {
                for (p in 0 until SIZE * SIZE) {
                    buffer.putShort(halfLookup[rgb518[p * 3 + c].toInt() and 0xff])
                }
            }
            wIn.writeBytes(buffer.array())
            val ok = inferInWorkerLocked(wIn, wOut)
            if (!ok || !wOut.isFile || wOut.length() < SIZE * SIZE * 2L) {
                Log.w(TAG, "da3-worker infer failed; restarting and falling back once")
                closeWorkerLocked()
                return@synchronized runSingleFrame(context, rgb518)
            }
            val bytes = wOut.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val depth = FloatArray(SIZE * SIZE)
            for (i in depth.indices) depth[i] = halfToFloat(bb.short.toInt() and 0xffff)
            depth
        }

        fun close() = synchronized(lock) {
            closeWorkerLocked()
            workDir?.deleteRecursively()
            workDir = null
        }

        private fun ensureWorkerLocked(): Boolean {
            if (workDir == null) {
                val w = File(context.filesDir, "da3live_session").apply { deleteRecursively(); mkdirs() }
                workDir = w
                inFile = File(w, "in.raw")
                outFile = File(w, "out.raw")
            }
            if (workerReady && proc?.isAlive == true) return true
            closeWorkerLocked()
            return runCatching {
                val cmd = "cd '" + DEVICE_PKG + "' && " +
                    "LD_LIBRARY_PATH='" + DEVICE_PKG + "' ADSP_LIBRARY_PATH='" + DEVICE_PKG + "/skel' " +
                    "exec ./bin/da3-worker '" + DA3_CONTEXT + "'"
                val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                val w = p.outputStream.bufferedWriter()
                val r = p.inputStream.bufferedReader()
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("__DA3_READY__")) {
                        Log.i(TAG, "persistent QNN session ready: " + line)
                        proc = p; stdin = w; stdout = r; workerReady = true
                        return@runCatching true
                    }
                    Log.d(TAG, "da3-worker init: " + line)
                }
                p.destroy()
                false
            }.getOrElse {
                Log.w(TAG, "da3-worker launch failed", it)
                false
            }
        }

        private fun inferInWorkerLocked(inRaw: File, outRaw: File): Boolean = runCatching {
            val w = stdin ?: return false
            val r = stdout ?: return false
            w.write("INFER " + inRaw.absolutePath + " " + outRaw.absolutePath + "\n")
            w.flush()
            while (true) {
                val line = r.readLine() ?: return false
                if (line.startsWith("__DA3_OK__")) {
                    Log.i(TAG, "da3-worker graphExecute " + line.substringAfter("__DA3_OK__").trim() + " ms")
                    return true
                }
                if (line.startsWith("__DA3_ERR__")) {
                    Log.w(TAG, "da3-worker error: " + line)
                    return false
                }
            }
            false
        }.getOrDefault(false)

        private fun closeWorkerLocked() {
            workerReady = false
            runCatching { stdin?.write("QUIT\n"); stdin?.flush() }
            runCatching { stdin?.close() }
            runCatching { stdout?.close() }
            runCatching { proc?.destroy() }
            proc = null; stdin = null; stdout = null
        }
    }

    /** Extracts a 518x518 packed RGB uint8 buffer directly from a Camera2 YUV_420_888 [Image]. */
    fun extractRgb518(img: Image): ByteArray {
        val w = img.width
        val h = img.height
        val planes = img.planes
        val yBuf = planes[0].buffer.duplicate().apply { rewind() }
        val uBuf = planes[1].buffer.duplicate().apply { rewind() }
        val vBuf = planes[2].buffer.duplicate().apply { rewind() }
        val yRowStride = planes[0].rowStride
        val yPixStride = planes[0].pixelStride
        val uvRowStride = planes[1].rowStride
        val uvPixStride = planes[1].pixelStride
        val yBytes = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
        val uBytes = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
        val vBytes = ByteArray(vBuf.remaining()).also { vBuf.get(it) }
        val out = ByteArray(SIZE * SIZE * 3)
        val yMax = yBytes.size - 1
        val uMax = uBytes.size - 1
        val vMax = vBytes.size - 1
        for (dy in 0 until SIZE) {
            val sy = ((dy + 0.5) * h / SIZE).toInt().coerceIn(0, h - 1)
            val yRow = sy * yRowStride
            val uvRow = (sy shr 1) * uvRowStride
            val outRow = dy * SIZE * 3
            for (dx in 0 until SIZE) {
                val sx = ((dx + 0.5) * w / SIZE).toInt().coerceIn(0, w - 1)
                val yi = (yRow + sx * yPixStride).coerceIn(0, yMax)
                val uvi = (uvRow + (sx shr 1) * uvPixStride).coerceIn(0, minOf(uMax, vMax))
                val yv = (yBytes[yi].toInt() and 0xff)
                val uv = (uBytes[uvi].toInt() and 0xff) - 128
                val vv = (vBytes[uvi].toInt() and 0xff) - 128
                val r = (yv + ((1436 * vv) shr 10)).coerceIn(0, 255)
                val g = (yv - ((352 * uv + 731 * vv) shr 10)).coerceIn(0, 255)
                val b = (yv + ((1815 * uv) shr 10)).coerceIn(0, 255)
                val o = outRow + dx * 3
                out[o] = r.toByte()
                out[o + 1] = g.toByte()
                out[o + 2] = b.toByte()
            }
        }
        return out
    }

    /** Runs Depth-Anything-3 on one 518x518 RGB frame and returns the 518x518 float32 depth map. */
    fun runSingleFrame(context: Context, rgb518: ByteArray): FloatArray {
        require(rgb518.size >= SIZE * SIZE * 3) { "rgb518 too small" }
        stageNpu(context)
        val work = File(context.filesDir, "da3live")
        work.deleteRecursively()
        work.mkdirs()
        val inFile = File(work, "f0.raw")
        val outDir = File(work, "out").apply { mkdirs() }
        val listFile = File(work, "list.txt")
        val buffer = ByteBuffer.allocate(SIZE * SIZE * 3 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (c in 0 until 3) {
            for (p in 0 until SIZE * SIZE) {
                buffer.putShort(halfLookup[rgb518[p * 3 + c].toInt() and 0xff])
            }
        }
        inFile.writeBytes(buffer.array())
        listFile.writeText(inFile.absolutePath + "\n")

        val cmd = "cd '" + DEVICE_PKG + "' && " +
            "LD_LIBRARY_PATH='" + DEVICE_PKG + "' ADSP_LIBRARY_PATH='" + DEVICE_PKG + "/skel' " +
            "./bin/qnn-net-run --backend libQnnHtp.so --retrieve_context " + DA3_CONTEXT + " " +
            "--input_list '" + listFile.absolutePath + "' --output_dir '" + outDir.absolutePath + "' " +
            "--config_file models/htp_ext.json --log_level error " +
            "--use_native_input_files --use_native_output_files"
        val res = runShell(cmd)
        if (res.code != 0) {
            error("DA3 NPU failed (code " + res.code + "): " + res.out.takeLast(240))
        }
        val rawDepth = File(outDir, "Result_0/depth_estimates_native.raw")
        if (!rawDepth.isFile || rawDepth.length() < SIZE * SIZE * 2L) {
            error("DA3 NPU produced no depth output")
        }
        val bytes = rawDepth.readBytes()
        work.deleteRecursively()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val depth = FloatArray(SIZE * SIZE)
        for (i in depth.indices) {
            depth[i] = halfToFloat(bb.short.toInt() and 0xffff)
        }
        return depth
    }

    fun stageNpu(context: Context) = synchronized(stageLock) {
        val root = File(context.filesDir, "masker")
        val pkg = File(root, "pkg")
        val marker = File(root, "staged")
        if (!marker.isFile || marker.readText() != PKG_VERSION) {
            pkg.deleteRecursively()
            pkg.mkdirs()
            copyAsset(context, ASSET_ROOT, pkg)
            marker.writeText(PKG_VERSION)
        }
        val devMarker = DEVICE_PKG + "/.version"
        val current = runShell("cat '" + devMarker + "' 2>/dev/null").out.trim()
        if (current != PKG_VERSION) {
            val staged = runShell(
                "rm -rf '" + DEVICE_PKG + "' && mkdir -p /data/local/tmp && " +
                    "cp -r '" + pkg.absolutePath + "' '" + DEVICE_PKG + "' && " +
                    "chmod -R 755 '" + DEVICE_PKG + "' && " +
                    "echo -n '" + PKG_VERSION + "' > '" + devMarker + "'",
            )
            require(staged.code == 0) { "Could not stage NPU payload: " + staged.out.takeLast(240) }
        }
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
                if (child == "LICENSES" || child == "SOURCE.md" || child == "SHA256SUMS") continue
                copyAsset(context, path + "/" + child, File(target, child))
            }
        }
    }

    private data class ShellResult(val code: Int, val out: String)

    private fun runShell(command: String): ShellResult = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        ShellResult(process.waitFor(), out)
    }.getOrElse {
        Log.e(TAG, "shell failed", it)
        ShellResult(-1, "shell failed: " + it.message)
    }

    private val halfLookup: ShortArray = ShortArray(256) { floatToHalf(it / 255f) }

    private fun floatToHalf(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exponent = (bits ushr 23) and 0xff
        var mantissa = bits and 0x7fffff
        if (exponent == 0xff) return (sign or 0x7c00 or (if (mantissa != 0) 0x200 else 0)).toShort()
        var e = exponent - 127 + 15
        if (e >= 0x1f) return (sign or 0x7c00).toShort()
        if (e <= 0) {
            if (e < -10) return sign.toShort()
            mantissa = (mantissa or 0x800000) ushr (1 - e)
            return (sign or (mantissa ushr 13)).toShort()
        }
        mantissa = (mantissa + 0x1000) ushr 13
        return (sign or (e shl 10) or mantissa).toShort()
    }

    private fun halfToFloat(bits: Int): Float {
        val sign = if (bits and 0x8000 != 0) -1f else 1f
        val exponent = (bits ushr 10) and 0x1f
        val mantissa = bits and 0x3ff
        return sign * when {
            exponent == 0 -> mantissa.toFloat() * 5.9604645e-8f
            exponent == 0x1f -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1f + mantissa.toFloat() / 1024f) * Math.pow(2.0, (exponent - 15).toDouble()).toFloat()
        }
    }
}
