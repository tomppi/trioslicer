package com.tomppi.splatproto

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Converts a binary little-endian 3DGS PLY (from [LaserInit] or a trained Cross3DGS export)
 * into Cross3DGS's `D3GSCHK1` checkpoint format.
 *
 * Exact Structure-of-Arrays (SoA) layout from `cross3dgs/libs/cross3dgs/src/gaussian_state.cpp`:
 *   magic       : char[8] = "D3GSCHK1"
 *   count       : uint64 (little-endian)
 *   means       : count * vec4(x, y, z, 0)
 *   scales_opac : count * vec4(LINEAR sx, LINEAR sy, LINEAR sz, LINEAR opacity in (0, 1))
 *   rotations   : count * vec4(qw, qx, qy, qz) (unit quaternion)
 *   sh_dc       : count * vec4(f_dc_0, f_dc_1, f_dc_2, 0)
 *   sh_rest     : count * 12 * vec4 (15 SH coefficients x 3 channels, coefficient-major, + 3 pad floats)
 *
 * Note: 3DGS PLY files store `scale_0..2` as `ln(scale)` and `opacity` as `logit(alpha)`,
 * and `f_rest_0..44` channel-major (`ch * 15 + coeff`), whereas `D3GSCHK1` stores linear
 * scales/opacity and coefficient-major `sh_rest`.
 */
object Cross3dgsCheckpoint {
    private const val MIN_LINEAR_SCALE = 1e-4f
    private const val MAX_LINEAR_SCALE = 1.0f
    private const val DEFAULT_LINEAR_SCALE = 0.002f
    private const val DEFAULT_LINEAR_OPACITY = 0.5f

    fun fromPly(ply: File, out: File): Boolean = fromPlyCount(ply, out) > 0

    /** Converts [ply] to [out] and returns the number of Gaussians written, or 0 on failure. */
    fun fromPlyCount(ply: File, out: File): Int {
        if (!ply.isFile) return 0
        val bytes = ply.readBytes()
        val endToken = "end_header\n".toByteArray(Charsets.US_ASCII)
        val headerEnd = indexOf(bytes, endToken) ?: return 0
        val header = String(bytes, 0, headerEnd, Charsets.US_ASCII)
        val lines = header.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "ply") return 0
        if (lines.none { it == "format binary_little_endian 1.0" }) return 0
        val n = lines.firstOrNull { it.startsWith("element vertex ") }
            ?.removePrefix("element vertex ")?.trim()?.toIntOrNull() ?: return 0
        if (n <= 0) return 0

        val propOffsets = HashMap<String, Int>()
        var stride = 0
        for (line in lines) {
            if (!line.startsWith("property ")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 3 || parts[1] == "list") return 0
            val size = when (parts[1]) {
                "float", "float32", "int", "int32", "uint", "uint32" -> 4
                "uchar", "uint8", "char", "int8" -> 1
                "double", "float64" -> 8
                "short", "ushort", "int16", "uint16" -> 2
                else -> return 0
            }
            propOffsets[parts[2]] = stride
            stride += size
        }

        val ox = propOffsets["x"] ?: return 0
        val oy = propOffsets["y"] ?: return 0
        val oz = propOffsets["z"] ?: return 0
        val os0 = propOffsets["scale_0"]
        val os1 = propOffsets["scale_1"]
        val os2 = propOffsets["scale_2"]
        val or0 = propOffsets["rot_0"]
        val or1 = propOffsets["rot_1"]
        val or2 = propOffsets["rot_2"]
        val or3 = propOffsets["rot_3"]
        val oOpac = propOffsets["opacity"]
        val odc0 = propOffsets["f_dc_0"]
        val odc1 = propOffsets["f_dc_1"]
        val odc2 = propOffsets["f_dc_2"]
        val restOffsets = IntArray(45) { idx -> propOffsets["f_rest_$idx"] ?: -1 }
        val hasAllRest = restOffsets.all { it >= 0 }

        val start = headerEnd + endToken.size
        if (start.toLong() + n.toLong() * stride.toLong() > bytes.size.toLong()) return 0

        val shGray = (0.6f - 0.5f) / 0.28209479f
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun linearScale(offset: Int?, base: Int): Float {
            if (offset == null) return DEFAULT_LINEAR_SCALE
            val logS = bb.getFloat(base + offset)
            if (!logS.isFinite()) return DEFAULT_LINEAR_SCALE
            return exp(logS.coerceIn(-10f, 2f)).coerceIn(MIN_LINEAR_SCALE, MAX_LINEAR_SCALE)
        }

