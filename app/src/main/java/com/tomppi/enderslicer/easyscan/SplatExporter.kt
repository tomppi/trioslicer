package com.tomppi.enderslicer.easyscan

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts an EasyScanner binary PLY cloud (x,y,z float + rgb uchar, 15 bytes
 * per vertex, Z-up) into the 32-byte .splat format used by web splat viewers.
 * Each point becomes an isotropic gaussian; the scale is estimated from the
 * point spacing, colours keep their RGB and the cloud is Y-up, centred.
 */
object SplatExporter {
    fun convert(ply: File, out: File): Int {
        if (!ply.exists()) return -1
        val data = ply.readBytes()
        val headerEnd = indexOf(data, "end_header\n".toByteArray())
        if (headerEnd < 0) return -1
        val header = String(data, 0, headerEnd, Charsets.US_ASCII)
        val n = header.lineSequence()
            .firstOrNull { it.startsWith("element vertex ") }
            ?.split(" ")?.last()?.toIntOrNull() ?: return -1
        val start = headerEnd + "end_header\n".length
        val stride = 15
        if (start + n * stride > data.size) return -1
        var minx = Float.MAX_VALUE; var miny = Float.MAX_VALUE; var minz = Float.MAX_VALUE
        var maxx = -Float.MAX_VALUE; var maxy = -Float.MAX_VALUE; var maxz = -Float.MAX_VALUE
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val o = start + i * stride
            val x = bb.getFloat(o); val y = bb.getFloat(o + 4); val z = bb.getFloat(o + 8)
            if (x < minx) minx = x; if (x > maxx) maxx = x
            if (y < miny) miny = y; if (y > maxy) maxy = y
            if (z < minz) minz = z; if (z > maxz) maxz = z
        }
        val cx = (minx + maxx) / 2f; val cy = (miny + maxy) / 2f; val cz = (minz + maxz) / 2f
        val vol = ((maxx - minx) * (maxy - miny) * (maxz - minz)).coerceAtLeast(1f)
        // voxel-downsample so viewers stay responsive (3 mm, 4 mm for big clouds)
        val voxel = if (n > 150000) 4.0f else 3.0f
        val scaleOut = voxel * 0.8f
        val seen = HashSet<Int>()
        val sel = ArrayList<Int>(Math.min(n, 80000))
        for (i in 0 until n) {
            val o = start + i * stride
            val kx = Math.floor((bb.getFloat(o) / voxel).toDouble()).toInt() + 512
            val ky = Math.floor((bb.getFloat(o + 4) / voxel).toDouble()).toInt() + 512
            val kz = Math.floor((bb.getFloat(o + 8) / voxel).toDouble()).toInt() + 512
            val key = (kx shl 20) or (ky shl 10) or kz
            if (seen.add(key)) sel.add(i)
        }
        val buf = ByteBuffer.allocate(sel.size * 32).order(ByteOrder.LITTLE_ENDIAN)
        for (si in 0 until sel.size) {
            val i = sel[si]
            val o = start + i * stride
            val x = bb.getFloat(o); val y = bb.getFloat(o + 4); val z = bb.getFloat(o + 8)
            val r = data[o + 12].toInt() and 0xff
            val g = data[o + 13].toInt() and 0xff
            val b = data[o + 14].toInt() and 0xff
            // AR PLYs are Z-up with the height stored negated (engine frame), so
            // flip Z to get a Y-up. Centred at the cloud midpoint.
            buf.putFloat(x - cx)
            buf.putFloat(-(z - cz))
            buf.putFloat(-(y - cy))
            buf.putFloat(scaleOut); buf.putFloat(scaleOut); buf.putFloat(scaleOut)
            buf.put(r.toByte()); buf.put(g.toByte()); buf.put(b.toByte()); buf.put(0xff.toByte())
            // identity quaternion packed as (128,128,128,255)
            buf.put(128.toByte()); buf.put(128.toByte()); buf.put(128.toByte()); buf.put(0xff.toByte())
        }
        FileOutputStream(out).use { it.write(buf.array()) }
        return sel.size
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
