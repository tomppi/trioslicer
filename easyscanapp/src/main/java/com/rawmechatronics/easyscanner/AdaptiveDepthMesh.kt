package com.rawmechatronics.easyscanner

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Calibrated camera intrinsics + Brown-Conrady distortion matching
 * `easyscan-core/algo/src/geom.rs`, with 2D Gauss-Newton undistortion and
 * smooth radial continuation past polynomial fold-back corners.
 */
data class DepthCamera(
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val width: Int, val height: Int,
    val k1: Double = 0.0, val k2: Double = 0.0,
    val p1: Double = 0.0, val p2: Double = 0.0, val k3: Double = 0.0,
) {
    private val maxMonotonicR: Double by lazy {
        if (k1 == 0.0 && k2 == 0.0 && k3 == 0.0) return@lazy 2.0
        var best = 0.0
        var r = 0.01
        while (r <= 2.0) {
            val r2 = r * r
            val drd = 1.0 + r2 * (3.0 * k1 + r2 * (5.0 * k2 + r2 * 7.0 * k3))
            if (drd <= 0.15) break
            best = r
            r += 0.01
        }
        best.coerceAtLeast(0.1)
    }

    private val maxInvertibleRd: Double by lazy {
        val r = maxMonotonicR
        val r2 = r * r
        r * (1.0 + r2 * (k1 + r2 * (k2 + r2 * k3)))
    }

    fun isInvertible(u: Double, v: Double): Boolean {
        if (k1 == 0.0 && k2 == 0.0 && p1 == 0.0 && p2 == 0.0 && k3 == 0.0) return true
        val x0 = (u - cx) / fx
        val y0 = (v - cy) / fy
        return hypot(x0, y0) <= maxInvertibleRd * 0.98
    }

    fun project(xc: Double, yc: Double, zc: Double): DoubleArray? {
        if (!xc.isFinite() || !yc.isFinite() || !zc.isFinite() || zc <= 1e-6) return null
        val x = xc / zc
        val y = yc / zc
        val r2 = x * x + y * y
        if (!r2.isFinite() || r2 > 4.0) return null
        val radial = 1.0 + r2 * (k1 + r2 * (k2 + r2 * k3))
        val xd = x * radial + 2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x)
        val yd = y * radial + p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y
        val u = fx * xd + cx
        val v = fy * yd + cy
        return if (u.isFinite() && v.isFinite()) doubleArrayOf(u, v) else null
    }

    fun undistort(u: Double, v: Double): DoubleArray {
        val x0 = (u - cx) / fx
        val y0 = (v - cy) / fy
        if (k1 == 0.0 && k2 == 0.0 && p1 == 0.0 && p2 == 0.0 && k3 == 0.0) {
            return doubleArrayOf(x0, y0)
        }
        val r0 = hypot(x0, y0)
        val rLim = maxMonotonicR
        val rdLim = maxInvertibleRd
        if (r0 > rdLim && rdLim > 1e-6) {
            val r2 = rLim * rLim
            val slope = (1.0 + r2 * (3.0 * k1 + r2 * (5.0 * k2 + r2 * 7.0 * k3))).coerceAtLeast(0.15)
            val rOut = rLim + (r0 - rdLim) / slope
            val s = rOut / r0
            return doubleArrayOf(x0 * s, y0 * s)
        }
        var x = x0
        var y = y0
        repeat(12) {
            val r2 = x * x + y * y
            val rad = 1.0 + r2 * (k1 + r2 * (k2 + r2 * k3))
            val dRadDr2 = k1 + r2 * (2.0 * k2 + r2 * 3.0 * k3)
            val fxv = x * rad + 2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x) - x0
            val fyv = y * rad + p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y - y0
            if (fxv * fxv + fyv * fyv < 1e-22) return doubleArrayOf(x, y)
            val j00 = rad + 2.0 * x * x * dRadDr2 + 2.0 * p1 * y + 6.0 * p2 * x
            val j01 = 2.0 * x * y * dRadDr2 + 2.0 * p1 * x + 2.0 * p2 * y
            val j10 = j01
            val j11 = rad + 2.0 * y * y * dRadDr2 + 2.0 * p2 * x + 6.0 * p1 * y
            val det = j00 * j11 - j01 * j10
            if (abs(det) < 1e-12 || !det.isFinite()) return@repeat
            var nx = x - (j11 * fxv - j01 * fyv) / det
            var ny = y - (-j10 * fxv + j00 * fyv) / det
            val nr = hypot(nx, ny)
            if (nr > rLim) { nx *= rLim / nr; ny *= rLim / nr }
            x = nx; y = ny
        }
        return doubleArrayOf(x, y)
    }
}

