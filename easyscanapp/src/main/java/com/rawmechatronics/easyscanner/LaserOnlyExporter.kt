package com.rawmechatronics.easyscanner

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Exports a finished Laser-Only capture session directly into:
 * 1. A metric binary PLY point cloud (`laser-only.ply`, millimetres) in the session folder,
 * 2. A CloudView `.splat` preview in `filesDir/splats/laser-only-<id>.splat`, and
 * 3. A metric binary STL mesh (`filesDir/scans/laser-only-<id>.stl`, 1.0 mm TSDF voxels)
 *    ready to send straight to TrioSlicer's build plate without running photogrammetry.
 */
object LaserOnlyExporter {
    private const val TAG = "LaserOnlyExporter"

    data class Result(
        val plyFile: File,
        val stlFile: File?,
        val splatName: String?,
        val points: Int,
        val frames: Int,
        val triangles: Int,
        val voxelMm: Float,
        val poseSource: String,
    )

    private data class PosedLaserFrame(
        val pose: RigidPose,
        val pointsCamM: FloatArray,
    )

    fun export(
        sessionDir: File,
        filesDir: File,
        preferredVoxelMm: Float = 1.0f,
    ): Result {
        val csv = File(sessionDir, "frames.csv")
        require(csv.isFile) { "Missing frames.csv in ${sessionDir.name}" }
        val laserDir = File(sessionDir, "laser")
        require(laserDir.isDirectory) { "No laser frames recorded" }

        val frames = ArrayList<PosedLaserFrame>()
        var boardFrames = 0
        var vioFrames = 0

        csv.bufferedReader().use { br ->
            val hdr = br.readLine()?.split(',') ?: emptyList()
            val iFrame = hdr.indexOf("frame")
            val iPose = hdr.indexOf("pose16")
            val iVio = hdr.indexOf("vio_pose16")
            require(iFrame >= 0) { "Malformed frames.csv header" }

            while (true) {
                val line = br.readLine() ?: break
                if (line.isBlank()) continue
                val cols = line.split(',')
                val idx = cols.getOrNull(iFrame)?.trim()?.toIntOrNull() ?: continue
                val f32 = File(laserDir, "f%05d.f32".format(idx))
                if (!f32.isFile || f32.length() < 12L) continue

                val boardPose = cols.getOrNull(iPose)?.let { parsePoseString(it, 0.001) }
                val vioPose = cols.getOrNull(iVio)?.let { parsePoseString(it, 1.0) }
                val pose = boardPose ?: vioPose ?: continue
                if (boardPose != null) boardFrames++ else vioFrames++

                val camM = readLaserPointsMeters(f32)
                if (camM.size >= 3) {
                    frames += PosedLaserFrame(pose, camM)
                }
            }
        }
        require(frames.isNotEmpty()) { "No posed laser frames to export" }

        val worldXyzM = ArrayList<Float>(frames.size * 300)
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (f in frames) {
            val pts = f.pointsCamM
            var i = 0
            while (i + 2 < pts.size) {
                val wp = f.pose.cameraToWorld(pts[i].toDouble(), pts[i + 1].toDouble(), pts[i + 2].toDouble())
                val wx = wp[0].toFloat()
                val wy = wp[1].toFloat()
                val wz = wp[2].toFloat()
                if (wx.isFinite() && wy.isFinite() && wz.isFinite()) {
                    worldXyzM.add(wx); worldXyzM.add(wy); worldXyzM.add(wz)
                    if (wz < minZ) minZ = wz
                    if (wz > maxZ) maxZ = wz
                }
                i += 3
            }
        }
        val ptCount = worldXyzM.size / 3
        require(ptCount > 0) { "Laser frames contained no valid 3D points" }

        val plyFile = File(sessionDir, "laser-only.ply")
        writeHeightColoredPlyMm(plyFile, worldXyzM, minZ, maxZ)

        val splatsDir = File(filesDir, "splats").apply { mkdirs() }
        val splatFile = File(splatsDir, "laser-only-${sessionDir.name}.splat")
        val splatOk = runCatching { SplatExporter.convert(plyFile, splatFile, voxelMm = 0f) > 0 }.getOrDefault(false)

        val spanM = robustSpanMetres(worldXyzM)
        val voxelMm = max(preferredVoxelMm, (spanM * 1000.0 / 450.0).toFloat()).coerceIn(0.75f, 8.0f)
        val voxelM = voxelMm * 0.001f
        val scansDir = File(filesDir, "scans").apply { mkdirs() }
        val stlOut = File(scansDir, "laser-only-${sessionDir.name}.stl")
        var triangles = 0
        if (NativeLiveTsdf.available) {
            val handle = NativeLiveTsdf.nativeCreate(voxelM, voxelM * 4.0f, 131072, max(4.0, spanM * 3.0))
            try {
                for (f in frames) {
                    NativeLiveTsdf.nativeIntegrateLaser(handle, f.pose.r, f.pose.t, f.pointsCamM, 12.0f)
                }
                val tris = NativeLiveTsdf.nativeWriteMeshStl(handle, stlOut.absolutePath, true)
                triangles = max(0, tris)
            } finally {
                NativeLiveTsdf.nativeDestroy(handle)
            }
        }

        val poseSource = when {
            boardFrames > 0 && vioFrames == 0 -> "board"
            vioFrames > 0 && boardFrames == 0 -> "vio"
            else -> "board+vio"
        }
        runCatching {
            Log.i(TAG, "exported ${sessionDir.name}: frames=${frames.size} pts=$ptCount tris=$triangles voxel=${voxelMm}mm ($poseSource)")
        }
        return Result(
            plyFile = plyFile,
            stlFile = stlOut.takeIf { it.isFile && it.length() > 84L && triangles > 0 },
            splatName = if (splatOk && splatFile.isFile) splatFile.name else null,
            points = ptCount,
            frames = frames.size,
            triangles = triangles,
            voxelMm = voxelMm,
            poseSource = poseSource,
        )
    }

