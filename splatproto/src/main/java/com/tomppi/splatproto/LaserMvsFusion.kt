package com.tomppi.splatproto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.rawmechatronics.easyscanner.NativeLiveTsdf
import com.rawmechatronics.easyscanner.SplatExporter
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Post-processing fusion of EasyScanner laser sweeps with Parallax BA poses and dense MVS:
 *
 * 1. Tightens per-frame laser poses via SE(3) interpolation across bundle-adjusted COLMAP poses.
 * 2. Locks global metric scale directly to the calibrated camera-frame laser plane when laser
 *    frames are present (falling back to SuperVINS metric scale for photo-only scans).
 * 3. Filters Parallax `dense.ply` through U2Net object masks when present so background clutter
 *    is excluded from the mesh.
 * 4. Fuses laser frames (high authority) + oriented dense MVS points (gap fill + color) in the
 *    native off-heap C++ sparse TSDF and exports a metric binary STL (`fused-mesh.stl`), colored
 *    mesh PLY (`fused-mesh.ply`), and merged cloud (`fused-cloud.ply` + `.splat` preview).
 */
object LaserMvsFusion {
    private const val TAG = "LaserMvsFusion"

    /** World -> camera rigid pose: p_cam = R * p_world + t, with unit quaternion q = [w, x, y, z]. */
    data class CameraPose(
        val r: DoubleArray,
        val t: DoubleArray,
        val q: DoubleArray,
    ) {
        /** Camera centre in world coordinates: C = -R^T * t. */
        fun centre(): DoubleArray = doubleArrayOf(
            -(r[0] * t[0] + r[3] * t[1] + r[6] * t[2]),
            -(r[1] * t[0] + r[4] * t[1] + r[7] * t[2]),
            -(r[2] * t[0] + r[5] * t[1] + r[8] * t[2]),
        )

        fun scaled(scale: Double): CameraPose = CameraPose(
            r.copyOf(),
            doubleArrayOf(t[0] * scale, t[1] * scale, t[2] * scale),
            q.copyOf(),
        )
    }

    data class DenseCloud(
        val xyzNxyz: FloatArray,
        val rgb: ByteArray,
        val count: Int,
        val hasNormals: Boolean,
    )

    data class LaserFrame(
        val frame: Int,
        val timestampNs: Long,
        val pose: CameraPose,
        val pointsCamM: FloatArray,
        val keyframeFile: File?,
        val keyframePose: CameraPose?,
        val vioAnchored: Boolean = false,
    )

    data class Result(
        val stlFile: File,
        val meshPlyFile: File?,
        val cloudPlyFile: File,
        val laserRefinedPlyFile: File?,
        val previewSplatFile: File?,
        val laserSplatFile: File?,
        val triangles: Int,
        val laserPoints: Int,
        val mvsPoints: Int,
        val laserScale: Double,
        val voxelMm: Float,
        val maskFiltered: Boolean,
        val vioAnchoredFrames: Int = 0,
    )