/** World-to-camera rigid transform: `x_cam = R * x_world + t`, [r] 3x3 row-major, [t] metres. */
data class RigidPose(val r: DoubleArray, val t: DoubleArray) {
    fun cameraToWorld(xc: Double, yc: Double, zc: Double): DoubleArray {
        val dx = xc - t[0]
        val dy = yc - t[1]
        val dz = zc - t[2]
        return doubleArrayOf(
            r[0] * dx + r[3] * dy + r[6] * dz,
            r[1] * dx + r[4] * dy + r[7] * dz,
            r[2] * dx + r[5] * dy + r[8] * dz,
        )
    }

    fun worldToCamera(wx: Double, wy: Double, wz: Double): DoubleArray = doubleArrayOf(
        r[0] * wx + r[1] * wy + r[2] * wz + t[0],
        r[3] * wx + r[4] * wy + r[5] * wz + t[1],
        r[6] * wx + r[7] * wy + r[8] * wz + t[2],
    )

    fun cameraCentre(): DoubleArray = cameraToWorld(0.0, 0.0, 0.0)

    companion object {
        fun fromRowMajor16(v: DoubleArray, translationScale: Double = 1.0): RigidPose? {
            if (v.size < 16) return null
            for (i in 0 until 12) if (!v[i].isFinite()) return null
            val r = doubleArrayOf(v[0], v[1], v[2], v[4], v[5], v[6], v[8], v[9], v[10])
            val det = r[0] * (r[4] * r[8] - r[5] * r[7]) -
                r[1] * (r[3] * r[8] - r[5] * r[6]) +
                r[2] * (r[3] * r[7] - r[4] * r[6])
            if (!det.isFinite() || det < 0.95 || det > 1.05) return null
            for (i in 0 until 3) for (j in 0 until 3) {
                val dot = r[i] * r[j] + r[i + 3] * r[j + 3] + r[i + 6] * r[j + 6]
                val expected = if (i == j) 1.0 else 0.0
                if (abs(dot - expected) > 0.05) return null
            }
            return RigidPose(r, doubleArrayOf(v[3] * translationScale, v[7] * translationScale, v[11] * translationScale))
        }

        fun fromFloat16(v: FloatArray, offset: Int = 0, translationScale: Double = 1.0): RigidPose? {
            if (v.size < offset + 16) return null
            val d = DoubleArray(16) { v[offset + it].toDouble() }
            return fromRowMajor16(d, translationScale)
        }
    }
}

/**
 * Two-level watertight adaptive surface mesh seeded by a single-frame Depth-Anything-3
 * depth map and refined at 4x linear (16x area) resolution wherever the metric laser
 * stripe sweeps over it.
 *
 * - Unswept regions stay as coarse 4x4 blocks (129x129 grid, ~5 mm facets at 0.5 m).
 * - Any block touched by metric laser observations subdivides into full 518x518 1x1 quads
 *   (~1.2 mm facets) whose vertices snap onto the weighted metric laser surface.
 * - Coarse blocks adjacent to refined blocks emit a centre-fan stitched to the refined
 *   neighbour's exact boundary vertices so there are zero T-junction cracks.
 */
