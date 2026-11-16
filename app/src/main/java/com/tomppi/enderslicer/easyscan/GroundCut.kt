package com.tomppi.enderslicer.easyscan

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The paper-tracker app knows where the ground is (the board plane) and never
 * stores points on it. The AR app has no such plane during a scan, so the beam
 * also hits the table around the object and those points dominate the cloud.
 * This finds the dense ground band in the exported Z-up PLY (the AR export
 * stores height negated) and drops everything below it plus a clearance.
 */
object GroundCut {
    private const val TAG = "GroundCut"

    /** Returns the number of vertices removed (negative when the file was left alone). */
    fun cut(ply: File, clearanceMm: Float = 10f): Int {
        val data = ply.readBytes()
        val marker = "end_header
".toByteArray()
        val end = indexOf(data, marker)
        if (end < 0) return -1
        val headerEnd = end + marker.size
        val header = String(data, 0, headerEnd, Charsets.US_ASCII)
        val n = header.lineSequence()
            .firstOrNull { it.startsWith("element vertex ") }
            ?.split(" ")?.last()?.toIntOrNull() ?: return -1
        val stride = 15
        if (headerEnd + n * stride > data.size) return -1
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        // AR PLY: height = -z, 1 mm bins from -20 to 60 mm
        val bins = IntArray(80)
        val lo = -20f
        for (i in 0 until n) {
            val h = -bb.getFloat(headerEnd + i * stride + 8)
            val b = (h - lo).toInt()
            if (b in bins.indices) bins[b]++
        }
        var peak = 0
        for (b in bins.indices) if (bins[b] > bins[peak]) peak = b
        val ground = lo + peak + 0.5f
        val cut = ground + clearanceMm
        var kept = 0
        for (i in 0 until n) if (-bb.getFloat(headerEnd + i * stride + 8) > cut) kept++
        if (kept == n || kept < 100) {
            Log.i(TAG, "left alone: ground %.1f cut %.1f kept %d/%d".format(ground, cut, kept, n))
            return -1
        }
        val out = ByteArray(kept * stride)
        var k = 0
        for (i in 0 until n) {
            if (-bb.getFloat(headerEnd + i * stride + 8) <= cut) continue
            System.arraycopy(data, headerEnd + i * stride, out, k * stride, stride)
            k++
        }
        val newHeader = header.lineSequence()
            .map { if (it.startsWith("element vertex ")) "element vertex $k" else it }
            .joinToString("
", postfix = "
")
        FileOutputStream(ply).use { stream ->
            stream.write(newHeader.toByteArray(Charsets.US_ASCII))
            stream.write(out)
        }
        Log.i(TAG, "ground %.1f mm, cut %.1f mm, removed %d kept %d".format(ground, cut, n - kept, kept))
        return n - kept
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