    private fun parsePoseString(raw: String, translationScale: Double): RigidPose? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 16) return null
        val d = DoubleArray(16)
        for (i in 0 until 16) {
            d[i] = parts[i].toDoubleOrNull() ?: return null
        }
        return RigidPose.fromRowMajor16(d, translationScale)
    }

    private fun readLaserPointsMeters(file: File): FloatArray {
        val raw = file.readBytes()
        val n = raw.size / 12
        if (n <= 0) return FloatArray(0)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(n * 3)
        var kept = 0
        for (i in 0 until n) {
            val x = bb.getFloat() * 0.001f
            val y = bb.getFloat() * 0.001f
            val z = bb.getFloat() * 0.001f
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || z <= 0.03f || z > 5.0f) continue
            out[kept * 3] = x
            out[kept * 3 + 1] = y
            out[kept * 3 + 2] = z
            kept++
        }
        return if (kept == n) out else out.copyOf(kept * 3)
    }

    private fun robustSpanMetres(xyzM: List<Float>): Double {
        val count = xyzM.size / 3
        if (count < 4) return 0.3
        val step = max(1, count / 4000)
        val xs = ArrayList<Float>(min(count, 4096))
        val ys = ArrayList<Float>(min(count, 4096))
        val zs = ArrayList<Float>(min(count, 4096))
        var i = 0
        while (i < count) {
            xs.add(xyzM[i * 3]); ys.add(xyzM[i * 3 + 1]); zs.add(xyzM[i * 3 + 2])
            i += step
        }
        xs.sort(); ys.sort(); zs.sort()
        val lo = (xs.size * 0.05).toInt()
        val hi = min(xs.size - 1, (xs.size * 0.95).toInt())
        return max(xs[hi] - xs[lo], max(ys[hi] - ys[lo], zs[hi] - zs[lo])).toDouble().coerceIn(0.05, 5.0)
    }

    private fun writeHeightColoredPlyMm(out: File, xyzM: List<Float>, minZ: Float, maxZ: Float) {
        val n = xyzM.size / 3
        val zSpan = max(1e-4f, maxZ - minZ)
        val hdr = "ply\nformat binary_little_endian 1.0\n" +
            "element vertex $n\n" +
            "property float x\nproperty float y\nproperty float z\n" +
            "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n"
        FileOutputStream(out).use { os ->
            os.write(hdr.toByteArray(Charsets.US_ASCII))
            val chunk = ByteBuffer.allocate(min(n, 4096) * 15).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                if (chunk.remaining() < 15) {
                    os.write(chunk.array(), 0, chunk.position())
                    chunk.clear()
                }
                val x = xyzM[i * 3] * 1000f
                val y = xyzM[i * 3 + 1] * 1000f
                val z = xyzM[i * 3 + 2] * 1000f
                val t = ((xyzM[i * 3 + 2] - minZ) / zSpan).coerceIn(0f, 1f)
                // Clean cyan -> emerald -> gold height ramp matching FusionPreviewView.
                val r = (36 + 215 * t).toInt().coerceIn(0, 255)
                val g = (190 + 50 * (1f - kotlin.math.abs(2f * t - 1f))).toInt().coerceIn(0, 255)
                val b = (240 * (1f - t) + 30).toInt().coerceIn(0, 255)
                chunk.putFloat(x); chunk.putFloat(y); chunk.putFloat(z)
                chunk.put(r.toByte()); chunk.put(g.toByte()); chunk.put(b.toByte())
            }
            if (chunk.position() > 0) os.write(chunk.array(), 0, chunk.position())
        }
    }
}
