package com.tomppi.splatproto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.rawmechatronics.easyscanner.SplatExporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Preview the laser points imported from one EasyScanner hybrid session in the
 * app's own EasyScanner CloudView.
 *
 * All laser frames are shown (including frames without a 640x480 JPEG); frames
 * with an image are sampled from it, frames without one take the colour of the
 * nearest keyframe.
 */
object HybridPreview {
    private const val GRAY = 0x9a9a9a

    private data class Img(val frame: Int, val ts: Long, val file: File, val pose: DoubleArray?)

    fun build(scan: Scan, outSplat: File): File? {
        val manifest = File(File(scan.dir, "hybrid"), "hybrid.json")
        if (!manifest.isFile) return null
        val doc = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return null
        val images = parseImages(scan, doc.optJSONArray("images"))
        val frames = doc.optJSONArray("laser_frames") ?: doc.optJSONArray("images") ?: return null
        if (frames.length() == 0) return null
        val loaded = scan.load() ?: return null
        val intr = loaded.cameras.firstOrNull()?.intr ?: return null
        val byFrame = images.associateBy { it.frame }
        val byTime = images.sortedBy { it.ts }
        val bitmaps = HashMap<String, Bitmap?>()

        data class P(val x: Float, val y: Float, val z: Float, val rgb: Int)
        val points = ArrayList<P>()
        for (k in 0 until frames.length()) {
            val row = frames.optJSONObject(k) ?: continue
            val laserRel = row.optString("laser", "")
            if (laserRel.isEmpty()) continue
            val laser = File(scan.dir, laserRel)
            if (!laser.isFile || laser.length() < 12L) continue
            val pose = parse16(row.optString("board_pose16", "")) ?: continue
            val frameNo = row.optInt("frame", -1)
            val ts = row.optLong("sensor_timestamp_ns", 0L)
            val img = byFrame[frameNo] ?: nearest(byTime, ts)
            val bmp = img?.let { im ->
                bitmaps.getOrPut(im.file.path) {
                    runCatching { BitmapFactory.decodeFile(im.file.path) }.getOrNull()
                }
            }
            val raw = laser.readBytes()
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val n = raw.size / 12
            for (i in 0 until n) {
                val px = bb.getFloat(); val py = bb.getFloat(); val pz = bb.getFloat()
                // board <- camera, then camera <- board with the same pose.
                val bx = pose[0] * px + pose[4] * py + pose[8] * pz - (pose[0] * pose[3] + pose[4] * pose[7] + pose[8] * pose[11])
                val by = pose[1] * px + pose[5] * py + pose[9] * pz - (pose[1] * pose[3] + pose[5] * pose[7] + pose[9] * pose[11])
                val bz = pose[2] * px + pose[6] * py + pose[10] * pz - (pose[2] * pose[3] + pose[6] * pose[7] + pose[10] * pose[11])
                if (!bx.isFinite() || !by.isFinite() || !bz.isFinite()) continue
                var rgb = GRAY
                if (bmp != null && img?.pose != null) {
                    val cp = img.pose
                    val cx = cp[0] * bx + cp[1] * by + cp[2] * bz + cp[3]
                    val cy = cp[4] * bx + cp[5] * by + cp[6] * bz + cp[7]
                    val cz = cp[8] * bx + cp[9] * by + cp[10] * bz + cp[11]
                    if (cz > 1e-3) {
                        val u = (intr.fx * cx / cz + intr.cx).roundToInt()
                        val v = (intr.fy * cy / cz + intr.cy).roundToInt()
                        if (u in 0 until bmp.width && v in 0 until bmp.height) rgb = bmp.getPixel(u, v)
                    }
                }
                points.add(P(bx.toFloat(), by.toFloat(), bz.toFloat(), rgb))
            }
        }
        for (b in bitmaps.values) b?.recycle()
        if (points.isEmpty()) return null

        val ply = File(File(scan.dir, "exports").apply { mkdirs() }, "laser-preview.ply")
        val header = "ply\nformat binary_little_endian 1.0\n" +
            "element vertex " + points.size + "\n" +
            "property float x\nproperty float y\nproperty float z\n" +
            "property uchar red\nproperty uchar green\nproperty uchar blue\n" +
            "end_header\n"
        FileOutputStream(ply).use { out ->
            out.write(header.toByteArray(Charsets.US_ASCII))
            val buf = ByteBuffer.allocate(points.size * 15).order(ByteOrder.LITTLE_ENDIAN)
            for (p in points) {
                buf.putFloat(p.x); buf.putFloat(p.y); buf.putFloat(p.z)
                buf.put((p.rgb shr 16).toByte()); buf.put((p.rgb shr 8).toByte()); buf.put(p.rgb.toByte())
            }
            out.write(buf.array())
        }
        outSplat.parentFile?.mkdirs()
        if (SplatExporter.convert(ply, outSplat, voxelMm = 0f) <= 0) return null
        return outSplat
    }

    private fun parseImages(scan: Scan, array: JSONArray?): List<Img> {
        if (array == null) return emptyList()
        val out = ArrayList<Img>()
        for (k in 0 until array.length()) {
            val row = array.optJSONObject(k) ?: continue
            val file = row.optString("file", "")
            if (file.isEmpty()) continue
            val f = File(scan.imagesDir, file)
            if (!f.isFile) continue
            out += Img(row.optInt("frame", -1), row.optLong("sensor_timestamp_ns", 0L),
                f, parse16(row.optString("board_pose16", "")))
        }
        return out
    }

    private fun nearest(images: List<Img>, ts: Long): Img? {
        if (images.isEmpty() || ts <= 0L) return images.firstOrNull()
        var lo = 0
        var hi = images.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (images[mid].ts < ts) lo = mid + 1 else hi = mid
        }
        var best = images[lo]
        var gap = kotlin.math.abs(best.ts - ts)
        if (lo > 0) {
            val g = kotlin.math.abs(images[lo - 1].ts - ts)
            if (g < gap) { best = images[lo - 1]; gap = g }
        }
        return best
    }

    private fun parse16(s: String): DoubleArray? {
        if (s.isBlank()) return null
        val v = s.trim().split(' ').mapNotNull { it.toDoubleOrNull() }
        return if (v.size == 16) DoubleArray(16) { v[it] } else null
    }
}
