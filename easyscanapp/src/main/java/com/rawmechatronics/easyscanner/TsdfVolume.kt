package com.rawmechatronics.easyscanner

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phase 4 sparse voxel-block Truncated Signed Distance Field (TSDF) with incremental
 * dirty-block Marching Cubes extraction and analytic TSDF-gradient vertex normals.
 *
 * - DA3 depth patches integrate across the surface shell with small prior weight (`0.5`),
 *   producing a complete 360-degree volumetric base from any viewpoint.
 * - Metric `LASER_CAM` frames integrate with high authority weight (`8.0`), carving
 *   accurate metric detail into the 3D volume; strongly laser-carved voxels attenuate
 *   later coarse DA3 updates so laser detail is never blurred back out.
 * - Only blocks touched since the last extraction re-run Marching Cubes.
 */
class TsdfVolume(
    val voxelSizeM: Float = DEFAULT_VOXEL_SIZE_M,
    val truncationM: Float = voxelSizeM * 4f,
    val maxBlocks: Int = DEFAULT_MAX_BLOCKS,
    val maxIntegrateDepthM: Double = DEFAULT_MAX_DEPTH_M,
) {
    private class BlockMesh(
        val positions: FloatArray,
        val normals: FloatArray,
        val colors: ByteArray,
        val refinedWeight: FloatArray,
        /** Unique global edge key per vertex so cross-block merging welds shared seam vertices cleanly. */
        val edgeKeys: LongArray,
        val vertexCount: Int,
        val indices: IntArray,
        val laserVoxels: Int,
    )

    private class VoxelBlock(val bx: Int, val by: Int, val bz: Int) {
        val tsdf = FloatArray(BLOCK_VOXELS) { 1f }
        val weight = FloatArray(BLOCK_VOXELS)
        val laserWeight = FloatArray(BLOCK_VOXELS)
        val rByte = ByteArray(BLOCK_VOXELS) { 170.toByte() }
        val gByte = ByteArray(BLOCK_VOXELS) { 175.toByte() }
        val bByte = ByteArray(BLOCK_VOXELS) { 180.toByte() }
        val hasColor = BooleanArray(BLOCK_VOXELS)
        var dirty = true
        var hasLaser = false
        var cached: BlockMesh? = null
    }

    data class Stats(
        val activeBlocks: Int,
        val laserBlocks: Int,
        val totalLaserHits: Int,
        val integratedPatches: Int,
        val lastDirtyRebuilt: Int,
    )

    private val lock = Any()
    private var nativeHandle: Long = if (NativeLiveTsdf.available) {
        NativeLiveTsdf.nativeCreate(voxelSizeM, truncationM, maxBlocks, maxIntegrateDepthM)
    } else 0L
    private val blocks = HashMap<Long, VoxelBlock>()
    private var totalLaserHits = 0
    private var integratedPatches = 0
    private var lastDirtyRebuilt = 0
    private val invVoxel = 1.0 / voxelSizeM.toDouble()

    val isNative: Boolean get() = nativeHandle != 0L
    fun nativeHandle(): Long = synchronized(lock) { nativeHandle }

    fun prepareNativeMesh(): Int {
        val h = nativeHandle()
        return if (h != 0L) NativeLiveTsdf.nativePrepareMesh(h) else 0
    }

    fun sampleSurfaceForIcp(maxPoints: Int = 6000): Pair<FloatArray, FloatArray>? {
        val h = nativeHandle()
        if (h == 0L) return null
        val pos = FloatArray(maxPoints * 3)
        val norm = FloatArray(maxPoints * 3)
        val n = NativeLiveTsdf.nativeSampleSurface(h, maxPoints, pos, norm)
        if (n <= 0) return null
        return (if (n == maxPoints) pos else pos.copyOf(n * 3)) to (if (n == maxPoints) norm else norm.copyOf(n * 3))
    }

    fun writeNativeMeshPly(file: java.io.File, toMillimetres: Boolean): Int {
        val h = nativeHandle()
        if (h == 0L) return -1
        file.parentFile?.mkdirs()
        return NativeLiveTsdf.nativeWriteMeshPly(h, file.absolutePath, toMillimetres)
    }

    fun stats(): Stats = synchronized(lock) {
        if (nativeHandle != 0L) {
            val out = IntArray(5)
            NativeLiveTsdf.nativeGetStats(nativeHandle, out)
            return@synchronized Stats(out[0], out[1], out[2], out[3], out[4])
        }
        Stats(
            activeBlocks = blocks.size,
            laserBlocks = blocks.values.count { it.hasLaser },
            totalLaserHits = totalLaserHits,
            integratedPatches = integratedPatches,
            lastDirtyRebuilt = lastDirtyRebuilt,
        )
    }

    fun clear() = synchronized(lock) {
        if (nativeHandle != 0L) NativeLiveTsdf.nativeClear(nativeHandle)
        blocks.clear()
        totalLaserHits = 0
        integratedPatches = 0
        lastDirtyRebuilt = 0
    }

    fun release() = synchronized(lock) {
        if (nativeHandle != 0L) {
            NativeLiveTsdf.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        blocks.clear()
    }

    /** Direct analytic scalar-field setter (used for exact verification tests). */
    internal fun setVoxelForTest(
        gx: Int, gy: Int, gz: Int,
        tsdfVal: Float, weightVal: Float = 1f, laserWeightVal: Float = 0f,
        r: Int = 180, g: Int = 180, b: Int = 180,
    ) = synchronized(lock) {
        val blk = getOrCreateBlock(Math.floorDiv(gx, BLOCK_DIM), Math.floorDiv(gy, BLOCK_DIM), Math.floorDiv(gz, BLOCK_DIM)) ?: return@synchronized
        val lx = Math.floorMod(gx, BLOCK_DIM)
        val ly = Math.floorMod(gy, BLOCK_DIM)
        val lz = Math.floorMod(gz, BLOCK_DIM)
        val idx = localIndex(lx, ly, lz)
        blk.tsdf[idx] = tsdfVal.coerceIn(-1f, 1f)
        blk.weight[idx] = weightVal
        blk.laserWeight[idx] = laserWeightVal
        blk.rByte[idx] = r.coerceIn(0, 255).toByte()
        blk.gByte[idx] = g.coerceIn(0, 255).toByte()
        blk.bByte[idx] = b.coerceIn(0, 255).toByte()
        blk.hasColor[idx] = true
        if (laserWeightVal > 0f) blk.hasLaser = true
        markDirtyAround(blk, lx, ly, lz)
    }

    /**
     * Estimates the fraction [0, 1] of valid foreground DA3 pixels in this view that fall on
     * genuinely uncovered space (not already occupied by an existing front-facing mesh piece).
     */
    fun estimateNoveltyFraction(
        pose: RigidPose,
        camera: DepthCamera,
        rawDepth: FloatArray,
        scale: Double,
        offset: Double = 0.0,
        validMask518: BooleanArray? = null,
    ): Float {
        val h = nativeHandle()
        if (h != 0L) {
            val cam11 = doubleArrayOf(
                camera.fx, camera.fy, camera.cx, camera.cy,
                camera.width.toDouble(), camera.height.toDouble(),
                camera.k1, camera.k2, camera.p1, camera.p2, camera.k3,
            )
            return NativeLiveTsdf.nativeEstimateNovelty(h, pose.r, pose.t, cam11, rawDepth, scale, offset, validMask518)
        }
        return synchronized(lock) {
            if (integratedPatches == 0 || blocks.isEmpty()) return@synchronized 1.0f
            val occ = buildPuzzleOccupancyLocked(pose, camera, rawDepth, scale, offset)
            val size = AdaptiveDepthMesh.SIZE
            var total = 0
            var novel = 0
            for (y in 0 until size step OCC_CELL) {
                val row = y * size
                val gy = y / OCC_CELL
                for (x in 0 until size step OCC_CELL) {
                    val idx = row + x
                    if (validMask518 != null && !validMask518[idx]) continue
                    val d = rawDepth[idx]
                    if (!(d > 0f) || !d.isFinite()) continue
                    val z = d * scale + offset
                    if (z <= 0.08 || z > maxIntegrateDepthM) continue
                    total++
                    if (occ[gy * OCC_DIM + (x / OCC_CELL)].toInt() == 0) novel++
                }
            }
            if (total > 0) novel.toFloat() / total.toFloat() else 0f
        }
    }

    private fun buildPuzzleOccupancyLocked(
        pose: RigidPose,
        camera: DepthCamera,
        rawDepth: FloatArray,
        scale: Double,
        offset: Double,
    ): ByteArray {
        for (blk in blocks.values) {
            if (blk.dirty) {
                blk.cached = extractBlockMeshLocked(blk)
                blk.dirty = false
            }
        }
        val occ = ByteArray(OCC_DIM * OCC_DIM)
        val camC = pose.cameraCentre()
        val camX = camC[0]; val camY = camC[1]; val camZ = camC[2]
        val size = AdaptiveDepthMesh.SIZE
        val rMat = pose.r; val tVec = pose.t
        for (blk in blocks.values) {
            val bm = blk.cached ?: continue
            val pos = bm.positions
            val nrm = bm.normals
            for (k in 0 until bm.vertexCount) {
                val o = k * 3
                val wx = pos[o].toDouble(); val wy = pos[o + 1].toDouble(); val wz = pos[o + 2].toDouble()
                val xc = rMat[0] * wx + rMat[1] * wy + rMat[2] * wz + tVec[0]
                val yc = rMat[3] * wx + rMat[4] * wy + rMat[5] * wz + tVec[1]
                val zc = rMat[6] * wx + rMat[7] * wy + rMat[8] * wz + tVec[2]
                if (zc <= 0.05 || zc > maxIntegrateDepthM + 0.15) continue
                val uv = camera.project(xc, yc, zc) ?: continue
                if (uv[0] < 0.0 || uv[0] >= camera.width || uv[1] < 0.0 || uv[1] >= camera.height) continue
                val sx = (uv[0] * size / camera.width).toInt().coerceIn(0, size - 1)
                val sy = (uv[1] * size / camera.height).toInt().coerceIn(0, size - 1)
                val dNew = rawDepth[sy * size + sx]
                if (!(dNew > 0f) || !dNew.isFinite()) continue
                val zNew = dNew * scale + offset
                val dz = abs(zc - zNew)
                val vx = camX - wx; val vy = camY - wy; val vz = camZ - wz
                val vlen = sqrt(vx * vx + vy * vy + vz * vz)
                val ndot = if (vlen > 1e-6) (nrm[o] * vx + nrm[o + 1] * vy + nrm[o + 2] * vz) / vlen else 0.0
                val sameLayerTol = max(0.10, 0.25 * zc)
                if ((ndot > 0.0 && dz < sameLayerTol) || dz < 0.025) {
                    val gx = sx / OCC_CELL
                    val gy = sy / OCC_CELL
                    val y0 = max(0, gy - 1); val y1 = min(OCC_DIM - 1, gy + 1)
                    val x0 = max(0, gx - 1); val x1 = min(OCC_DIM - 1, gx + 1)
                    for (yy in y0..y1) for (xx in x0..x1) occ[yy * OCC_DIM + xx] = 1
                }
            }
        }
        val seam = occ.copyOf()
        for (gy in 0 until OCC_DIM) {
            val y0 = max(0, gy - 2); val y1 = min(OCC_DIM - 1, gy + 2)
            for (gx in 0 until OCC_DIM) {
                val idx = gy * OCC_DIM + gx
                if (occ[idx].toInt() != 1) continue
                val x0 = max(0, gx - 2); val x1 = min(OCC_DIM - 1, gx + 2)
                var touchesEmpty = false
                outer@ for (yy in y0..y1) {
                    for (xx in x0..x1) {
                        if (occ[yy * OCC_DIM + xx].toInt() == 0) { touchesEmpty = true; break@outer }
                    }
                }
                if (touchesEmpty) seam[idx] = 2
            }
        }
        return seam
    }

    /**
     * Integrates one scaled DA3 depth patch into the sparse volumetric TSDF with low prior
     * weight so it builds a complete 3D base surface without overwriting metric laser detail.
     */
    fun integrateDepthMap(
        pose: RigidPose,
        camera: DepthCamera,
        rawDepth: FloatArray,
        scale: Double,
        offset: Double = 0.0,
        rgb518: ByteArray,
        validMask518: BooleanArray? = null,
        stride: Int = 2,
        da3Weight: Float = DA3_PRIOR_WEIGHT,
    ) {
        val h = nativeHandle()
        if (h != 0L) {
            val cam11 = doubleArrayOf(
                camera.fx, camera.fy, camera.cx, camera.cy,
                camera.width.toDouble(), camera.height.toDouble(),
                camera.k1, camera.k2, camera.p1, camera.p2, camera.k3,
            )
            NativeLiveTsdf.nativeIntegrateDepth(h, pose.r, pose.t, cam11, rawDepth, scale, offset, rgb518, validMask518, stride, da3Weight)
            return
        }
        synchronized(lock) {
        val puzzleMode = integratedPatches > 0 && blocks.isNotEmpty()
        val occ = if (puzzleMode) buildPuzzleOccupancyLocked(pose, camera, rawDepth, scale, offset) else null
        // Stage 1: allocate the sparse surface-shell voxel blocks intersected by this depth map.
        val trunc = truncationM.toDouble()
        val size = AdaptiveDepthMesh.SIZE
        val touchedBlocks = HashSet<VoxelBlock>()
        val allocStride = max(2, stride)
        for (y in 0 until size step allocStride) {
            val vf = (y + 0.5) * camera.height / size
            val row = y * size
            val gyOcc = y / OCC_CELL
            for (x in 0 until size step allocStride) {
                if (occ != null && occ[gyOcc * OCC_DIM + (x / OCC_CELL)].toInt() == 1) continue
                val idx = row + x
                if (validMask518 != null && !validMask518[idx]) continue
                val d = rawDepth[idx]
                if (!(d > 0f) || !d.isFinite()) continue
                val z = d * scale + offset
                if (z <= 0.08 || z > maxIntegrateDepthM) continue
                val uf = (x + 0.5) * camera.width / size
                val ray = camera.undistort(uf, vf)
                val wPt = pose.cameraToWorld(ray[0] * z, ray[1] * z, z)
                val gx = floor(wPt[0] * invVoxel).toInt()
                val gy = floor(wPt[1] * invVoxel).toInt()
                val gz = floor(wPt[2] * invVoxel).toInt()
                val bx = Math.floorDiv(gx, BLOCK_DIM)
                val by = Math.floorDiv(gy, BLOCK_DIM)
                val bz = Math.floorDiv(gz, BLOCK_DIM)
                val lx = Math.floorMod(gx, BLOCK_DIM)
                val ly = Math.floorMod(gy, BLOCK_DIM)
                val lz = Math.floorMod(gz, BLOCK_DIM)
                val dxMin = if (lx <= 2) -1 else 0; val dxMax = if (lx >= BLOCK_DIM - 3) 1 else 0
                val dyMin = if (ly <= 2) -1 else 0; val dyMax = if (ly >= BLOCK_DIM - 3) 1 else 0
                val dzMin = if (lz <= 2) -1 else 0; val dzMax = if (lz >= BLOCK_DIM - 3) 1 else 0
                for (dz in dzMin..dzMax) for (dy in dyMin..dyMax) for (dx in dxMin..dxMax) {
                    val b = getOrCreateBlock(bx + dx, by + dy, bz + dz) ?: continue
                    touchedBlocks.add(b)
                }
            }
        }

        // Stage 2: projective TSDF update for EVERY voxel in every touched surface block
        // (guarantees zero inter-ray holes at any camera distance or voxel resolution).
        val rMat = pose.r; val tVec = pose.t
        for (blk in touchedBlocks) {
            val gx0 = blk.bx * BLOCK_DIM; val gy0 = blk.by * BLOCK_DIM; val gz0 = blk.bz * BLOCK_DIM
            for (lz in 0 until BLOCK_DIM) {
                val wz = (gz0 + lz + 0.5) * voxelSizeM
                for (ly in 0 until BLOCK_DIM) {
                    val wy = (gy0 + ly + 0.5) * voxelSizeM
                    for (lx in 0 until BLOCK_DIM) {
                        val wx = (gx0 + lx + 0.5) * voxelSizeM
                        val xc = rMat[0] * wx + rMat[1] * wy + rMat[2] * wz + tVec[0]
                        val yc = rMat[3] * wx + rMat[4] * wy + rMat[5] * wz + tVec[1]
                        val zc = rMat[6] * wx + rMat[7] * wy + rMat[8] * wz + tVec[2]
                        if (zc <= 0.08 || zc > maxIntegrateDepthM + trunc) continue
                        val uv = camera.project(xc, yc, zc) ?: continue
                        if (uv[0] < 0.0 || uv[0] >= camera.width || uv[1] < 0.0 || uv[1] >= camera.height) continue
                        val sx = (uv[0] * size / camera.width).toInt().coerceIn(0, size - 1)
                        val sy = (uv[1] * size / camera.height).toInt().coerceIn(0, size - 1)
                        val cellState = occ?.get((sy / OCC_CELL) * OCC_DIM + (sx / OCC_CELL))?.toInt() ?: 0
                        if (cellState == 1) continue
                        val sIdx = sy * size + sx
                        if (validMask518 != null && !validMask518[sIdx]) continue
                        val dRaw = AdaptiveDepthMesh.sampleDepthBilinear(rawDepth, uv[0], uv[1], camera)
                        if (!(dRaw > 0f) || !dRaw.isFinite()) continue
                        val dMetric = dRaw * scale + offset
                        if (dMetric > maxIntegrateDepthM) continue
                        val sdf = dMetric - zc
                        if (sdf < -trunc || sdf > trunc) continue
                        val tsdfSample = (sdf / trunc).toFloat().coerceIn(-1f, 1f)
                        val vIdx = localIndex(lx, ly, lz)
                        val prevLW = blk.laserWeight[vIdx]
                        val prevW = blk.weight[vIdx]
                        val effW = if (puzzleMode) {
                            if (prevLW > 1f) continue
                            if (cellState == 2) {
                                if (prevW > 0f && abs(tsdfSample - blk.tsdf[vIdx]) > 0.65f) continue
                                da3Weight * 0.25f
                            } else if (prevW > 0f) {
                                if (abs(tsdfSample - blk.tsdf[vIdx]) > 0.70f) continue
                                da3Weight * 0.20f
                            } else da3Weight
                        } else {
                            if (prevLW > 4f) da3Weight * 0.15f else da3Weight
                        }
                        blk.tsdf[vIdx] = if (prevW <= 0f) tsdfSample else (blk.tsdf[vIdx] * prevW + tsdfSample * effW) / (prevW + effW)
                        blk.weight[vIdx] = min(MAX_VOXEL_WEIGHT, prevW + effW)
                        val nr = rgb518[sIdx * 3].toInt() and 0xff
                        val ng = rgb518[sIdx * 3 + 1].toInt() and 0xff
                        val nb = rgb518[sIdx * 3 + 2].toInt() and 0xff
                        if (!blk.hasColor[vIdx]) {
                            blk.rByte[vIdx] = nr.toByte(); blk.gByte[vIdx] = ng.toByte(); blk.bByte[vIdx] = nb.toByte()
                            blk.hasColor[vIdx] = true
                        } else {
                            val a = (effW / (prevW + effW)).coerceIn(0.05f, 0.5f)
                            blk.rByte[vIdx] = ((blk.rByte[vIdx].toInt() and 0xff) * (1f - a) + nr * a).toInt().coerceIn(0, 255).toByte()
                            blk.gByte[vIdx] = ((blk.gByte[vIdx].toInt() and 0xff) * (1f - a) + ng * a).toInt().coerceIn(0, 255).toByte()
                            blk.bByte[vIdx] = ((blk.bByte[vIdx].toInt() and 0xff) * (1f - a) + nb * a).toInt().coerceIn(0, 255).toByte()
                        }
                        markDirtyAround(blk, lx, ly, lz)
                    }
                }
            }
        }
        integratedPatches++
        }
    }

    /**
     * Integrates every camera-frame metric laser point (`[x,y,z,...]` metres) with high
     * metric weight (`8.0`), carving sharp 3D detail into the volumetric TSDF.
     * Never subsamples or caps laser points.
     */
    fun integrateLaserFrame(
        pose: RigidPose,
        laserCamM: FloatArray,
        laserWeightVal: Float = LASER_AUTHORITY_WEIGHT,
    ): Int {
        val h = nativeHandle()
        if (h != 0L) {
            return NativeLiveTsdf.nativeIntegrateLaser(h, pose.r, pose.t, laserCamM, laserWeightVal)
        }
        return synchronized(lock) {
        val camC = pose.cameraCentre()
        val cx = camC[0]; val cy = camC[1]; val cz = camC[2]
        val trunc = truncationM.toDouble()
        val stepM = voxelSizeM * 0.6
        var integrated = 0
        var i = 0
        while (i + 2 < laserCamM.size) {
            val lx = laserCamM[i].toDouble()
            val ly = laserCamM[i + 1].toDouble()
            val lz = laserCamM[i + 2].toDouble()
            i += 3
            if (!lx.isFinite() || !ly.isFinite() || !lz.isFinite() || lz <= 0.04 || lz > 10.0) continue
            val wPt = pose.cameraToWorld(lx, ly, lz)
            if (!wPt[0].isFinite() || !wPt[1].isFinite() || !wPt[2].isFinite()) continue
            integrateRayLocked(cx, cy, cz, wPt[0], wPt[1], wPt[2], trunc, stepM, laserWeightVal, isLaser = true, -1, -1, -1)
            integrated++
        }
        totalLaserHits += integrated
        integrated
        }
    }

    private fun integrateRayLocked(
        cx: Double, cy: Double, cz: Double,
        px: Double, py: Double, pz: Double,
        trunc: Double, stepM: Double, baseWeight: Float, isLaser: Boolean,
        r: Int, g: Int, b: Int,
    ) {
        val vx = px - cx; val vy = py - cy; val vz = pz - cz
        val dist = sqrt(vx * vx + vy * vy + vz * vz)
        if (!dist.isFinite() || dist <= 0.04 || dist > 15.0) return
        val invD = 1.0 / dist
        val dx = vx * invD; val dy = vy * invD; val dz = vz * invD
        val tStart = max(0.02, dist - trunc)
        val tEnd = dist + trunc
        var prevGx = Int.MIN_VALUE; var prevGy = Int.MIN_VALUE; var prevGz = Int.MIN_VALUE
        var t = tStart
        while (t <= tEnd) {
            val wx = cx + dx * t
            val wy = cy + dy * t
            val wz = cz + dz * t
            val fx = wx * invVoxel - 0.5
            val fy = wy * invVoxel - 0.5
            val fz = wz * invVoxel - 0.5
            val gx0 = floor(fx).toInt()
            val gy0 = floor(fy).toInt()
            val gz0 = floor(fz).toInt()
            if (gx0 != prevGx || gy0 != prevGy || gz0 != prevGz) {
                prevGx = gx0; prevGy = gy0; prevGz = gz0
                val ux = (fx - gx0).coerceIn(0.0, 1.0)
                val uy = (fy - gy0).coerceIn(0.0, 1.0)
                val uz = (fz - gz0).coerceIn(0.0, 1.0)
                // Trilinearly splat the metric laser ray onto the 2x2x2 voxel cell so a single
                // stripe immediately updates all cube corners cleanly with exact signed distance.
                for (oz in 0..1) {
                    val wzf = if (oz == 0) 1.0 - uz else uz
                    if (wzf < 0.05) continue
                    val gz = gz0 + oz
                    val vcz = (gz + 0.5) * voxelSizeM
                    for (oy in 0..1) {
                        val wyf = if (oy == 0) 1.0 - uy else uy
                        if (wyf < 0.05) continue
                        val gy = gy0 + oy
                        val vcy = (gy + 0.5) * voxelSizeM
                        for (ox in 0..1) {
                            val wxf = if (ox == 0) 1.0 - ux else ux
                            val triW = (wxf * wyf * wzf).toFloat()
                            if (triW < 0.05f) continue
                            val gx = gx0 + ox
                            val vcx = (gx + 0.5) * voxelSizeM
                            val rayDepth = (vcx - cx) * dx + (vcy - cy) * dy + (vcz - cz) * dz
                            val sdf = dist - rayDepth
                            if (sdf < -trunc || sdf > trunc) continue
                            val tsdfSample = (sdf / trunc).toFloat().coerceIn(-1f, 1f)
                            val bx = Math.floorDiv(gx, BLOCK_DIM)
                            val by = Math.floorDiv(gy, BLOCK_DIM)
                            val bz = Math.floorDiv(gz, BLOCK_DIM)
                            val blk = getOrCreateBlock(bx, by, bz) ?: continue
                            val lx = Math.floorMod(gx, BLOCK_DIM)
                            val ly = Math.floorMod(gy, BLOCK_DIM)
                            val lz = Math.floorMod(gz, BLOCK_DIM)
                            val vIdx = localIndex(lx, ly, lz)
                            val prevLW = blk.laserWeight[vIdx]
                            val effW = (if (!isLaser && prevLW > 4f) baseWeight * 0.15f else baseWeight) * triW
                            val prevW = blk.weight[vIdx]
                            blk.tsdf[vIdx] = if (prevW <= 0f) tsdfSample else (blk.tsdf[vIdx] * prevW + tsdfSample * effW) / (prevW + effW)
                            blk.weight[vIdx] = min(MAX_VOXEL_WEIGHT, prevW + effW)
                            if (isLaser) {
                                blk.laserWeight[vIdx] = min(MAX_VOXEL_WEIGHT, prevLW + effW)
                                blk.hasLaser = true
                            }
                            if (r >= 0 && !blk.hasColor[vIdx]) {
                                blk.rByte[vIdx] = r.toByte(); blk.gByte[vIdx] = g.toByte(); blk.bByte[vIdx] = b.toByte()
                                blk.hasColor[vIdx] = true
                            }
                            markDirtyAround(blk, lx, ly, lz)
                        }
                    }
                }
            }
            t += stepM
        }
    }

    /** Extracts a single watertight volumetric triangle mesh (rebuilding only dirty blocks). */
    fun extractMesh(): AdaptiveDepthMesh.MeshData = synchronized(lock) {
        var rebuilt = 0
        for (blk in blocks.values) {
            if (blk.dirty) {
                blk.cached = extractBlockMeshLocked(blk)
                blk.dirty = false
                rebuilt++
            }
        }
        lastDirtyRebuilt = rebuilt

        // Merge cached block meshes while welding shared cross-block seam vertices by exact edgeKey.
        var estVerts = 0
        var estIdx = 0
        var laserBlocks = 0
        var laserCells = 0
        for (blk in blocks.values) {
            if (blk.hasLaser) laserBlocks++
            val c = blk.cached ?: continue
            estVerts += c.vertexCount
            estIdx += c.indices.size
            laserCells += c.laserVoxels
        }
        if (estIdx == 0) {
            return@synchronized AdaptiveDepthMesh.MeshData(
                FloatArray(0), FloatArray(0), ByteArray(0), FloatArray(0), 0, IntArray(0),
                laserBlocks, blocks.size, laserCells, totalLaserHits, FloatArray(3), FloatArray(3),
            )
        }

        val weldedMap = HashMap<Long, Int>(estVerts)
        val pos = FloatArray(estVerts * 3)
        val norm = FloatArray(estVerts * 3)
        val col = ByteArray(estVerts * 3)
        val refW = FloatArray(estVerts)
        val idxOut = IntArray(estIdx)
        var vOut = 0
        var iOut = 0
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY

        for (blk in blocks.values) {
            val c = blk.cached ?: continue
            if (c.indices.isEmpty()) continue
            val localToGlobal = IntArray(c.vertexCount)
            for (k in 0 until c.vertexCount) {
                val key = c.edgeKeys[k]
                val existing = weldedMap[key]
                if (existing != null) {
                    localToGlobal[k] = existing
                } else {
                    val g = vOut++
                    weldedMap[key] = g
                    localToGlobal[k] = g
                    val so = k * 3; val go = g * 3
                    val x = c.positions[so]; val y = c.positions[so + 1]; val z = c.positions[so + 2]
                    pos[go] = x; pos[go + 1] = y; pos[go + 2] = z
                    norm[go] = c.normals[so]; norm[go + 1] = c.normals[so + 1]; norm[go + 2] = c.normals[so + 2]
                    col[go] = c.colors[so]; col[go + 1] = c.colors[so + 1]; col[go + 2] = c.colors[so + 2]
                    refW[g] = c.refinedWeight[k]
                    minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
                    maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
                }
            }
            for (t in c.indices.indices) {
                idxOut[iOut++] = localToGlobal[c.indices[t]]
            }
        }

        AdaptiveDepthMesh.MeshData(
            positions = pos.copyOf(vOut * 3),
            normals = norm.copyOf(vOut * 3),
            colors = col.copyOf(vOut * 3),
            refinedWeight = refW.copyOf(vOut),
            vertexCount = vOut,
            indices = if (iOut == idxOut.size) idxOut else idxOut.copyOf(iOut),
            refinedBlocks = laserBlocks,
            totalBlocks = blocks.size,
            laserCells = laserCells,
            totalLaserHits = totalLaserHits,
            boundsMin = if (vOut > 0) floatArrayOf(minX, minY, minZ) else FloatArray(3),
            boundsMax = if (vOut > 0) floatArrayOf(maxX, maxY, maxZ) else FloatArray(3),
        )
    }

    private fun extractBlockMeshLocked(blk: VoxelBlock): BlockMesh? {
        val gx0 = blk.bx * BLOCK_DIM
        val gy0 = blk.by * BLOCK_DIM
        val gz0 = blk.bz * BLOCK_DIM
        // Local edge vertex cache for 9x9x9x3 edges inside and on the +1 border of this 8x8x8 block.
        val edgeCache = IntArray(9 * 9 * 9 * 3) { -1 }
        var vCap = 128
        var pos = FloatArray(vCap * 3)
        var norm = FloatArray(vCap * 3)
        var col = ByteArray(vCap * 3)
        var refW = FloatArray(vCap)
        var keys = LongArray(vCap)
        var vCount = 0
        var iCap = 384
        var indices = IntArray(iCap)
        var iCount = 0
        var laserVoxels = 0

        val cTsdf = FloatArray(8)
        val cWeight = FloatArray(8)
        val cLaserW = FloatArray(8)
        val cR = FloatArray(8)
        val cG = FloatArray(8)
        val cB = FloatArray(8)
        val eVerts = IntArray(12)

        for (lz in 0 until BLOCK_DIM) {
            val gz = gz0 + lz
            for (ly in 0 until BLOCK_DIM) {
                val gy = gy0 + ly
                for (lx in 0 until BLOCK_DIM) {
                    val gx = gx0 + lx
                    val selfIdx = localIndex(lx, ly, lz)
                    if (blk.laserWeight[selfIdx] > 0f) laserVoxels++
                    if (blk.weight[selfIdx] < MIN_CORNER_WEIGHT) continue

                    var allValid = true
                    var mask = 0
                    for (c in 0 until 8) {
                        val cx = lx + MarchingCubesTables.CORNER_DX[c]
                        val cy = ly + MarchingCubesTables.CORNER_DY[c]
                        val cz = lz + MarchingCubesTables.CORNER_DZ[c]
                        val sample = sampleCornerLocked(blk, gx0 + cx, gy0 + cy, gz0 + cz, cx, cy, cz)
                        if (sample == null) { allValid = false; break }
                        val tv = sample.tsdf
                        cTsdf[c] = tv
                        cWeight[c] = sample.weight
                        cLaserW[c] = sample.laserWeight
                        cR[c] = sample.r; cG[c] = sample.g; cB[c] = sample.b
                        if (tv < 0f) mask = mask or (1 shl c)
                    }
                    if (!allValid) continue
                    val edgeMask = MarchingCubesTables.EDGE_TABLE[mask]
                    if (edgeMask == 0) continue

                    for (e in 0 until 12) {
                        if ((edgeMask and (1 shl e)) == 0) continue
                        val ca = MarchingCubesTables.EDGE_A[e]
                        val cb = MarchingCubesTables.EDGE_B[e]
                        val ax = lx + MarchingCubesTables.CORNER_DX[ca]
                        val ay = ly + MarchingCubesTables.CORNER_DY[ca]
                        val az = lz + MarchingCubesTables.CORNER_DZ[ca]
                        val bx = lx + MarchingCubesTables.CORNER_DX[cb]
                        val by = ly + MarchingCubesTables.CORNER_DY[cb]
                        val bz = lz + MarchingCubesTables.CORNER_DZ[cb]
                        // Canonical edge origin + axis (0=X, 1=Y, 2=Z).
                        val ox = min(ax, bx); val oy = min(ay, by); val oz = min(az, bz)
                        val axis = when {
                            ax != bx -> 0
                            ay != by -> 1
                            else -> 2
                        }
                        val cacheSlot = ((oz * 9 + oy) * 9 + ox) * 3 + axis
                        val cachedV = edgeCache[cacheSlot]
                        if (cachedV >= 0) {
                            eVerts[e] = cachedV
                            continue
                        }

                        val va = cTsdf[ca]; val vb = cTsdf[cb]
                        val denom = vb - va
                        val t = if (abs(denom) > 1e-6f) (-va / denom).coerceIn(0f, 1f) else 0.5f
                        val wx = (gx0 + ax + t * (bx - ax) + 0.5f) * voxelSizeM
                        val wy = (gy0 + ay + t * (by - ay) + 0.5f) * voxelSizeM
                        val wz = (gz0 + az + t * (bz - az) + 0.5f) * voxelSizeM

                        // Analytic smooth TSDF-gradient normal interpolated across the crossed edge.
                        val na = gradientLocked(gx0 + ax, gy0 + ay, gz0 + az)
                        val nb = gradientLocked(gx0 + bx, gy0 + by, gz0 + bz)
                        var nx = na[0] * (1f - t) + nb[0] * t
                        var ny = na[1] * (1f - t) + nb[1] * t
                        var nz = na[2] * (1f - t) + nb[2] * t
                        val nLen = sqrt(nx * nx + ny * ny + nz * nz)
                        if (nLen > 1e-6f) { nx /= nLen; ny /= nLen; nz /= nLen }
                        else { nx = 0f; ny = 0f; nz = 1f }

                        val rr = (cR[ca] * (1f - t) + cR[cb] * t).toInt().coerceIn(0, 255)
                        val gg = (cG[ca] * (1f - t) + cG[cb] * t).toInt().coerceIn(0, 255)
                        val bb = (cB[ca] * (1f - t) + cB[cb] * t).toInt().coerceIn(0, 255)
                        val lw = cLaserW[ca] * (1f - t) + cLaserW[cb] * t
                        val rw = if (lw > 0f) (lw / (lw + 1.5f)).coerceIn(0f, 1f) else 0f

                        if (vCount >= vCap) {
                            vCap = (vCap * 3) / 2 + 64
                            pos = pos.copyOf(vCap * 3); norm = norm.copyOf(vCap * 3)
                            col = col.copyOf(vCap * 3); refW = refW.copyOf(vCap); keys = keys.copyOf(vCap)
                        }
                        val vi = vCount++
                        edgeCache[cacheSlot] = vi
                        eVerts[e] = vi
                        pos[vi * 3] = wx; pos[vi * 3 + 1] = wy; pos[vi * 3 + 2] = wz
                        norm[vi * 3] = nx; norm[vi * 3 + 1] = ny; norm[vi * 3 + 2] = nz
                        col[vi * 3] = rr.toByte(); col[vi * 3 + 1] = gg.toByte(); col[vi * 3 + 2] = bb.toByte()
                        refW[vi] = rw
                        keys[vi] = packEdgeKey(gx0 + ox, gy0 + oy, gz0 + oz, axis)
                    }

                    val base = mask * 16
                    var k = 0
                    while (k < 15) {
                        val e0 = MarchingCubesTables.TRI_TABLE[base + k]
                        if (e0 < 0) break
                        val e1 = MarchingCubesTables.TRI_TABLE[base + k + 1]
                        val e2 = MarchingCubesTables.TRI_TABLE[base + k + 2]
                        if (iCount + 3 > iCap) {
                            iCap = (iCap * 3) / 2 + 128
                            indices = indices.copyOf(iCap)
                        }
                        indices[iCount++] = eVerts[e0]
                        indices[iCount++] = eVerts[e1]
                        indices[iCount++] = eVerts[e2]
                        k += 3
                    }
                }
            }
        }
        if (iCount == 0) return null
        return BlockMesh(
            positions = pos.copyOf(vCount * 3),
            normals = norm.copyOf(vCount * 3),
            colors = col.copyOf(vCount * 3),
            refinedWeight = refW.copyOf(vCount),
            edgeKeys = keys.copyOf(vCount),
            vertexCount = vCount,
            indices = indices.copyOf(iCount),
            laserVoxels = laserVoxels,
        )
    }

    private class CornerSample(
        var tsdf: Float = 0f,
        var weight: Float = 0f,
        var laserWeight: Float = 0f,
        var r: Float = 160f,
        var g: Float = 160f,
        var b: Float = 160f,
    )
    private val scratchCorner = CornerSample()

    private fun sampleCornerLocked(
        blk: VoxelBlock, gx: Int, gy: Int, gz: Int, lx: Int, ly: Int, lz: Int,
    ): CornerSample? {
        val target: VoxelBlock
        val idx: Int
        if (lx in 0 until BLOCK_DIM && ly in 0 until BLOCK_DIM && lz in 0 until BLOCK_DIM) {
            target = blk
            idx = localIndex(lx, ly, lz)
        } else {
            val nbx = Math.floorDiv(gx, BLOCK_DIM)
            val nby = Math.floorDiv(gy, BLOCK_DIM)
            val nbz = Math.floorDiv(gz, BLOCK_DIM)
            target = blocks[packBlockKey(nbx, nby, nbz)] ?: return null
            idx = localIndex(Math.floorMod(gx, BLOCK_DIM), Math.floorMod(gy, BLOCK_DIM), Math.floorMod(gz, BLOCK_DIM))
        }
        val w = target.weight[idx]
        if (w < MIN_CORNER_WEIGHT) return null
        scratchCorner.tsdf = target.tsdf[idx]
        scratchCorner.weight = w
        scratchCorner.laserWeight = target.laserWeight[idx]
        scratchCorner.r = (target.rByte[idx].toInt() and 0xff).toFloat()
        scratchCorner.g = (target.gByte[idx].toInt() and 0xff).toFloat()
        scratchCorner.b = (target.bByte[idx].toInt() and 0xff).toFloat()
        return scratchCorner
    }

    private fun tsdfAtLocked(gx: Int, gy: Int, gz: Int, fallback: Float): Float {
        val blk = blocks[packBlockKey(Math.floorDiv(gx, BLOCK_DIM), Math.floorDiv(gy, BLOCK_DIM), Math.floorDiv(gz, BLOCK_DIM))] ?: return fallback
        val idx = localIndex(Math.floorMod(gx, BLOCK_DIM), Math.floorMod(gy, BLOCK_DIM), Math.floorMod(gz, BLOCK_DIM))
        return if (blk.weight[idx] >= MIN_CORNER_WEIGHT) blk.tsdf[idx] else fallback
    }

    /** Central-difference TSDF gradient pointing from negative (inside) to positive (outside). */
    private fun gradientLocked(gx: Int, gy: Int, gz: Int): FloatArray {
        val c = tsdfAtLocked(gx, gy, gz, 0f)
        val dx = tsdfAtLocked(gx + 1, gy, gz, c) - tsdfAtLocked(gx - 1, gy, gz, c)
        val dy = tsdfAtLocked(gx, gy + 1, gz, c) - tsdfAtLocked(gx, gy - 1, gz, c)
        val dz = tsdfAtLocked(gx, gy, gz + 1, c) - tsdfAtLocked(gx, gy, gz - 1, c)
        return floatArrayOf(dx, dy, dz)
    }

    private fun getOrCreateBlock(bx: Int, by: Int, bz: Int): VoxelBlock? {
        val key = packBlockKey(bx, by, bz)
        blocks[key]?.let { return it }
        if (blocks.size >= maxBlocks) return null
        val blk = VoxelBlock(bx, by, bz)
        blocks[key] = blk
        return blk
    }

    private fun markDirtyAround(blk: VoxelBlock, lx: Int, ly: Int, lz: Int) {
        blk.dirty = true
        // Neighbor blocks on the -X/-Y/-Z side sample lx=0/ly=0/lz=0 across their +1 boundary.
        val mx = if (lx == 0) -1 else 0
        val my = if (ly == 0) -1 else 0
        val mz = if (lz == 0) -1 else 0
        if (mx == 0 && my == 0 && mz == 0) return
        for (dz in mz..0) for (dy in my..0) for (dx in mx..0) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            blocks[packBlockKey(blk.bx + dx, blk.by + dy, blk.bz + dz)]?.dirty = true
        }
    }

    companion object {
        const val BLOCK_DIM = 8
        const val BLOCK_VOXELS = BLOCK_DIM * BLOCK_DIM * BLOCK_DIM
        const val DEFAULT_VOXEL_SIZE_M = 0.004f
        const val DEFAULT_MAX_BLOCKS = 6000
        const val DEFAULT_MAX_DEPTH_M = 1.5
        const val DA3_PRIOR_WEIGHT = 0.5f
        const val LASER_AUTHORITY_WEIGHT = 8.0f
        private const val MIN_CORNER_WEIGHT = 0.25f
        private const val MAX_VOXEL_WEIGHT = 120f
        private const val OCC_CELL = 4
        private const val OCC_DIM = (AdaptiveDepthMesh.SIZE + OCC_CELL - 1) / OCC_CELL

        private fun localIndex(lx: Int, ly: Int, lz: Int): Int = (lz shl 6) or (ly shl 3) or lx

        private fun packBlockKey(bx: Int, by: Int, bz: Int): Long =
            (bx.toLong() and 0x1fffffL) or
                ((by.toLong() and 0x1fffffL) shl 21) or
                ((bz.toLong() and 0x1fffffL) shl 42)

        /** Packs global voxel edge `(gx, gy, gz, axis)` into a unique 64-bit key (20 bits/axis + 2 bits). */
        private fun packEdgeKey(gx: Int, gy: Int, gz: Int, axis: Int): Long =
            (gx.toLong() and 0xfffffL) or
                ((gy.toLong() and 0xfffffL) shl 20) or
                ((gz.toLong() and 0xfffffL) shl 40) or
                ((axis.toLong() and 0x3L) shl 60)
    }
}