        fun linearOpacity(base: Int): Float {
            if (oOpac == null) return DEFAULT_LINEAR_OPACITY
            val logit = bb.getFloat(base + oOpac)
            if (!logit.isFinite()) return DEFAULT_LINEAR_OPACITY
            val alpha = 1.0f / (1.0f + exp(-logit.coerceIn(-12f, 12f)))
            return alpha.coerceIn(0.05f, 0.99f)
        }

        out.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(out), 65536).use { os ->
            os.write("D3GSCHK1".toByteArray(Charsets.US_ASCII))
            os.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n.toLong()).array())

            val v4 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            fun writeVec4(a: Float, b: Float, c: Float, d: Float) {
                v4.clear()
                v4.putFloat(a); v4.putFloat(b); v4.putFloat(c); v4.putFloat(d)
                os.write(v4.array())
            }

            // 1) means (n * vec4)
            for (i in 0 until n) {
                val o = start + i * stride
                writeVec4(bb.getFloat(o + ox), bb.getFloat(o + oy), bb.getFloat(o + oz), 0f)
            }
            // 2) scales_opacity (n * vec4, LINEAR scale + LINEAR opacity)
            for (i in 0 until n) {
                val o = start + i * stride
                writeVec4(
                    linearScale(os0, o),
                    linearScale(os1 ?: os0, o),
                    linearScale(os2 ?: os0, o),
                    linearOpacity(o),
                )
            }
            // 3) rotations (n * vec4, unit quaternion w,x,y,z)
            for (i in 0 until n) {
                val o = start + i * stride
                if (or0 != null && or1 != null && or2 != null && or3 != null) {
                    val rw = bb.getFloat(o + or0)
                    val rx = bb.getFloat(o + or1)
                    val ry = bb.getFloat(o + or2)
                    val rz = bb.getFloat(o + or3)
                    val norm = sqrt((rw * rw + rx * rx + ry * ry + rz * rz).toDouble()).toFloat()
                    if (norm > 1e-6f && norm.isFinite()) {
                        writeVec4(rw / norm, rx / norm, ry / norm, rz / norm)
                    } else {
                        writeVec4(1f, 0f, 0f, 0f)
                    }
                } else {
                    writeVec4(1f, 0f, 0f, 0f)
                }
            }
            // 4) sh_dc (n * vec4)
            for (i in 0 until n) {
                val o = start + i * stride
                if (odc0 != null && odc1 != null && odc2 != null) {
                    writeVec4(bb.getFloat(o + odc0), bb.getFloat(o + odc1), bb.getFloat(o + odc2), 0f)
                } else {
                    writeVec4(shGray, shGray, shGray, 0f)
                }
            }
            // 5) sh_rest (n * 12 * vec4 = n * 48 floats, coefficient-major)
            val restBuf = ByteBuffer.allocate(48 * 4).order(ByteOrder.LITTLE_ENDIAN)
            val zeroRest = ByteArray(48 * 4)
            for (i in 0 until n) {
                if (hasAllRest) {
                    val o = start + i * stride
                    restBuf.clear()
                    // PLY stores channel-major f_rest[ch * 15 + coeff]; Cross3DGS stores coeff-major [coeff * 3 + ch].
                    for (coeff in 0 until 15) {
                        for (ch in 0 until 3) {
                            val v = bb.getFloat(o + restOffsets[ch * 15 + coeff])
                            restBuf.putFloat(if (v.isFinite()) v else 0f)
                        }
                    }
                    restBuf.putFloat(0f); restBuf.putFloat(0f); restBuf.putFloat(0f)
                    os.write(restBuf.array())
                } else {
                    os.write(zeroRest)
                }
            }
        }
        return n
    }

    /** Reads the Gaussian count from an existing `D3GSCHK1` checkpoint file, or 0 if invalid. */
    fun readCount(checkpoint: File): Int {
        if (!checkpoint.isFile || checkpoint.length() < 16L) return 0
        return runCatching {
            val head = ByteArray(16)
            checkpoint.inputStream().use { if (it.read(head) != 16) return 0 }
            if (String(head, 0, 8, Charsets.US_ASCII) != "D3GSCHK1") return 0
            val count = ByteBuffer.wrap(head, 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (count in 1..Int.MAX_VALUE) count.toInt() else 0
        }.getOrDefault(0)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int? {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }
}