    /** Parses COLMAP `sparse/0/images.txt` into world->camera poses keyed by image filename. */
    fun readColmapPoses(file: File): Map<String, CameraPose>? {
        if (!file.isFile) return null
        val out = HashMap<String, CameraPose>()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val s = line.trim()
                if (s.isEmpty() || s.startsWith("#")) continue
                val p = s.split(Regex("\\s+"))
                if (p.size < 10) continue
                val qw = p[1].toDoubleOrNull() ?: continue
                val qx = p[2].toDoubleOrNull() ?: continue
                val qy = p[3].toDoubleOrNull() ?: continue
                val qz = p[4].toDoubleOrNull() ?: continue
                val tx = p[5].toDoubleOrNull() ?: continue
                val ty = p[6].toDoubleOrNull() ?: continue
                val tz = p[7].toDoubleOrNull() ?: continue
                val name = p[9]
                val n = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
                if (n < 1e-12) continue
                val q = doubleArrayOf(qw / n, qx / n, qy / n, qz / n)
                out[name] = CameraPose(quatToMat(q), doubleArrayOf(tx, ty, tz), q)
            }
        }
        return out
    }

    fun quatToMat(q: DoubleArray): DoubleArray {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
        )
    }

    fun matToQuat(r: DoubleArray): DoubleArray {
        val trace = r[0] + r[4] + r[8]
        val w: Double; val x: Double; val y: Double; val z: Double
        if (trace > 0.0) {
            val s = sqrt(trace + 1.0) * 2.0
            w = 0.25 * s
            x = (r[7] - r[5]) / s
            y = (r[2] - r[6]) / s
            z = (r[3] - r[1]) / s
        } else if (r[0] > r[4] && r[0] > r[8]) {
            val s = sqrt(1.0 + r[0] - r[4] - r[8]) * 2.0
            w = (r[7] - r[5]) / s
            x = 0.25 * s
            y = (r[1] + r[3]) / s
            z = (r[2] + r[6]) / s
        } else if (r[4] > r[8]) {
            val s = sqrt(1.0 + r[4] - r[0] - r[8]) * 2.0
            w = (r[2] - r[6]) / s
            x = (r[1] + r[3]) / s
            y = 0.25 * s
            z = (r[5] + r[7]) / s
        } else {
            val s = sqrt(1.0 + r[8] - r[0] - r[4]) * 2.0
            w = (r[3] - r[1]) / s
            x = (r[2] + r[6]) / s
            y = (r[5] + r[7]) / s
            z = 0.25 * s
        }
        val n = sqrt(w * w + x * x + y * y + z * z).coerceAtLeast(1e-12)
        return doubleArrayOf(w / n, x / n, y / n, z / n)
    }

    /** Parses a whitespace-separated row-major 4x4 world->camera pose string. */
    fun parsePose16String(raw: String, translationScale: Double = 1.0): CameraPose? {
        if (raw.isBlank()) return null
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size < 16) return null
        val v = DoubleArray(16)
        for (i in 0 until 16) {
            val d = parts[i].toDoubleOrNull() ?: return null
            if (!d.isFinite()) return null
            v[i] = d
        }
        val r = doubleArrayOf(v[0], v[1], v[2], v[4], v[5], v[6], v[8], v[9], v[10])
        val det = r[0] * (r[4] * r[8] - r[5] * r[7]) -
            r[1] * (r[3] * r[8] - r[5] * r[6]) +
            r[2] * (r[3] * r[7] - r[4] * r[6])
        if (!det.isFinite() || det < 0.95 || det > 1.05) return null
        val t = doubleArrayOf(v[3] * translationScale, v[7] * translationScale, v[11] * translationScale)
        return CameraPose(r, t, matToQuat(r))
    }

    /**
     * Propagates an anchored BA pose [baAnchor] to a target frame using the local relative
     * camera motion observed by VIO (`vioAnchor -> vioTarget`):
     *   R_target = R_vioTarget * R_vioAnchor^T * R_baAnchor
     *   C_target = C_baAnchor + vioToPoseScale * R_baAnchor^T * R_vioAnchor * (C_vioTarget - C_vioAnchor)
     */
    fun propagateFromAnchor(
        baAnchor: CameraPose,
        vioAnchor: CameraPose,
        vioTarget: CameraPose,
        vioToPoseScale: Double = 1.0,
    ): CameraPose {
        // R_rel_cam = R_vioTarget * R_vioAnchor^T (maps anchor-camera frame -> target-camera frame)
        val rvt = vioTarget.r
        val rva = vioAnchor.r
        val rba = baAnchor.r
        val rel = DoubleArray(9)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                rel[i * 3 + j] = rvt[i * 3] * rva[j * 3] +
                    rvt[i * 3 + 1] * rva[j * 3 + 1] +
                    rvt[i * 3 + 2] * rva[j * 3 + 2]
            }
        }
        // R_out = rel * R_baAnchor
        val rOut = DoubleArray(9)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                rOut[i * 3 + j] = rel[i * 3] * rba[j] +
                    rel[i * 3 + 1] * rba[3 + j] +
                    rel[i * 3 + 2] * rba[6 + j]
            }
        }
        val cva = vioAnchor.centre()
        val cvt = vioTarget.centre()
        val dvx = cvt[0] - cva[0]
        val dvy = cvt[1] - cva[1]
        val dvz = cvt[2] - cva[2]
        // Displacement in anchor camera coordinates: d_cam = R_vioAnchor * (C_vioTarget - C_vioAnchor)
        val dcx = rva[0] * dvx + rva[1] * dvy + rva[2] * dvz
        val dcy = rva[3] * dvx + rva[4] * dvy + rva[5] * dvz
        val dcz = rva[6] * dvx + rva[7] * dvy + rva[8] * dvz
        // Rotate into BA world via R_baAnchor^T and scale from VIO metres into BA pose units.
        val cba = baAnchor.centre()
        val cx = cba[0] + vioToPoseScale * (rba[0] * dcx + rba[3] * dcy + rba[6] * dcz)
        val cy = cba[1] + vioToPoseScale * (rba[1] * dcx + rba[4] * dcy + rba[7] * dcz)
        val cz = cba[2] + vioToPoseScale * (rba[2] * dcx + rba[5] * dcy + rba[8] * dcz)
        val tOut = doubleArrayOf(
            -(rOut[0] * cx + rOut[1] * cy + rOut[2] * cz),
            -(rOut[3] * cx + rOut[4] * cy + rOut[5] * cz),
            -(rOut[6] * cx + rOut[7] * cy + rOut[8] * cz),
        )
        return CameraPose(rOut, tOut, matToQuat(rOut))
    }

    private fun rotationAngleDeg(ra: DoubleArray, rb: DoubleArray): Double {
        // tr(ra * rb^T) = sum_i (row_i(ra) . row_i(rb))
        var tr = 0.0
        for (i in 0 until 9) tr += ra[i] * rb[i]
        val c = ((tr - 1.0) * 0.5).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    private fun dist3(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Drift-compensated VIO-anchored SE(3) interpolation between BA keyframes [baA] and [baB].
     *
     * Propagates forward from [baA] using `vioA -> vioTarget` and backward from [baB] using
     * `vioB -> vioTarget`, then blends with [alpha] so the trajectory faithfully follows real
     * curved/accelerated handheld motion while cancelling VIO drift cleanly to zero at both
     * bundle-adjusted endpoints. Returns `null` if the VIO segment fails consistency checks
     * (caller falls back to straight-line SLERP [interpolatePose]).
     */
    fun interpolatePoseVioAnchored(
        baA: CameraPose,
        baB: CameraPose,
        vioA: CameraPose,
        vioB: CameraPose,
        vioTarget: CameraPose,
        alphaRaw: Double,
        vioToPoseScale: Double = 1.0,
    ): CameraPose? {
        val alpha = alphaRaw.coerceIn(0.0, 1.0)
        if (alpha <= 1e-9) return CameraPose(baA.r.copyOf(), baA.t.copyOf(), baA.q.copyOf())
        if (alpha >= 1.0 - 1e-9) return CameraPose(baB.r.copyOf(), baB.t.copyOf(), baB.q.copyOf())

        // Verify VIO agrees with BA across the [A, B] span (guards against VIO resets/glitches).
        val fwdB = propagateFromAnchor(baA, vioA, vioB, vioToPoseScale)
        val rotErrDeg = rotationAngleDeg(fwdB.r, baB.r)
        if (!rotErrDeg.isFinite() || rotErrDeg > 18.0) return null

        val dBa = dist3(baA.centre(), baB.centre())
        val dVio = dist3(vioA.centre(), vioB.centre()) * vioToPoseScale
        val maxSpan = 1.5 * vioToPoseScale
        if (dVio > maxSpan || dBa > maxSpan) return null
        if (dBa > 0.02 * vioToPoseScale) {
            val ratio = dVio / dBa
            if (ratio !in 0.35..2.8) return null
        }
        val endpointPosErr = dist3(fwdB.centre(), baB.centre())
        if (endpointPosErr > max(0.06 * vioToPoseScale, 0.70 * dBa)) return null

        // Guard against a single-frame VIO jump at vioTarget itself.
        val dA = dist3(vioA.centre(), vioTarget.centre()) * vioToPoseScale
        val dB = dist3(vioB.centre(), vioTarget.centre()) * vioToPoseScale
        val maxDetour = max(0.20 * vioToPoseScale, 2.5 * max(dBa, dVio))
        if (dA > maxDetour || dB > maxDetour) return null

        val fwd = propagateFromAnchor(baA, vioA, vioTarget, vioToPoseScale)
        val bwd = propagateFromAnchor(baB, vioB, vioTarget, vioToPoseScale)
        return interpolatePose(fwd, bwd, alpha)
    }

    /**
     * Smooth SE(3) interpolation between two world->camera poses:
     * SLERP on orientation + linear interpolation on world camera centres.
     */
    fun interpolatePose(a: CameraPose, b: CameraPose, alphaRaw: Double): CameraPose {
        val alpha = alphaRaw.coerceIn(0.0, 1.0)
        if (alpha <= 1e-9) return CameraPose(a.r.copyOf(), a.t.copyOf(), a.q.copyOf())
        if (alpha >= 1.0 - 1e-9) return CameraPose(b.r.copyOf(), b.t.copyOf(), b.q.copyOf())

        var bw = b.q[0]; var bx = b.q[1]; var by = b.q[2]; var bz = b.q[3]
        var dot = a.q[0] * bw + a.q[1] * bx + a.q[2] * by + a.q[3] * bz
        if (dot < 0.0) {
            dot = -dot
            bw = -bw; bx = -bx; by = -by; bz = -bz
        }
        val q = if (dot > 0.9995) {
            val w = a.q[0] + (bw - a.q[0]) * alpha
            val x = a.q[1] + (bx - a.q[1]) * alpha
            val y = a.q[2] + (by - a.q[2]) * alpha
            val z = a.q[3] + (bz - a.q[3]) * alpha
            val n = sqrt(w * w + x * x + y * y + z * z).coerceAtLeast(1e-12)
            doubleArrayOf(w / n, x / n, y / n, z / n)
        } else {
            val theta = acos(dot.coerceIn(-1.0, 1.0))
            val invSin = 1.0 / sin(theta)
            val w0 = sin((1.0 - alpha) * theta) * invSin
            val w1 = sin(alpha * theta) * invSin
            doubleArrayOf(
                a.q[0] * w0 + bw * w1,
                a.q[1] * w0 + bx * w1,
                a.q[2] * w0 + by * w1,
                a.q[3] * w0 + bz * w1,
            )
        }
        val r = quatToMat(q)
        val ca = a.centre()
        val cb = b.centre()
        val cx = ca[0] + (cb[0] - ca[0]) * alpha
        val cy = ca[1] + (cb[1] - ca[1]) * alpha
        val cz = ca[2] + (cb[2] - ca[2]) * alpha
        val t = doubleArrayOf(
            -(r[0] * cx + r[1] * cy + r[2] * cz),
            -(r[3] * cx + r[4] * cy + r[5] * cz),
            -(r[6] * cx + r[7] * cy + r[8] * cz),
        )
        return CameraPose(r, t, q)
    }

    /**
     * Reads a binary little-endian Parallax PLY (`dense.ply` or `sparse.ply`).
     * Supports float x/y/z, optional float nx/ny/nz, and optional uchar red/green/blue.
     */
    fun readParallaxPly(file: File, maxPoints: Int = 500_000): DenseCloud? {
        if (!file.isFile || file.length() < 32L) return null
        val raw = BufferedInputStream(FileInputStream(file)).use { it.readBytes() }
        val marker = "end_header\n".toByteArray(Charsets.US_ASCII)
        val markerCrLf = "end_header\r\n".toByteArray(Charsets.US_ASCII)
        var headerEnd = indexOfBytes(raw, marker)
        var dataOffset = if (headerEnd >= 0) headerEnd + marker.size else -1
        if (headerEnd < 0) {
            headerEnd = indexOfBytes(raw, markerCrLf)
            dataOffset = if (headerEnd >= 0) headerEnd + markerCrLf.size else -1
        }
        if (headerEnd < 0 || dataOffset < 0) return null

        val header = String(raw, 0, headerEnd, Charsets.US_ASCII)
        if (!header.contains("binary_little_endian")) return null

        var vertexCount = 0
        var inVertex = false
        var stride = 0
        var offX = -1; var offY = -1; var offZ = -1
        var offNx = -1; var offNy = -1; var offNz = -1
        var offR = -1; var offG = -1; var offB = -1

        for (rawLine in header.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("element ")) {
                val parts = line.split(Regex("\\s+"))
                inVertex = parts.size >= 3 && parts[1] == "vertex"
                if (inVertex) vertexCount = parts[2].toIntOrNull() ?: 0
                continue
            }
            if (!inVertex || !line.startsWith("property ")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 3 || parts[1] == "list") continue
            val typeSize = when (parts[1]) {
                "float", "float32", "int", "int32", "uint", "uint32" -> 4
                "double", "float64" -> 8
                "short", "int16", "ushort", "uint16" -> 2
                "uchar", "uint8", "char", "int8" -> 1
                else -> return null
            }
            when (parts[2]) {
                "x" -> offX = stride
                "y" -> offY = stride
                "z" -> offZ = stride
                "nx" -> offNx = stride
                "ny" -> offNy = stride
                "nz" -> offNz = stride
                "red", "r" -> offR = stride
                "green", "g" -> offG = stride
                "blue", "b" -> offB = stride
            }
            stride += typeSize
        }
        if (vertexCount <= 0 || stride < 12 || offX < 0 || offY < 0 || offZ < 0) return null
        val available = ((raw.size - dataOffset) / stride).coerceAtMost(vertexCount)
        if (available <= 0) return null

        val step = max(1, (available + maxPoints - 1) / maxPoints)
        val outCap = (available + step - 1) / step
        val xyzNxyz = FloatArray(outCap * 6)
        val rgb = ByteArray(outCap * 3)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val hasNormals = offNx >= 0 && offNy >= 0 && offNz >= 0
        val hasColor = offR >= 0 && offG >= 0 && offB >= 0

        var outCount = 0
        var i = 0
        while (i < available) {
            val base = dataOffset + i * stride
            val x = bb.getFloat(base + offX)
            val y = bb.getFloat(base + offY)
            val z = bb.getFloat(base + offZ)
            if (x.isFinite() && y.isFinite() && z.isFinite()) {
                val nx = if (hasNormals) bb.getFloat(base + offNx) else 0f
                val ny = if (hasNormals) bb.getFloat(base + offNy) else 0f
                val nz = if (hasNormals) bb.getFloat(base + offNz) else 0f
                val fo = outCount * 6
                xyzNxyz[fo] = x; xyzNxyz[fo + 1] = y; xyzNxyz[fo + 2] = z
                xyzNxyz[fo + 3] = if (nx.isFinite()) nx else 0f
                xyzNxyz[fo + 4] = if (ny.isFinite()) ny else 0f
                xyzNxyz[fo + 5] = if (nz.isFinite()) nz else 0f
                val co = outCount * 3
                if (hasColor) {
                    rgb[co] = raw[base + offR]
                    rgb[co + 1] = raw[base + offG]
                    rgb[co + 2] = raw[base + offB]
                } else {
                    rgb[co] = 175.toByte(); rgb[co + 1] = 180.toByte(); rgb[co + 2] = 185.toByte()
                }
                outCount++
            }
            i += step
        }
        return DenseCloud(
            xyzNxyz = if (outCount == outCap) xyzNxyz else xyzNxyz.copyOf(outCount * 6),
            rgb = if (outCount == outCap) rgb else rgb.copyOf(outCount * 3),
            count = outCount,
            hasNormals = hasNormals,
        )
    }

    private data class ResolvedFramePose(
        val pose: CameraPose,
        val colorFile: File,
        val colorPose: CameraPose,
        val vioAnchored: Boolean,
    )

    private fun resolvePoseForFrame(
        frame: Int,
        ts: Long,
        frameVio: CameraPose?,
        vioToPoseScale: Double,
        sorted: List<Any>,
        byFrame: Map<Int, Any>,
        frameOf: (Any) -> Int,
        tsOf: (Any) -> Long,
        fileOf: (Any) -> File,
        poseOf: (Any) -> CameraPose,
        vioOf: (Any) -> CameraPose?,
    ): ResolvedFramePose? {
        if (sorted.isEmpty() || frame < 0) return null
        fun pickColorSource(nearIdx: Int): Any {
            // Avoid sampling RGB from the exact frame where the red laser line sits on that pixel.
            if (sorted.size >= 2 && abs(frameOf(sorted[nearIdx]) - frame) <= 1) {
                val alt = if (nearIdx + 1 < sorted.size) nearIdx + 1 else nearIdx - 1
                return sorted[alt]
            }
            return sorted[nearIdx]
        }
        fun extrapolateFromEdge(edge: Any, nearIdx: Int): ResolvedFramePose {
            val edgeBa = poseOf(edge)
            val edgeVio = vioOf(edge)
            val colorSrc = pickColorSource(nearIdx)
            if (frameVio != null && edgeVio != null) {
                val step = dist3(edgeVio.centre(), frameVio.centre()) * vioToPoseScale
                val rot = rotationAngleDeg(edgeVio.r, frameVio.r)
                if (step < 0.25 * vioToPoseScale && rot < 35.0) {
                    val prop = propagateFromAnchor(edgeBa, edgeVio, frameVio, vioToPoseScale)
                    return ResolvedFramePose(prop, fileOf(colorSrc), poseOf(colorSrc), vioAnchored = true)
                }
            }
            return ResolvedFramePose(edgeBa, fileOf(colorSrc), poseOf(colorSrc), vioAnchored = false)
        }
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (frameOf(sorted[mid]) < frame) lo = mid + 1 else hi = mid
        }
        byFrame[frame]?.let { exact ->
            val colorSrc = pickColorSource(lo)
            return ResolvedFramePose(poseOf(exact), fileOf(colorSrc), poseOf(colorSrc), vioAnchored = false)
        }
        val right = sorted[lo]
        if (lo == 0) {
            if (abs(frameOf(right) - frame) > 20) return null
            return extrapolateFromEdge(right, 0)
        }
        val left = sorted[lo - 1]
        val f0 = frameOf(left)
        val f1 = frameOf(right)
        if (frame > f1) {
            if (frame - f1 > 20) return null
            return extrapolateFromEdge(right, lo)
        }
        val spanFrames = f1 - f0
        if (spanFrames <= 0 || spanFrames > 60) {
            val nearIdx = if (abs(frame - f0) <= abs(f1 - frame)) lo - 1 else lo
            if (min(abs(frame - f0), abs(f1 - frame)) > 20) return null
            return extrapolateFromEdge(sorted[nearIdx], nearIdx)
        }
        val t0 = tsOf(left); val t1 = tsOf(right)
        val alpha = if (ts > 0L && t0 > 0L && t1 > t0) {
            (ts - t0).toDouble() / (t1 - t0).toDouble()
        } else {
            (frame - f0).toDouble() / spanFrames.toDouble()
        }
        val nearIdx = if (alpha < 0.5) lo - 1 else lo
        val colorSrc = pickColorSource(nearIdx)
        val vioL = vioOf(left)
        val vioR = vioOf(right)
        if (frameVio != null && vioL != null && vioR != null) {
            val anchored = interpolatePoseVioAnchored(
                baA = poseOf(left),
                baB = poseOf(right),
                vioA = vioL,
                vioB = vioR,
                vioTarget = frameVio,
                alphaRaw = alpha,
                vioToPoseScale = vioToPoseScale,
            )
            if (anchored != null) {
                return ResolvedFramePose(anchored, fileOf(colorSrc), poseOf(colorSrc), vioAnchored = true)
            }
        }
        val interp = interpolatePose(poseOf(left), poseOf(right), alpha)
        return ResolvedFramePose(interp, fileOf(colorSrc), poseOf(colorSrc), vioAnchored = false)
    }

    /**
     * Estimates the exact metric scale correction factor (`z_laser / z_mvs`) by comparing
     * camera-frame metric laser depths against BA-reconstructed 3D points projected into the
     * same camera pixel bins. Returns `1.0` if insufficient correspondences exist.
     */
    fun estimateLaserScale(
        laserFrames: List<LaserFrame>,
        worldXyz: FloatArray,
        worldStride: Int,
        worldCount: Int,
        intr: Intrinsics,
    ): Double {
        if (laserFrames.isEmpty() || worldCount < 20 || intr.fx <= 0.0 || intr.fy <= 0.0) return 1.0
        val gridW = 160
        val gridH = max(60, (gridW.toDouble() * intr.height / max(1, intr.width)).roundToInt())
        val sx = gridW.toDouble() / max(1, intr.width)
        val sy = gridH.toDouble() / max(1, intr.height)
        val zGrid = FloatArray(gridW * gridH)

        // Subsample world points to at most 20,000 for fast projection per sampled laser frame.
        val ptStep = max(1, worldCount / 20_000)
        val frameStep = max(1, laserFrames.size / 24)
        val ratios = ArrayList<Double>(2048)

        var fi = 0
        while (fi < laserFrames.size) {
            val lf = laserFrames[fi]
            zGrid.fill(Float.POSITIVE_INFINITY)
            val r = lf.pose.r
            val t = lf.pose.t
            var pi = 0
            while (pi < worldCount) {
                val base = pi * worldStride
                val wx = worldXyz[base].toDouble()
                val wy = worldXyz[base + 1].toDouble()
                val wz = worldXyz[base + 2].toDouble()
                val cz = r[6] * wx + r[7] * wy + r[8] * wz + t[2]
                if (cz > 0.04 && cz < 6.0) {
                    val cx = r[0] * wx + r[1] * wy + r[2] * wz + t[0]
                    val cy = r[3] * wx + r[4] * wy + r[5] * wz + t[1]
                    val u = intr.fx * (cx / cz) + intr.cx
                    val v = intr.fy * (cy / cz) + intr.cy
                    val gx = (u * sx).toInt()
                    val gy = (v * sy).toInt()
                    if (gx in 0 until gridW && gy in 0 until gridH) {
                        val idx = gy * gridW + gx
                        if (cz.toFloat() < zGrid[idx]) zGrid[idx] = cz.toFloat()
                    }
                }
                pi += ptStep
            }

            val pts = lf.pointsCamM
            val n = pts.size / 3
            for (k in 0 until n) {
                val lx = pts[k * 3].toDouble()
                val ly = pts[k * 3 + 1].toDouble()
                val lz = pts[k * 3 + 2].toDouble()
                if (lz <= 0.04 || lz > 4.0) continue
                val u = intr.fx * (lx / lz) + intr.cx
                val v = intr.fy * (ly / lz) + intr.cy
                val gx = (u * sx).toInt()
                val gy = (v * sy).toInt()
                if (gx !in 0 until gridW || gy !in 0 until gridH) continue
                var zMvs = zGrid[gy * gridW + gx].toDouble()
                if (!zMvs.isFinite()) {
                    // Check immediate 3x3 bin neighborhood (~4 px) to avoid bin-boundary quantization misses.
                    for (dy in -1..1) {
                        val ny = gy + dy
                        if (ny !in 0 until gridH) continue
                        val rowOff = ny * gridW
                        for (dx in -1..1) {
                            val nx = gx + dx
                            if (nx !in 0 until gridW) continue
                            val cand = zGrid[rowOff + nx].toDouble()
                            if (cand.isFinite() && (!zMvs.isFinite() || cand < zMvs)) zMvs = cand
                        }
                    }
                }
                if (!zMvs.isFinite() || zMvs <= 0.04) continue
                val ratio = lz / zMvs
                if (ratio in 0.05..20.0) ratios.add(ratio)
            }
            fi += frameStep
        }

        if (ratios.size < 30) return 1.0
        ratios.sort()
        val median = ratios[ratios.size / 2]
        val q25 = ratios[ratios.size / 4]
        val q75 = ratios[(ratios.size * 3) / 4]
        val relIqr = (q75 - q25) / max(1e-6, median)
        if (!median.isFinite() || median !in 0.05..20.0 || relIqr > 0.45) {
            runCatching { Log.w(TAG, "Laser scale rejected: median=$median relIqr=$relIqr n=${ratios.size}") }
            return 1.0
        }
        runCatching {
            Log.i(TAG, "Laser metric scale lock: scale=%.5f (relIqr=%.3f, n=%d)".format(median, relIqr, ratios.size))
        }
        return median
    }

    /**
     * Filters a world-space point cloud through U2Net object masks (`masks/<name>.png`) when
     * present. Points projected into up to 8 views are kept if at least half the views that see
     * them classify that pixel as foreground (> 127).
     */
    fun filterByMasks(
        scan: Scan,
        cloud: DenseCloud,
        colmapPoses: Map<String, CameraPose>,
        intr: Intrinsics,
        scale: Double,
    ): Pair<DenseCloud, Boolean> {
        val masksDir = File(scan.dir, "masks")
        if (!masksDir.isDirectory) return cloud to false
        data class MaskView(val pose: CameraPose, val mask: ByteArray, val w: Int, val h: Int)
        val candidates = colmapPoses.entries.sortedBy { it.key }
        val step = max(1, candidates.size / 10)
        val views = ArrayList<MaskView>()
        var idx = 0
        while (idx < candidates.size && views.size < 10) {
            val (name, pose) = candidates[idx]
            val stem = name.substringBeforeLast('.')
            val maskFile = File(masksDir, "$stem.png").takeIf { it.isFile }
                ?: File(masksDir, "$name.png").takeIf { it.isFile }
            if (maskFile != null) {
                val bmp = runCatching { BitmapFactory.decodeFile(maskFile.path) }.getOrNull()
                if (bmp != null) {
                    val w = bmp.width; val h = bmp.height
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    val m = ByteArray(w * h) { p -> if ((px[p] and 0xFF) >= 128) 1 else 0 }
                    views += MaskView(pose.scaled(scale), m, w, h)
                }
            }
            idx += step
        }
        if (views.isEmpty()) return cloud to false

        val keptXyz = FloatArray(cloud.count * 6)
        val keptRgb = ByteArray(cloud.count * 3)
        var kept = 0
        for (i in 0 until cloud.count) {
            val fo = i * 6
            val wx = cloud.xyzNxyz[fo].toDouble()
            val wy = cloud.xyzNxyz[fo + 1].toDouble()
            val wz = cloud.xyzNxyz[fo + 2].toDouble()
            var seen = 0
            var fg = 0
            for (mv in views) {
                val r = mv.pose.r; val t = mv.pose.t
                val cz = r[6] * wx + r[7] * wy + r[8] * wz + t[2]
                if (cz <= 0.02) continue
                val cx = r[0] * wx + r[1] * wy + r[2] * wz + t[0]
                val cy = r[3] * wx + r[4] * wy + r[5] * wz + t[1]
                val u = intr.fx * (cx / cz) + intr.cx
                val v = intr.fy * (cy / cz) + intr.cy
                val mu = (u * mv.w / max(1, intr.width)).toInt()
                val mvY = (v * mv.h / max(1, intr.height)).toInt()
                if (mu in 0 until mv.w && mvY in 0 until mv.h) {
                    seen++
                    if (mv.mask[mvY * mv.w + mu].toInt() != 0) fg++
                }
            }
            if (seen == 0 || fg * 2 >= seen) {
                val ko = kept * 6
                for (k in 0 until 6) keptXyz[ko + k] = cloud.xyzNxyz[fo + k]
                val co = i * 3; val kco = kept * 3
                keptRgb[kco] = cloud.rgb[co]
                keptRgb[kco + 1] = cloud.rgb[co + 1]
                keptRgb[kco + 2] = cloud.rgb[co + 2]
                kept++
            }
        }
        if (kept < 100) return cloud to false
        Log.i(TAG, "Mask filter kept $kept / ${cloud.count} dense MVS points across ${views.size} views")
        return DenseCloud(
            xyzNxyz = keptXyz.copyOf(kept * 6),
            rgb = keptRgb.copyOf(kept * 3),
            count = kept,
            hasNormals = cloud.hasNormals,
        ) to true
    }

    /** Runs full Laser + Dense MVS post-processing and exports STL, mesh PLY, and merged cloud. */
    fun fuse(
        scan: Scan,
        splatsDir: File,
        preferredVoxelMm: Float = 1.0f,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Result {
        onProgress(0.05f, "Loading bundle-adjusted poses…")
        val poses = readColmapPoses(File(scan.dir, "sparse/0/images.txt"))
            ?: error("Scan has not been reconstructed yet (missing sparse/0/images.txt)")
        val intr = readPrimaryIntrinsics(scan)
            ?: error("Missing camera intrinsics for scan")

        onProgress(0.15f, "Reading dense MVS point cloud…")
        val rawDense = readParallaxPly(scan.dense)
            ?: readParallaxPly(scan.sparse)
            ?: error("Missing dense.ply and sparse.ply")

        onProgress(0.28f, "Aligning laser frames with BA poses…")
        val unscaledLaser = loadBaLaserFramesTyped(scan, poses, 1.0)
        val laserScale = if (unscaledLaser.isNotEmpty()) {
            estimateLaserScale(unscaledLaser, rawDense.xyzNxyz, 6, rawDense.count, intr)
        } else 1.0

        val scaledDense = if (abs(laserScale - 1.0) > 1e-4) {
            val s = laserScale.toFloat()
            val arr = rawDense.xyzNxyz.copyOf()
            for (i in 0 until rawDense.count) {
                arr[i * 6] *= s; arr[i * 6 + 1] *= s; arr[i * 6 + 2] *= s
            }
            rawDense.copy(xyzNxyz = arr)
        } else rawDense

        onProgress(0.40f, "Applying object masks…")
        val (filteredDense, maskFiltered) = filterByMasks(scan, scaledDense, poses, intr, laserScale)
        val laserFrames = if (unscaledLaser.isNotEmpty()) {
            if (abs(laserScale - 1.0) > 1e-4) loadBaLaserFramesTyped(scan, poses, laserScale) else unscaledLaser
        } else emptyList()

        val exports = File(scan.dir, "exports").apply { mkdirs() }
        var laserRefinedPly: File? = null
        val worldLaserXyz = ArrayList<Float>()
        val worldLaserRgb = ArrayList<Byte>()

        if (laserFrames.isNotEmpty()) {
            onProgress(0.50f, "Re-projecting ${laserFrames.size} laser frames…")
            val bitmaps = HashMap<String, Bitmap?>()
            for (lf in laserFrames) {
                val r = lf.pose.r; val t = lf.pose.t
                val bmp = lf.keyframeFile?.let { kf ->
                    bitmaps.getOrPut(kf.path) { runCatching { BitmapFactory.decodeFile(kf.path) }.getOrNull() }
                }
                val kp = lf.keyframePose
                val pts = lf.pointsCamM
                val n = pts.size / 3
                for (i in 0 until n) {
                    val px = pts[i * 3].toDouble()
                    val py = pts[i * 3 + 1].toDouble()
                    val pz = pts[i * 3 + 2].toDouble()
                    val dx = px - t[0]; val dy = py - t[1]; val dz = pz - t[2]
                    val wx = (r[0] * dx + r[3] * dy + r[6] * dz).toFloat()
                    val wy = (r[1] * dx + r[4] * dy + r[7] * dz).toFloat()
                    val wz = (r[2] * dx + r[5] * dy + r[8] * dz).toFloat()
                    if (!wx.isFinite() || !wy.isFinite() || !wz.isFinite()) continue
                    worldLaserXyz.add(wx); worldLaserXyz.add(wy); worldLaserXyz.add(wz)
                    var rgbInt = 0xD8D8D8
                    if (bmp != null && kp != null) {
                        val kr = kp.r; val kt = kp.t
                        val cz = kr[6] * wx + kr[7] * wy + kr[8] * wz + kt[2]
                        if (cz > 0.02) {
                            val cx = kr[0] * wx + kr[1] * wy + kr[2] * wz + kt[0]
                            val cy = kr[3] * wx + kr[4] * wy + kr[5] * wz + kt[1]
                            val u = ((intr.fx * cx / cz + intr.cx) * bmp.width / max(1, intr.width)).roundToInt()
                            val v = ((intr.fy * cy / cz + intr.cy) * bmp.height / max(1, intr.height)).roundToInt()
                            if (u in 0 until bmp.width && v in 0 until bmp.height) rgbInt = bmp.getPixel(u, v)
                        }
                    }
                    worldLaserRgb.add(((rgbInt shr 16) and 0xFF).toByte())
                    worldLaserRgb.add(((rgbInt shr 8) and 0xFF).toByte())
                    worldLaserRgb.add((rgbInt and 0xFF).toByte())
                }
            }
            for (b in bitmaps.values) b?.recycle()
            if (worldLaserXyz.isNotEmpty()) {
                val outLaser = File(exports, "laser-ba-refined.ply")
                writeColoredCloudPly(outLaser, worldLaserXyz, worldLaserRgb, toMm = true)
                laserRefinedPly = outLaser
            }
        }

        // Write laser-only .splat preview (if laser points exist) + merged cloud preview.
        val laserSplat = laserRefinedPly?.let { ply ->
            val dst = File(splatsDir.apply { mkdirs() }, "laser-ba-${scan.name}.splat")
            if (runCatching { SplatExporter.convert(ply, dst, voxelMm = 0f) > 0 }.getOrDefault(false)) dst else null
        }

        // Write merged laser + dense MVS point cloud (in mm) + CloudView .splat preview.
        onProgress(0.60f, "Writing fused point cloud…")
        val cloudPly = File(exports, "fused-cloud.ply")
        writeFusedCloudPly(cloudPly, worldLaserXyz, worldLaserRgb, filteredDense, toMm = true)
        val previewSplat = File(splatsDir.apply { mkdirs() }, "fused-${scan.name}.splat")
        val splatOk = runCatching { SplatExporter.convert(cloudPly, previewSplat, voxelMm = 0f) > 0 }.getOrDefault(false)

        // Choose voxel size that fits the object cleanly inside 131,072 sparse 8^3 blocks (~1.0 mm up to ~45 cm).
        val spanM = robustSpanMetres(filteredDense, worldLaserXyz)
        val voxelMm = max(preferredVoxelMm, (spanM * 1000.0 / 450.0).toFloat()).coerceIn(0.75f, 12.0f)
        val voxelM = voxelMm * 0.001f
        val maxDepthM = max(4.0, spanM * 3.0)

        onProgress(0.70f, "Fusing into native TSDF (${String.format("%.1f", voxelMm)} mm voxels)…")
        val stlFile = File(exports, "fused-mesh.stl")
        val meshPlyFile = File(exports, "fused-mesh.ply")
        var triangles = 0
        if (NativeLiveTsdf.available) {
            val handle = NativeLiveTsdf.nativeCreate(voxelM, voxelM * 4.0f, 131072, maxDepthM)
            try {
                // Pass 1: BA-tightened laser sweeps (geometry authority, high weight = 12).
                for (lf in laserFrames) {
                    NativeLiveTsdf.nativeIntegrateLaser(handle, lf.pose.r, lf.pose.t, lf.pointsCamM, 12.0f)
                }
                // Pass 2: Dense MVS oriented point cloud (gap fill + color, yields to laser voxels).
                onProgress(0.82f, "Integrating ${filteredDense.count} MVS points…")
                if (filteredDense.count > 0 && filteredDense.hasNormals) {
                    NativeLiveTsdf.nativeIntegrateOrientedPoints(
                        handle, filteredDense.xyzNxyz, filteredDense.rgb, filteredDense.count, 1.5f,
                    )
                }
                onProgress(0.92f, "Extracting sharp-edge Dual Contouring mesh…")
                var stlTris = NativeLiveTsdf.nativeWriteMeshStlDualContouring(handle, stlFile.absolutePath, true)
                var plyTris = NativeLiveTsdf.nativeWriteMeshPlyDualContouring(handle, meshPlyFile.absolutePath, true)
                if (stlTris <= 0) {
                    stlTris = NativeLiveTsdf.nativeWriteMeshStl(handle, stlFile.absolutePath, true)
                }
                if (plyTris <= 0) {
                    NativeLiveTsdf.nativeWriteMeshPly(handle, meshPlyFile.absolutePath, true)
                }
                triangles = max(0, stlTris)
            } finally {
                NativeLiveTsdf.nativeDestroy(handle)
            }
        }

        onProgress(1.0f, "Fusion complete")
        val laserPtCount = worldLaserXyz.size / 3
        val vioAnchoredCount = laserFrames.count { it.vioAnchored }
        Log.i(
            TAG,
            "fused scan=${scan.name} laserPts=$laserPtCount mvsPts=${filteredDense.count} " +
                "vioAnchored=$vioAnchoredCount/${laserFrames.size} " +
                "scale=%.4f voxel=%.2fmm tris=$triangles masked=$maskFiltered".format(laserScale, voxelMm),
        )
        return Result(
            stlFile = stlFile,
            meshPlyFile = meshPlyFile.takeIf { it.isFile && it.length() > 0L },
            cloudPlyFile = cloudPly,
            laserRefinedPlyFile = laserRefinedPly,
            previewSplatFile = previewSplat.takeIf { splatOk && it.isFile },
            laserSplatFile = laserSplat,
            triangles = triangles,
            laserPoints = laserPtCount,
            mvsPoints = filteredDense.count,
            laserScale = laserScale,
            voxelMm = voxelMm,
            maskFiltered = maskFiltered,
            vioAnchoredFrames = vioAnchoredCount,
        )
    }

    /** Public typed wrapper used by both [fuse] and [LaserInit]. */
    fun loadBaLaserFramesTyped(
        scan: Scan,
        colmapPoses: Map<String, CameraPose>,
        scale: Double = 1.0,
        vioToPoseScale: Double = 1.0,
    ): List<LaserFrame> {
        val hybrid = File(scan.dir, "hybrid/hybrid.json")
        if (!hybrid.isFile) return emptyList()
        val doc = runCatching { JSONObject(hybrid.readText()) }.getOrNull() ?: return emptyList()
        val images = doc.optJSONArray("images") ?: return emptyList()
        val frames = doc.optJSONArray("laser_frames") ?: images

        fun parseRowLocalPose(row: JSONObject): CameraPose? =
            parsePose16String(row.optString("vio_pose16", ""), 1.0)
                ?: parsePose16String(row.optString("board_pose16", ""), 0.001)

        data class KeyPose(
            val frame: Int,
            val ts: Long,
            val name: String,
            val file: File,
            val pose: CameraPose,
            val vioPose: CameraPose?,
        )
        val keyPoses = ArrayList<KeyPose>()
        for (k in 0 until images.length()) {
            val row = images.optJSONObject(k) ?: continue
            val name = row.optString("file", "")
            val pose = colmapPoses[name] ?: continue
            val frame = row.optInt("frame", -1)
            val ts = row.optLong("sensor_timestamp_ns", 0L)
            if (frame >= 0) {
                keyPoses += KeyPose(
                    frame = frame,
                    ts = ts,
                    name = name,
                    file = File(scan.imagesDir, name),
                    pose = pose.scaled(scale),
                    vioPose = parseRowLocalPose(row),
                )
            }
        }
        if (keyPoses.isEmpty()) return emptyList()
        keyPoses.sortBy { it.frame }
        val byFrame = keyPoses.associateBy { it.frame }

        val result = ArrayList<LaserFrame>()
        for (k in 0 until frames.length()) {
            val row = frames.optJSONObject(k) ?: continue
            val rel = row.optString("laser", "")
            if (rel.isEmpty()) continue
            val laserFile = File(scan.dir, rel)
            if (!laserFile.isFile || laserFile.length() < 12L) continue
            val frame = row.optInt("frame", -1)
            val ts = row.optLong("sensor_timestamp_ns", 0L)
            val frameVio = parseRowLocalPose(row)
            val resolved = resolvePoseForFrame(
                frame = frame,
                ts = ts,
                frameVio = frameVio,
                vioToPoseScale = vioToPoseScale,
                sorted = keyPoses,
                byFrame = byFrame,
                frameOf = { (it as KeyPose).frame },
                tsOf = { (it as KeyPose).ts },
                fileOf = { (it as KeyPose).file },
                poseOf = { (it as KeyPose).pose },
                vioOf = { (it as KeyPose).vioPose },
            ) ?: continue

            val raw = laserFile.readBytes()
            val n = raw.size / 12
            if (n <= 0) continue
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val camM = FloatArray(n * 3)
            var kept = 0
            for (i in 0 until n) {
                val x = bb.getFloat() * 0.001f
                val y = bb.getFloat() * 0.001f
                val z = bb.getFloat() * 0.001f
                if (!x.isFinite() || !y.isFinite() || !z.isFinite() || z <= 0.03f || z > 5.0f) continue
                camM[kept * 3] = x
                camM[kept * 3 + 1] = y
                camM[kept * 3 + 2] = z
                kept++
            }
            if (kept == 0) continue
            result += LaserFrame(
                frame = frame,
                timestampNs = ts,
                pose = resolved.pose,
                pointsCamM = if (kept == n) camM else camM.copyOf(kept * 3),
                keyframeFile = resolved.colorFile.takeIf { it.isFile },
                keyframePose = resolved.colorPose,
                vioAnchored = resolved.vioAnchored,
            )
        }
        return result
    }

    fun readPrimaryIntrinsics(scan: Scan): Intrinsics? {
        val camTxt = File(scan.dir, "sparse/0/cameras.txt")
        if (camTxt.isFile) {
            camTxt.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val s = line.trim()
                    if (s.isEmpty() || s.startsWith("#")) continue
                    val p = s.split(Regex("\\s+"))
                    if (p.size < 8) continue
                    val w = p[2].toIntOrNull() ?: continue
                    val h = p[3].toIntOrNull() ?: continue
                    val fx = p[4].toDoubleOrNull() ?: continue
                    val fy = p[5].toDoubleOrNull() ?: continue
                    val cx = p[6].toDoubleOrNull() ?: continue
                    val cy = p[7].toDoubleOrNull() ?: continue
                    return Intrinsics(w, h, fx, fy, cx, cy, 0.0, 0.0, 0.0, 0.0, 0.0, true)
                }
            }
        }
        return scan.load()?.cameras?.firstOrNull()?.intr
    }

    private fun robustSpanMetres(dense: DenseCloud, laserXyz: List<Float>): Double {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val zs = ArrayList<Float>()
        if (laserXyz.size >= 30) {
            val step = max(1, (laserXyz.size / 3) / 5000)
            var i = 0
            while (i * 3 + 2 < laserXyz.size) {
                xs.add(laserXyz[i * 3]); ys.add(laserXyz[i * 3 + 1]); zs.add(laserXyz[i * 3 + 2])
                i += step
            }
        } else if (dense.count > 0) {
            val step = max(1, dense.count / 5000)
            var i = 0
            while (i < dense.count) {
                xs.add(dense.xyzNxyz[i * 6]); ys.add(dense.xyzNxyz[i * 6 + 1]); zs.add(dense.xyzNxyz[i * 6 + 2])
                i += step
            }
        }
        if (xs.size < 4) return 0.3
        xs.sort(); ys.sort(); zs.sort()
        val lo = (xs.size * 0.05).toInt()
        val hi = min(xs.size - 1, (xs.size * 0.95).toInt())
        val span = max(xs[hi] - xs[lo], max(ys[hi] - ys[lo], zs[hi] - zs[lo])).toDouble()
        return span.coerceIn(0.05, 5.0)
    }

    private fun writeColoredCloudPly(out: File, xyz: List<Float>, rgb: List<Byte>, toMm: Boolean) {
        val n = xyz.size / 3
        val s = if (toMm) 1000f else 1f
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
                chunk.putFloat(xyz[i * 3] * s)
                chunk.putFloat(xyz[i * 3 + 1] * s)
                chunk.putFloat(xyz[i * 3 + 2] * s)
                chunk.put(rgb[i * 3])
                chunk.put(rgb[i * 3 + 1])
                chunk.put(rgb[i * 3 + 2])
            }
            if (chunk.position() > 0) os.write(chunk.array(), 0, chunk.position())
        }
    }

    private fun writeFusedCloudPly(
        out: File,
        laserXyz: List<Float>,
        laserRgb: List<Byte>,
        dense: DenseCloud,
        toMm: Boolean,
    ) {
        val nLaser = laserXyz.size / 3
        val total = nLaser + dense.count
        val s = if (toMm) 1000f else 1f
        val hdr = "ply\nformat binary_little_endian 1.0\n" +
            "element vertex $total\n" +
            "property float x\nproperty float y\nproperty float z\n" +
            "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n"
        FileOutputStream(out).use { os ->
            os.write(hdr.toByteArray(Charsets.US_ASCII))
            val chunk = ByteBuffer.allocate(4096 * 15).order(ByteOrder.LITTLE_ENDIAN)
            fun flushIfNeeded() {
                if (chunk.remaining() < 15) {
                    os.write(chunk.array(), 0, chunk.position())
                    chunk.clear()
                }
            }
            for (i in 0 until nLaser) {
                flushIfNeeded()
                chunk.putFloat(laserXyz[i * 3] * s)
                chunk.putFloat(laserXyz[i * 3 + 1] * s)
                chunk.putFloat(laserXyz[i * 3 + 2] * s)
                chunk.put(laserRgb[i * 3])
                chunk.put(laserRgb[i * 3 + 1])
                chunk.put(laserRgb[i * 3 + 2])
            }
            for (i in 0 until dense.count) {
                flushIfNeeded()
                val fo = i * 6; val co = i * 3
                chunk.putFloat(dense.xyzNxyz[fo] * s)
                chunk.putFloat(dense.xyzNxyz[fo + 1] * s)
                chunk.putFloat(dense.xyzNxyz[fo + 2] * s)
                chunk.put(dense.rgb[co])
                chunk.put(dense.rgb[co + 1])
                chunk.put(dense.rgb[co + 2])
            }
            if (chunk.position() > 0) os.write(chunk.array(), 0, chunk.position())
        }
    }

    private fun indexOfBytes(hay: ByteArray, needle: ByteArray): Int {
        val limit = min(hay.size - needle.size, 8192)
        outer@ for (i in 0..limit) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
