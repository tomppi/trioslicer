package com.rawmechatronics.easyscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the SuperPoint HTP context inside the app. The QAIRT runtime, HTP
 * skel/stub libraries and the context binary ship in jniLibs/assets;
 * qnn-net-run is executed from the app's native library directory, first
 * directly and then through su because the vendor DSP namespace refuses
 * untrusted_app on this device.
 */
object SuperPointRunner {
    private const val TAG = "SuperPoint"
    private const val W = 640
    private const val H = 480

    private val HTP_CONFIG = """{"devices":[{"dsp_arch":"v73","cores":[{"perf_profile":"burst","rpc_control_latency":100}]}]}"""
    private val SU_EXT = """{"backend_extensions":{"shared_library_path":"@LIB@","config_file_path":"@CFG@"}}"""
    private const val STAGE = "/data/local/tmp/easyscanner_sp"

    fun runOnJpeg(ctx: Context, jpeg: File): String {
        val dir = File(ctx.filesDir, "sp").apply { mkdirs() }
        copyAsset(ctx, "sp_context.bin", File(dir, "context.bin"))

        val src = BitmapFactory.decodeFile(jpeg.absolutePath) ?: return "decode failed"
        val bmp = Bitmap.createScaledBitmap(src, W, H, true)
        val pixels = IntArray(W * H)
        bmp.getPixels(pixels, 0, W, 0, 0, W, H)
        val raw = ByteBuffer.allocateDirect(W * H * 3 * 2).order(ByteOrder.nativeOrder())
        for (c in 0..2) {
            for (i in 0 until W * H) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> (p shr 16) and 0xff
                    1 -> (p shr 8) and 0xff
                    else -> p and 0xff
                }
                raw.putShort(half(v / 255f))
            }
        }
        raw.rewind()
        val input = File(dir, "input.raw")
        FileOutputStream(input).use { out ->
            val bytes = ByteArray(raw.remaining())
            raw.get(bytes)
            out.write(bytes)
        }
        val list = File(dir, "input_list.txt").apply { writeText(input.absolutePath + System.lineSeparator()) }
        val outDir = File(dir, "out").apply { deleteRecursively(); mkdirs() }

        val native = File(ctx.applicationInfo.nativeLibraryDir)
        val bin = File(native, "libqnnnetrun.so")
        if (!bin.exists()) return "runtime missing: " + bin
        val htpCfg = File(dir, "htp_config.json").apply { writeText(HTP_CONFIG) }
        val ext = File(dir, "htp_ext.json").apply {
            writeText(
                """{"backend_extensions":{"shared_library_path":"""" + native.absolutePath +
                    "/libQnnHtpNetRunExtensions.so" + """","config_file_path":"""" + htpCfg.absolutePath + """"}}"""
            )
        }

        val cmd = listOf(
            bin.absolutePath,
            "--backend", File(native, "libQnnHtp.so").absolutePath,
            "--retrieve_context", File(dir, "context.bin").absolutePath,
            "--input_list", list.absolutePath,
            "--output_dir", outDir.absolutePath,
            "--config_file", ext.absolutePath,
            "--use_native_input_files", "--use_native_output_files",
            "--profiling_level", "basic",
        )
        val t0 = System.currentTimeMillis()
        var mode = "direct"
        var output = ""
        var rc = -1
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = native.absolutePath
            pb.environment()["ADSP_LIBRARY_PATH"] = native.absolutePath
            val proc = pb.start()
            output = proc.inputStream.bufferedReader().readText()
            rc = proc.waitFor()
        } catch (ex: Exception) {
            output = ex.message ?: "exec failed"
        }
        if (rc != 0) {
            // The vendor DSP namespace refuses untrusted_app: stage the same
            // runtime under /data/local/tmp with su, where the proven setup ran.
            mode = "su"
            File(dir, "htp_config.json").writeText(HTP_CONFIG)
            File(dir, "htp_ext.json").writeText(
                SU_EXT.replace("@LIB@", STAGE + "/libQnnHtpNetRunExtensions.so")
                    .replace("@CFG@", STAGE + "/htp_config.json")
            )
            val d = STAGE
            val script = "D=" + d + "; mkdir -p \$D/skel; " +
                "cp " + native.absolutePath + "/libQnnHtp.so " + native.absolutePath +
                "/libQnnHtpNetRunExtensions.so " + native.absolutePath +
                "/libQnnHtpV73Stub.so " + native.absolutePath + "/libQnnSystem.so \$D/; " +
                "cp " + native.absolutePath + "/libQnnHtpV73Skel.so \$D/skel/; " +
                "cp " + native.absolutePath + "/libqnnnetrun.so \$D/qnn-net-run; " +
                "cp " + File(dir, "context.bin").absolutePath + " \$D/context.bin; " +
                "cp " + File(dir, "htp_config.json").absolutePath + " \$D/htp_config.json; " +
                "cp " + File(dir, "htp_ext.json").absolutePath + " \$D/htp_ext.json; " +
                "rm -rf \$D/out; mkdir -p \$D/out; " +
                "chmod 755 \$D/qnn-net-run; " +
                "export LD_LIBRARY_PATH=\$D; export ADSP_LIBRARY_PATH=\$D/skel; " +
                "cd " + dir.absolutePath + "; " +
                "\$D/qnn-net-run --backend \$D/libQnnHtp.so --retrieve_context \$D/context.bin " +
                "--input_list " + list.absolutePath + " --output_dir \$D/out " +
                "--config_file \$D/htp_ext.json --use_native_input_files --use_native_output_files; rc=\$?; " +
                "rm -rf " + outDir.absolutePath + "; mkdir -p " + outDir.absolutePath + "; " +
                "cp -r \$D/out/. " + outDir.absolutePath + "/ 2>/dev/null; " +
                "chmod -R a+rX " + outDir.absolutePath + " 2>/dev/null; exit \$rc"
            try {
                val p2 = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
                output += p2.inputStream.bufferedReader().readText()
                rc = p2.waitFor()
            } catch (ex: Exception) {
                output += " su: " + (ex.message ?: "failed")
            }
        }
        val ms = System.currentTimeMillis() - t0
        val scores = find(outDir, "scores")
        val desc = find(outDir, "descriptors")
        val kp = if (scores != null) countKeypoints(scores) else -1
        val dsize = desc?.length() ?: 0L
        return "mode=" + mode + " rc=" + rc + " kp=" + kp + " desc_bytes=" + dsize +
            " ms=" + ms + " tail=" + output.takeLast(160).replace(System.lineSeparator(), " | ")
    }

    private fun copyAsset(ctx: Context, name: String, out: File) {
        if (out.exists() && out.length() > 1000) return
        try {
            ctx.assets.open(name).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        } catch (ex: Exception) {
            Log.e(TAG, "asset " + name + " failed", ex)
        }
    }

    private fun find(dir: File, needle: String): File? {
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isDirectory) {
                find(f, needle)?.let { return it }
            } else if (f.name.contains(needle) && f.name.endsWith(".raw")) {
                return f
            }
        }
        return null
    }

    private fun readHalf(file: File): FloatArray {
        val bytes = file.readBytes()
        val n = bytes.size / 2
        val out = FloatArray(n)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) out[i] = halfToFloat(bb.short)
        return out
    }

    private fun countKeypoints(file: File): Int {
        val scores = readHalf(file)
        if (scores.size < W * H) return -1
        var count = 0
        for (y in 4 until H - 4) {
            for (x in 4 until W - 4) {
                val v = scores[y * W + x]
                if (v <= 0.001f) continue
                var isMax = true
                var dy = -4
                while (dy <= 4 && isMax) {
                    var dx = -4
                    while (dx <= 4 && isMax) {
                        if ((dx != 0 || dy != 0) && scores[(y + dy) * W + (x + dx)] > v) isMax = false
                        dx++
                    }
                    dy++
                }
                if (isMax) {
                    count++
                    if (count >= 1000) return count
                }
            }
        }
        return count
    }

    private fun half(f: Float): Short {
        val bits = java.lang.Float.floatToIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xff
        var mant = bits and 0x7fffff
        if (exp == 0xff) return (sign or 0x7c00 or (mant ushr 13)).toShort()
        val e = exp - 127 + 15
        if (e >= 0x1f) return (sign or 0x7c00).toShort()
        if (e <= 0) {
            if (e < -10) return sign.toShort()
            mant = (mant or 0x800000) ushr (1 - e)
            return (sign or ((mant + 0x1000) ushr 13)).toShort()
        }
        return (sign or (e shl 10) or ((mant + 0x1000) ushr 13)).toShort()
    }

    private fun halfToFloat(h: Short): Float {
        val bits = h.toInt() and 0xffff
        val sign = (bits and 0x8000) shl 16
        val exp = (bits ushr 10) and 0x1f
        val mant = bits and 0x3ff
        return when {
            exp == 0 -> java.lang.Float.intBitsToFloat(sign or (mant shl 13))
            exp == 0x1f -> java.lang.Float.intBitsToFloat(sign or 0x7f800000 or (mant shl 13))
            else -> java.lang.Float.intBitsToFloat(sign or ((exp - 15 + 127) shl 23) or (mant shl 13))
        }
    }
}