class AdaptiveDepthMesh(
    val refPose: RigidPose,
    val camera: DepthCamera,
    rawDepth: FloatArray,
    val scale: Double,
    val offset: Double = 0.0,
    rgb518: ByteArray,
    validMask518: BooleanArray? = null,
) {
    data class LaserStats(
        val acceptedPoints: Int,
        val rejectedOutliers: Int,
        val rejectedOutOfBounds: Int,
        val newlyRefinedBlocks: Int,
        val totalRefinedBlocks: Int,
        val totalLaserCells: Int,
    )

    class MeshData(
        /** Packed xyz in world metres, length = vertexCount * 3. */
        val positions: FloatArray,
        /** Unit vertex normals oriented toward the reference camera, length = vertexCount * 3. */
        val normals: FloatArray,
        /** Packed RGB uint8, length = vertexCount * 3. */
        val colors: ByteArray,
        /** 0f = coarse DA3 base, 0..1f = laser refinement strength. */
        val refinedWeight: FloatArray,
        val vertexCount: Int,
        /** Triangle vertex indices (3 per triangle). */
        val indices: IntArray,
        val refinedBlocks: Int,
        val totalBlocks: Int,
        val laserCells: Int,
        val totalLaserHits: Int,
        val boundsMin: FloatArray,
        val boundsMax: FloatArray,
    ) {
        val triangleCount: Int get() = indices.size / 3
    }

    private val lock = Any()
    private val baseDepthM = FloatArray(SIZE * SIZE) { Float.NaN }
    private val rgb = rgb518.copyOf(SIZE * SIZE * 3)
    private val validMask = validMask518?.copyOf(SIZE * SIZE) ?: BooleanArray(SIZE * SIZE) { true }
    private val laserSum = FloatArray(SIZE * SIZE)
    private val laserWeight = FloatArray(SIZE * SIZE)
    private val directHit = BooleanArray(SIZE * SIZE)
    private val blockRefined = BooleanArray(COARSE * COARSE)

    private var totalLaserHits = 0
    private var totalLaserCells = 0
    private var totalRefinedBlocks = 0
    private var acceptedFrames = 0
    private var version = 1L

    // Precomputed normalised camera rays for the 518x518 grid (saves ~268k Gauss-Newton calls per rebuild).
    private val rayX = FloatArray(SIZE * SIZE)
    private val rayY = FloatArray(SIZE * SIZE)

    init {
        require(rawDepth.size >= SIZE * SIZE) { "rawDepth must be at least ${SIZE * SIZE}" }
        require(rgb518.size >= SIZE * SIZE * 3) { "rgb518 must be at least ${SIZE * SIZE * 3}" }
        for (y in 0 until SIZE) {
            val vf = (y + 0.5) * camera.height / SIZE
            val row = y * SIZE
            for (x in 0 until SIZE) {
                val uf = (x + 0.5) * camera.width / SIZE
                val ray = camera.undistort(uf, vf)
                rayX[row + x] = ray[0].toFloat()
                rayY[row + x] = ray[1].toFloat()
                val d = rawDepth[row + x]
                if (d > 0f && d.isFinite()) {
                    val z = d * scale + offset
                    if (z > 0.02 && z < 50.0) baseDepthM[row + x] = z.toFloat()
                }
            }
        }
    }

    fun currentVersion(): Long = synchronized(lock) { version }
    fun acceptedFrameCount(): Int = synchronized(lock) { acceptedFrames }
    fun refinedBlockCount(): Int = synchronized(lock) { totalRefinedBlocks }
    fun laserCellCount(): Int = synchronized(lock) { totalLaserCells }
    fun totalLaserHitCount(): Int = synchronized(lock) { totalLaserHits }

    /** Effective metric depth at grid pixel `(x, y)`: laser dominates wherever measured. */
    private fun effectiveDepth(idx: Int): Float {
        val base = baseDepthM[idx]
        val lw = laserWeight[idx]
        if (lw <= 0f) return base
        val laserAvg = laserSum[idx] / lw
        if (!base.isFinite()) return laserAvg
        // Small DA3 prior weight (0.08) lets even 1 direct laser pass snap 93% to exact metric laser
        // while keeping the 1-px halo smooth at the boundary.
        return (base * BASE_PRIOR_WEIGHT + laserSum[idx]) / (BASE_PRIOR_WEIGHT + lw)
    }

    /**
     * Projects one frame's camera-frame metric laser points (`[x,y,z]` metres) into
     * this mesh using [framePose], updating fine 518x518 depth cells and marking touched
     * 4x4 blocks for high-resolution subdivision.
     *
     * Returns world-space accepted laser points `[x,y,z,...]` alongside update counters.
     */
    fun addLaserFrame(
        framePose: RigidPose,
        laserCamM: FloatArray,
        maxDeviationFraction: Double = 0.18,
        maxDeviationM: Double = 0.08,
        worldPointsOut: FloatArray? = null,
    ): Pair<LaserStats, FloatArray> = synchronized(lock) {
        val n = laserCamM.size / 3
        if (n == 0) {
            return@synchronized LaserStats(0, 0, 0, 0, totalRefinedBlocks, totalLaserCells) to FloatArray(0)
        }
        val worldBuf = if (worldPointsOut != null && worldPointsOut.size >= n * 3) worldPointsOut else FloatArray(n * 3)
        var wCount = 0
        var accepted = 0
        var outliers = 0
        var oob = 0
        var newBlocks = 0

        var i = 0
        while (i + 2 < laserCamM.size) {
            val lx = laserCamM[i].toDouble()
            val ly = laserCamM[i + 1].toDouble()
            val lz = laserCamM[i + 2].toDouble()
            i += 3
            if (!lx.isFinite() || !ly.isFinite() || !lz.isFinite() || lz <= 0.02 || lz > 20.0) {
                oob++; continue
            }
            val world = framePose.cameraToWorld(lx, ly, lz)
            val ref = refPose.worldToCamera(world[0], world[1], world[2])
            val zRef = ref[2]
            if (!zRef.isFinite() || zRef <= 0.02 || zRef > 20.0) { oob++; continue }
            val uv = camera.project(ref[0], ref[1], zRef)
            if (uv == null || uv[0] < 0.0 || uv[0] >= camera.width || uv[1] < 0.0 || uv[1] >= camera.height) {
                oob++; continue
            }
            val ix = (uv[0] * SIZE / camera.width).toInt().coerceIn(0, SIZE - 1)
            val iy = (uv[1] * SIZE / camera.height).toInt().coerceIn(0, SIZE - 1)
            val idx = iy * SIZE + ix
            if (!validMask[idx]) { oob++; continue }
            val base = baseDepthM[idx]
            if (base.isFinite()) {
                val tol = max(maxDeviationM, maxDeviationFraction * base.toDouble())
                if (abs(zRef - base.toDouble()) > tol) {
                    outliers++; continue
                }
            }

            val zf = zRef.toFloat()
            // Cap accumulated weight so later cleaner laser passes still refine earlier ones.
            val prevW = laserWeight[idx]
            if (!directHit[idx]) {
                // Replace any halo-only estimate cleanly with the direct measurement.
                laserSum[idx] = zf
                laserWeight[idx] = 1f
                directHit[idx] = true
                totalLaserCells++
            } else if (prevW < MAX_CELL_WEIGHT) {
                laserSum[idx] += zf
                laserWeight[idx] = prevW + 1f
            } else {
                val avg = laserSum[idx] / prevW
                val updated = avg * (MAX_CELL_WEIGHT - 1f) + zf
                laserSum[idx] = updated
                laserWeight[idx] = MAX_CELL_WEIGHT
            }

            // Gentle 4-neighbor transition halo so refined ridges blend smoothly into adjacent DA3 cells.
            addHalo(ix - 1, iy, zf)
            addHalo(ix + 1, iy, zf)
            addHalo(ix, iy - 1, zf)
            addHalo(ix, iy + 1, zf)

            // Mark containing 4x4 block (and neighbor if on the exact seam) for fine subdivision.
            newBlocks += markBlock(ix / BLOCK, iy / BLOCK)
            if (ix % BLOCK == 0 && ix > 0) newBlocks += markBlock(ix / BLOCK - 1, iy / BLOCK)
            if (iy % BLOCK == 0 && iy > 0) newBlocks += markBlock(ix / BLOCK, iy / BLOCK - 1)

            worldBuf[wCount * 3] = world[0].toFloat()
            worldBuf[wCount * 3 + 1] = world[1].toFloat()
            worldBuf[wCount * 3 + 2] = world[2].toFloat()
            wCount++
            accepted++
            totalLaserHits++
        }

        if (accepted > 0) {
            acceptedFrames++
            version++
        }
        val trimmed = if (wCount * 3 == worldBuf.size) worldBuf else worldBuf.copyOf(wCount * 3)
        LaserStats(accepted, outliers, oob, newBlocks, totalRefinedBlocks, totalLaserCells) to trimmed
    }

    private fun addHalo(x: Int, y: Int, zf: Float) {
        if (x !in 0 until SIZE || y !in 0 until SIZE) return
        val idx = y * SIZE + x
        if (directHit[idx] || !validMask[idx]) return
        val base = baseDepthM[idx]
        if (base.isFinite() && abs(zf - base) > max(0.04f, 0.08f * base)) return
        if (laserWeight[idx] < 0.8f) {
            laserSum[idx] += zf * HALO_WEIGHT
            laserWeight[idx] += HALO_WEIGHT
        }
    }

    private fun markBlock(bx: Int, by: Int): Int {
        if (bx !in 0 until COARSE || by !in 0 until COARSE) return 0
        val bIdx = by * COARSE + bx
        if (blockRefined[bIdx]) return 0
        blockRefined[bIdx] = true
        totalRefinedBlocks++
        return 1
    }

    /** Builds a watertight, normal-shaded adaptive mesh snapshot. */
    /** Metric 3D point in this patch's camera frame at a full-resolution camera pixel. */
    fun metricPointAt(u: Double, v: Double): DoubleArray? = synchronized(lock) {
        // baseDepthM is already scaled to metric metres in init (d * scale + offset); never scale twice.
        val z = sampleDepthBilinear(baseDepthM, u, v, camera).toDouble()
        if (!z.isFinite() || z <= 0.08 || z > 15.0) return@synchronized null
        val ray = camera.undistort(u, v)
        doubleArrayOf(ray[0] * z, ray[1] * z, z)
    }

    fun buildMesh(): MeshData = synchronized(lock) {
        val vertIndex = IntArray(SIZE * SIZE) { -1 }
        val effDepth = FloatArray(SIZE * SIZE) { Float.NaN }
        var vCount = 0
        var capVerts = COARSE * COARSE + totalRefinedBlocks * 20 + 1024
        var pos = FloatArray(capVerts * 3)
        var col = ByteArray(capVerts * 3)
        var refW = FloatArray(capVerts)
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY

        fun ensureVertex(x: Int, y: Int): Int {
            val idx = y * SIZE + x
            val existing = vertIndex[idx]
            if (existing >= 0) return existing
            if (!validMask[idx]) return -1
            val z = effectiveDepth(idx)
            if (!z.isFinite() || z <= 0.02f || z > 50f) return -1
            val xc = rayX[idx].toDouble() * z
            val yc = rayY[idx].toDouble() * z
            val w = refPose.cameraToWorld(xc, yc, z.toDouble())
            if (!w[0].isFinite() || !w[1].isFinite() || !w[2].isFinite()) return -1
            if (vCount >= capVerts) {
                capVerts = (capVerts * 3) / 2 + 1024
                pos = pos.copyOf(capVerts * 3)
                col = col.copyOf(capVerts * 3)
                refW = refW.copyOf(capVerts)
            }
            val k = vCount++
            vertIndex[idx] = k
            effDepth[idx] = z
            val wx = w[0].toFloat(); val wy = w[1].toFloat(); val wz = w[2].toFloat()
            pos[k * 3] = wx; pos[k * 3 + 1] = wy; pos[k * 3 + 2] = wz
            col[k * 3] = rgb[idx * 3]
            col[k * 3 + 1] = rgb[idx * 3 + 1]
            col[k * 3 + 2] = rgb[idx * 3 + 2]
            refW[k] = when {
                directHit[idx] -> 1f
                laserWeight[idx] > 0f -> (laserWeight[idx] / (laserWeight[idx] + BASE_PRIOR_WEIGHT)).coerceIn(0f, 0.7f)
                else -> 0f
            }
            minX = min(minX, wx); minY = min(minY, wy); minZ = min(minZ, wz)
            maxX = max(maxX, wx); maxY = max(maxY, wy); maxZ = max(maxZ, wz)
            return k
        }

        var triCap = (COARSE * COARSE * 2 + totalRefinedBlocks * 32) * 3
        var indices = IntArray(triCap)
        var iCount = 0

        fun emitTri(a: Int, b: Int, c: Int, da: Float, db: Float, dc: Float) {
            val minD = min(min(da, db), dc)
            val lim = MESH_EDGE_JUMP_RATIO * minD
            if (abs(da - db) > lim || abs(da - dc) > lim || abs(db - dc) > lim) return
            if (iCount + 3 > triCap) {
                triCap = (triCap * 3) / 2 + 1024
                indices = indices.copyOf(triCap)
            }
            indices[iCount++] = a
            indices[iCount++] = b
            indices[iCount++] = c
        }

        fun emitQuad(x0: Int, y0: Int, step: Int) {
            val x1 = x0 + step; val y1 = y0 + step
            val a = ensureVertex(x0, y0); if (a < 0) return
            val b = ensureVertex(x1, y0); if (b < 0) return
            val c = ensureVertex(x0, y1); if (c < 0) return
            val d = ensureVertex(x1, y1); if (d < 0) return
            val da = effDepth[y0 * SIZE + x0]
            val db = effDepth[y0 * SIZE + x1]
            val dc = effDepth[y1 * SIZE + x0]
            val dd = effDepth[y1 * SIZE + x1]
            val minD = min(min(da, db), min(dc, dd))
            val lim = MESH_EDGE_JUMP_RATIO * minD
            if (abs(da - db) > lim || abs(da - dc) > lim || abs(db - dd) > lim || abs(dc - dd) > lim) return
            if (iCount + 6 > triCap) {
                triCap = (triCap * 3) / 2 + 1024
                indices = indices.copyOf(triCap)
            }
            indices[iCount++] = a; indices[iCount++] = b; indices[iCount++] = d
            indices[iCount++] = a; indices[iCount++] = d; indices[iCount++] = c
        }

        val ringV = IntArray(BLOCK * 4)
        val ringD = FloatArray(BLOCK * 4)

        for (by in 0 until COARSE) {
            val y0 = by * BLOCK
            val y1 = y0 + BLOCK
            for (bx in 0 until COARSE) {
                val x0 = bx * BLOCK
                val x1 = x0 + BLOCK
                if (blockRefined[by * COARSE + bx]) {
                    // Full-resolution 4x4 fine quads inside this laser-refined block.
                    for (dy in 0 until BLOCK) {
                        for (dx in 0 until BLOCK) {
                            emitQuad(x0 + dx, y0 + dy, 1)
                        }
                    }
                } else {
                    val topRef = by > 0 && blockRefined[(by - 1) * COARSE + bx]
                    val rightRef = bx + 1 < COARSE && blockRefined[by * COARSE + bx + 1]
                    val bottomRef = by + 1 < COARSE && blockRefined[(by + 1) * COARSE + bx]
                    val leftRef = bx > 0 && blockRefined[by * COARSE + bx - 1]
                    if (!topRef && !rightRef && !bottomRef && !leftRef) {
                        emitQuad(x0, y0, BLOCK)
                    } else {
                        // T-junction-free centre fan matching refined neighbor edges.
                        val cx = x0 + BLOCK / 2
                        val cy = y0 + BLOCK / 2
                        val cv = ensureVertex(cx, cy)
                        if (cv < 0) continue
                        val cd = effDepth[cy * SIZE + cx]
                        var rn = 0
                        fun push(px: Int, py: Int): Boolean {
                            val v = ensureVertex(px, py)
                            if (v < 0) return false
                            ringV[rn] = v
                            ringD[rn] = effDepth[py * SIZE + px]
                            rn++
                            return true
                        }
                        var ok = true
                        // Top edge: left -> right (excluding final corner, added by right edge)
                        val topStep = if (topRef) 1 else BLOCK
                        var px = x0
                        while (px < x1 && ok) { ok = push(px, y0); px += topStep }
                        // Right edge: top -> bottom
                        val rightStep = if (rightRef) 1 else BLOCK
                        var py = y0
                        while (py < y1 && ok) { ok = push(x1, py); py += rightStep }
                        // Bottom edge: right -> left
                        val bottomStep = if (bottomRef) 1 else BLOCK
                        px = x1
                        while (px > x0 && ok) { ok = push(px, y1); px -= bottomStep }
                        // Left edge: bottom -> top
                        val leftStep = if (leftRef) 1 else BLOCK
                        py = y1
                        while (py > y0 && ok) { ok = push(x0, py); py -= leftStep }
                        if (ok && rn >= 4) {
                            for (k in 0 until rn) {
                                val next = if (k + 1 < rn) k + 1 else 0
                                emitTri(cv, ringV[k], ringV[next], cd, ringD[k], ringD[next])
                            }
                        }
                    }
                }
            }
        }

        // Compute smooth area-weighted vertex normals facing the reference camera.
        val normals = FloatArray(vCount * 3)
        val camC = refPose.cameraCentre()
        val ccx = camC[0].toFloat(); val ccy = camC[1].toFloat(); val ccz = camC[2].toFloat()
        var t = 0
        while (t + 2 < iCount) {
            val ia = indices[t] * 3
            val ib = indices[t + 1] * 3
            val ic = indices[t + 2] * 3
            t += 3
            val ax = pos[ia]; val ay = pos[ia + 1]; val az = pos[ia + 2]
            val ex1 = pos[ib] - ax; val ey1 = pos[ib + 1] - ay; val ez1 = pos[ib + 2] - az
            val ex2 = pos[ic] - ax; val ey2 = pos[ic + 1] - ay; val ez2 = pos[ic + 2] - az
            var nx = ey1 * ez2 - ez1 * ey2
            var ny = ez1 * ex2 - ex1 * ez2
            var nz = ex1 * ey2 - ey1 * ex2
            // Orient normal toward the reference camera so shading is consistent.
            val toCamX = ccx - ax; val toCamY = ccy - ay; val toCamZ = ccz - az
            if (nx * toCamX + ny * toCamY + nz * toCamZ < 0f) {
                nx = -nx; ny = -ny; nz = -nz
            }
            normals[ia] += nx; normals[ia + 1] += ny; normals[ia + 2] += nz
            normals[ib] += nx; normals[ib + 1] += ny; normals[ib + 2] += nz
            normals[ic] += nx; normals[ic + 1] += ny; normals[ic + 2] += nz
        }
        for (k in 0 until vCount) {
            val o = k * 3
            val nx = normals[o]; val ny = normals[o + 1]; val nz = normals[o + 2]
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-9f) {
                normals[o] = nx / len; normals[o + 1] = ny / len; normals[o + 2] = nz / len
            } else {
                normals[o] = 0f; normals[o + 1] = 0f; normals[o + 2] = 1f
            }
        }

        MeshData(
            positions = pos.copyOf(vCount * 3),
            normals = normals,
            colors = col.copyOf(vCount * 3),
            refinedWeight = refW.copyOf(vCount),
            vertexCount = vCount,
            indices = indices.copyOf(iCount),
            refinedBlocks = totalRefinedBlocks,
            totalBlocks = COARSE * COARSE,
            laserCells = totalLaserCells,
            totalLaserHits = totalLaserHits,
            boundsMin = if (vCount > 0) floatArrayOf(minX, minY, minZ) else FloatArray(3),
            boundsMax = if (vCount > 0) floatArrayOf(maxX, maxY, maxZ) else FloatArray(3),
        )
    }

    companion object {
        const val SIZE = 518
        const val BLOCK = 4
        const val COARSE = (SIZE - 1) / BLOCK // 129 blocks covering [0..516]
        private const val BASE_PRIOR_WEIGHT = 0.08f
        private const val HALO_WEIGHT = 0.15f
        private const val MAX_CELL_WEIGHT = 24f
        private const val MESH_EDGE_JUMP_RATIO = 0.08f

        /** Bilinear sample of the 518x518 DA3 depth map at distorted pixel coordinates `(u, v)`. */
        fun sampleDepthBilinear(depth: FloatArray, u: Double, v: Double, camera: DepthCamera): Float {
            val gx = u * SIZE / camera.width - 0.5
            val gy = v * SIZE / camera.height - 0.5
            val x0 = Math.floor(gx).toInt().coerceIn(0, SIZE - 1)
            val y0 = Math.floor(gy).toInt().coerceIn(0, SIZE - 1)
            val x1 = (x0 + 1).coerceIn(0, SIZE - 1)
            val y1 = (y0 + 1).coerceIn(0, SIZE - 1)
            val fx = (gx - x0).coerceIn(0.0, 1.0)
            val fy = (gy - y0).coerceIn(0.0, 1.0)
            var sum = 0.0
            var wSum = 0.0
            fun acc(ix: Int, iy: Int, w: Double) {
                val d = depth[iy * SIZE + ix]
                if (d > 0f && d.isFinite()) { sum += d * w; wSum += w }
            }
            acc(x0, y0, (1.0 - fx) * (1.0 - fy))
            acc(x1, y0, fx * (1.0 - fy))
            acc(x0, y1, (1.0 - fx) * fy)
            acc(x1, y1, fx * fy)
            return if (wSum > 0.0) (sum / wSum).toFloat() else Float.NaN
        }

        /** Robust IRLS scale `z_metric = scale * d_da3` from `(da3, metricZ)` pairs. */
        fun fitScale(pairs: List<Pair<Double, Double>>, minimum: Int = 20): Double? {
            if (pairs.size < minimum) return null
            var current = pairs
            var best: Double? = null
            repeat(4) {
                var xx = 0.0; var xy = 0.0
                for ((x, y) in current) { xx += x * x; xy += x * y }
                if (xx < 1e-12) return null
                val a = xy / xx
                if (!a.isFinite() || a <= 0.0) return null
                best = a
                val residuals = current.map { abs(it.second - a * it.first) }
                val sorted = residuals.sorted()
                val mad = sorted[sorted.size / 2].coerceAtLeast(1e-6)
                val filtered = current.filterIndexed { i, _ -> residuals[i] <= 2.5 * mad }
                if (filtered.size < minimum || filtered.size == current.size) return best
                current = filtered
            }
            return best
        }

        /** Writes a binary little-endian mesh PLY with positions, normals, colours, and triangles. */
        fun writeMeshPly(file: File, mesh: MeshData, toMillimetres: Boolean = false) {
            val scale = if (toMillimetres) 1000f else 1f
            val faces = mesh.triangleCount
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                val header = "ply\nformat binary_little_endian 1.0\n" +
                    "comment EasyScanner adaptive DA3 + laser mesh (" +
                    (if (toMillimetres) "mm" else "m") + ", refinedBlocks=" + mesh.refinedBlocks + ")\n" +
                    "element vertex " + mesh.vertexCount + "\n" +
                    "property float x\nproperty float y\nproperty float z\n" +
                    "property float nx\nproperty float ny\nproperty float nz\n" +
                    "property uchar red\nproperty uchar green\nproperty uchar blue\n" +
                    "element face " + faces + "\n" +
                    "property list uchar int vertex_indices\nend_header\n"
                out.write(header.toByteArray(Charsets.US_ASCII))
                val vb = ByteBuffer.allocate(mesh.vertexCount * 27).order(ByteOrder.LITTLE_ENDIAN)
                for (k in 0 until mesh.vertexCount) {
                    val o = k * 3
                    vb.putFloat(mesh.positions[o] * scale)
                    vb.putFloat(mesh.positions[o + 1] * scale)
                    vb.putFloat(mesh.positions[o + 2] * scale)
                    vb.putFloat(mesh.normals[o])
                    vb.putFloat(mesh.normals[o + 1])
                    vb.putFloat(mesh.normals[o + 2])
                    vb.put(mesh.colors[o])
                    vb.put(mesh.colors[o + 1])
                    vb.put(mesh.colors[o + 2])
                }
                out.write(vb.array())
                val fb = ByteBuffer.allocate(faces * 13).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until faces) {
                    fb.put(3)
                    fb.putInt(mesh.indices[i * 3])
                    fb.putInt(mesh.indices[i * 3 + 1])
                    fb.putInt(mesh.indices[i * 3 + 2])
                }
                out.write(fb.array())
            }
        }
    }
}
