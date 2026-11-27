package com.tomppi.splatproto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns the per-frame EasyScanner laser points into a coloured, SE(3)-interpolated Gaussian
 * initialization PLY (`init.ply`) in the exact COLMAP world that Cross3DGS trains in.
 *
 * Uses [LaserMvsFusion.readColmapPoses] + [LaserMvsFusion.loadBaLaserFramesTyped] so laser
 * frames between keyframes receive smooth SLERP/linear camera-centre poses, and samples real
 * RGB colours from the nearest keyframe image for faster Cross3DGS convergence.
 */
object LaserInit {
    private const val SH_C0 = 0.28209479177387814f
    private const val DEFAULT_GRAY = 0x9A

    fun build(scan: Scan, out: File): Boolean {
        val poses = LaserMvsFusion.readColmapPoses(File(scan.dir, "sparse/0/images.txt")) ?: return false
        if (poses.isEmpty()) return false
        val unscaledFrames = LaserMvsFusion.loadBaLaserFramesTyped(scan, poses, scale = 1.0, vioToPoseScale = 1.0)
        if (unscaledFrames.isEmpty()) return false
        val intr = LaserMvsFusion.readPrimaryIntrinsics(scan)
        val refCloud = LaserMvsFusion.readParallaxPly(scan.dense)
            ?: LaserMvsFusion.readParallaxPly(scan.sparse)
        // `laserScale = z_laser_m / z_colmap`: divide metric laser coordinates by `laserScale`
        // so the initial Gaussians land in COLMAP's native coordinate scale that Cross3DGS trains in.
        val laserScale = if (intr != null && refCloud != null && refCloud.count >= 20) {
            LaserMvsFusion.estimateLaserScale(unscaledFrames, refCloud.xyzNxyz, 6, refCloud.count, intr)
        } else 1.0
        val invScale = 1.0 / laserScale
        val frames = if (abs(laserScale - 1.0) > 1e-4) {
            LaserMvsFusion.loadBaLaserFramesTyped(scan, poses, scale = 1.0, vioToPoseScale = invScale)
        } else unscaledFrames

        val bitmaps = HashMap<String, Bitmap?>()
        val pts = ArrayList<Float>()
        val shDc = ArrayList<Float>()

        for (lf in frames) {
            val r = lf.pose.r
            val t = lf.pose.t
            val bmp = lf.keyframeFile?.let { kf ->
                bitmaps.getOrPut(kf.path) { runCatching { BitmapFactory.decodeFile(kf.path) }.getOrNull() }
            }
            val kp = lf.keyframePose
            val raw = lf.pointsCamM
            val n = raw.size / 3
            for (i in 0 until n) {
                val px = raw[i * 3].toDouble() * invScale
                val py = raw[i * 3 + 1].toDouble() * invScale
                val pz = raw[i * 3 + 2].toDouble() * invScale
                val dx = px - t[0]; val dy = py - t[1]; val dz = pz - t[2]
                val wx = r[0] * dx + r[3] * dy + r[6] * dz
                val wy = r[1] * dx + r[4] * dy + r[7] * dz
                val wz = r[2] * dx + r[5] * dy + r[8] * dz
                if (!wx.isFinite() || !wy.isFinite() || !wz.isFinite()) continue
                if (abs(wx) > 1000.0 || abs(wy) > 1000.0 || abs(wz) > 1000.0) continue
                pts.add(wx.toFloat()); pts.add(wy.toFloat()); pts.add(wz.toFloat())

                var cr = DEFAULT_GRAY; var cg = DEFAULT_GRAY; var cb = DEFAULT_GRAY
                if (bmp != null && kp != null && intr != null) {
                    val kr = kp.r; val kt = kp.t
                    val cz = kr[6] * wx + kr[7] * wy + kr[8] * wz + kt[2]
                    if (cz > 0.02) {
                        val cx = kr[0] * wx + kr[1] * wy + kr[2] * wz + kt[0]
                        val cy = kr[3] * wx + kr[4] * wy + kr[5] * wz + kt[1]
                        val u = ((intr.fx * cx / cz + intr.cx) * bmp.width / max(1, intr.width)).roundToInt()
                        val v = ((intr.fy * cy / cz + intr.cy) * bmp.height / max(1, intr.height)).roundToInt()
                        if (u in 0 until bmp.width && v in 0 until bmp.height) {
                            val rgb = bmp.getPixel(u, v)
                            cr = (rgb shr 16) and 0xFF
                            cg = (rgb shr 8) and 0xFF
                            cb = rgb and 0xFF
                        }
                    }
                }
                shDc.add(((cr / 255f) - 0.5f) / SH_C0)
                shDc.add(((cg / 255f) - 0.5f) / SH_C0)
                shDc.add(((cb / 255f) - 0.5f) / SH_C0)
            }
        }
        for (b in bitmaps.values) b?.recycle()
        val count = pts.size / 3
        if (count == 0) return false

        out.parentFile?.mkdirs()
        // 1.5 mm isotropic initial Gaussians (scaled into COLMAP world units) match laser stripe
        // spacing without overlapping excessively on the mobile Vulkan rasterizer;
        // Cross3dgsCheckpoint converts log-scale and logit-opacity into linear values for Cross3DGS.
        val initScaleColmap = (0.0015 * invScale).toFloat().coerceIn(1e-4f, 0.25f)
        val logScale = kotlin.math.ln(initScaleColmap)
        val opacity = 2.2f // sigmoid(2.2) ~ 0.90
        val header = "ply\nformat binary_little_endian 1.0\n" +
            "element vertex $count\n" +
            "property float x\nproperty float y\nproperty float z\n" +
            "property float scale_0\nproperty float scale_1\nproperty float scale_2\n" +
            "property float rot_0\nproperty float rot_1\nproperty float rot_2\nproperty float rot_3\n" +
            "property float opacity\n" +
            "property float f_dc_0\nproperty float f_dc_1\nproperty float f_dc_2\n" +
            "end_header\n"
        FileOutputStream(out).use { os ->
            os.write(header.toByteArray(Charsets.US_ASCII))
            val chunk = ByteBuffer.allocate(minOf(count, 4096) * 14 * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                if (chunk.remaining() < 14 * 4) {
                    os.write(chunk.array(), 0, chunk.position())
                    chunk.clear()
                }
                val b = i * 3
                chunk.putFloat(pts[b]); chunk.putFloat(pts[b + 1]); chunk.putFloat(pts[b + 2])
                chunk.putFloat(logScale); chunk.putFloat(logScale); chunk.putFloat(logScale)
                chunk.putFloat(1f); chunk.putFloat(0f); chunk.putFloat(0f); chunk.putFloat(0f)
                chunk.putFloat(opacity)
                chunk.putFloat(shDc[b]); chunk.putFloat(shDc[b + 1]); chunk.putFloat(shDc[b + 2])
            }
            if (chunk.position() > 0) os.write(chunk.array(), 0, chunk.position())
        }
        return true
    }
}
